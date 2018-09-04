package kamon
package instrumentation

import java.time.Duration

import com.typesafe.config.Config
import kamon.context.Context
import kamon.context.HttpPropagation.Direction
import kamon.instrumentation.HttpServer.Settings.TagMode
import kamon.trace.{IdentityProvider, Span}
import kamon.util.GlobPathFilter
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
  * HTTP Server instrumentation handler that takes care of context propagation, distributed tracing and HTTP server
  * metrics. Instances can be created by using the [[HttpServer.from]] method with the desired configuration name. All
  * configuration for the default HTTP instrumentation is at "kamon.instrumentation.http-server.default".
  *
  * The default implementation shipping with Kamon provides:
  *
  * - Context Propagation: Incoming and Returning context propagation as well as incoming context tags. Context
  *   propagation is further used to enable distributed tracing on top of any instrumented HTTP Server.
  * - Distributed Tracing: Automatically join traces initiated by the callers of this service and apply span and metric
  *   tags from the incoming requests as well as form the incoming context tags.
  * - Server Metrics: Basic request processing metrics to understand connection usage, throughput and response code
  *   counts in the HTTP server.
  *
  */
trait HttpServer {

  /**
    * Initiate handling of a HTTP request received by this server. The returned RequestHandler contains the Span that
    * represents the processing of the incoming HTTP request (if tracing is enabled) and the Context extracted from
    * HTTP headers (if context propagation is enabled).
    *
    * Callers of this method **must** always ensure that the doneReceiving, send and doneSending callbacks are invoked
    * for all incoming requests.
    *
    * @param request A HttpRequest wrapper on the original incoming HTTP request.
    * @return The RequestHandler that will follow the lifecycle of the incoming request.
    */
  def receive(request: HttpRequest): HttpServer.RequestHandler


  /**
    * Signals that a new HTTP connection has been opened.
    */
  def openConnection(): Unit


  /**
    * Signals that a HTTP connection has been closed. If the connection lifetime or the number of handled requests
    * cannot be determined the the values [[Duration.ZERO]] and zero can be provided, respectively. No metrics will
    * be updated when the values are zero.
    *
    * @param lifetime For how long did the connection remain open.
    * @param handledRequests How many requests where handled by the closed connection.
    */
  def closeConnection(lifetime: Duration, handledRequests: Long): Unit

}

object HttpServer {

  /**
    * Handler associated to the processing of a single request. The instrumentation code using this class is responsible
    * of creating a dedicated [[HttpServer.RequestHandler]] instance for each received request should invoking the
    * doneReceiving, send and doneSending callbacks when appropriate.
    */
  trait RequestHandler {

    /**
      * If context propagation is enabled this function returns the incoming context associated wih this request,
      * otherwise [[Context.Empty]] is returned.
      */
    def context: Context

    /**
      * Span representing the current HTTP server operation. If tracing is disabled this will return an empty span.
      */
    def span: Span

    /**
      * Signals that the entire request (headers and body) has been received.
      *
      * @param receivedBytes Size of the entire HTTP request.
      */
    def doneReceiving(receivedBytes: Long): Unit

    /**
      * Process a response to be sent back to the client. Since returning keys might need to included in the response
      * headers, users of this class must ensure that the returned HttpResponse is used instead of the original one
      * passed into this function.
      *
      * @param response Wraps the HTTP response to be sent back to the client.
      * @param context Context that should be used for writing returning keys into the response.
      * @return The modified HTTP response that should be sent to clients.
      */
    def send[HttpResponse](response: HttpResponse.Writable[HttpResponse], context: Context): HttpResponse

    /**
      * Signals that the entire response (headers and body) has been sent to the client.
      *
      * @param sentBytes Size of the entire HTTP response.
      */
    def doneSending(sentBytes: Long): Unit

  }


  def from(name: String, port: Int, component: String): HttpServer = {
    from(name, port, component, Kamon, Kamon)
  }

  def from(name: String, port: Int, component: String, configuration: Configuration, contextPropagation: ContextPropagation): HttpServer = {
    val defaultConfiguration = configuration.config().getConfig(DefaultHttpServerConfiguration)
    val configWithFallback = if(name == DefaultHttpServer) defaultConfiguration else {
      configuration.config().getConfig(HttpServerConfigurationPrefix + "." + name).withFallback(defaultConfiguration)
    }

    new HttpServer.Default(Settings.from(configWithFallback), contextPropagation, port, component)
  }

  val HttpServerConfigurationPrefix = "kamon.instrumentation.http-server"
  val DefaultHttpServer = "default"
  val DefaultHttpServerConfiguration = s"$HttpServerConfigurationPrefix.default"


  private class Default(settings: Settings, contextPropagation: ContextPropagation, port: Int, component: String) extends HttpServer {
    private val _log = LoggerFactory.getLogger(classOf[Default])
    private val _propagation = contextPropagation.httpPropagation(settings.propagationChannel)
      .getOrElse {
        _log.warn(s"Could not find HTTP propagation [${settings.propagationChannel}], falling back to the default HTTP propagation")
        contextPropagation.defaultHttpPropagation()
      }

    override def receive(request: HttpRequest): RequestHandler = {
      val incomingContext = if(settings.enableContextPropagation)
        _propagation.readContext(request)
        else Context.Empty

      val requestSpan = if(settings.enableTracing)
        buildServerSpan(incomingContext, request)
        else Span.Empty

      val handlerContext = if(requestSpan.nonEmpty())
        incomingContext.withKey(Span.ContextKey, requestSpan)
      else incomingContext

      // TODO: Handle HTTP Server Metrics


      new HttpServer.RequestHandler {
        override def context: Context =
          handlerContext

        override def span: Span =
          requestSpan

        override def doneReceiving(receivedBytes: Long): Unit = {}

        override def send[HttpResponse](response: HttpResponse.Writable[HttpResponse], context: Context): HttpResponse = {
          def addResponseTag(tag: String, value: String, mode: TagMode): Unit = mode match {
            case TagMode.Metric => span.tagMetric(tag, value)
            case TagMode.Span => span.tag(tag, value)
            case TagMode.Off =>
          }

          if(settings.enableContextPropagation) {
            _propagation.writeContext(context, response, Direction.Returning)
          }

          addResponseTag("http.status_code", response.statusCode.toString, settings.statusCodeTagMode)
          response.build()
        }

        override def doneSending(sentBytes: Long): Unit = {
          span.finish()
        }
      }
    }

    override def openConnection(): Unit = ???

    override def closeConnection(lifetime: Duration, handledRequests: Long): Unit = ???

    private def buildServerSpan(context: Context, request: HttpRequest): Span = {
      val span = Kamon.buildSpan(operationName(request))
        .withMetricTag("span.kind", "server")
        .withMetricTag("component", component)

      if(!settings.enableSpanMetrics)
        span.disableMetrics()


      for { traceIdTag <- settings.traceIDTag; customTraceID <- context.getTag(traceIdTag) } {
        val identifier = Kamon.identityProvider.traceIdGenerator().from(customTraceID)
        if(identifier != IdentityProvider.NoIdentifier)
          span.withTraceID(identifier)
      }

      def addRequestTag(tag: String, value: String, mode: TagMode): Unit = mode match {
        case TagMode.Metric => span.withMetricTag(tag, value)
        case TagMode.Span => span.withTag(tag, value)
        case TagMode.Off =>
      }

      addRequestTag("http.url", request.url, settings.urlTagMode)
      addRequestTag("http.method", request.method, settings.urlTagMode)
      settings.contextTags.foreach {
        case (tagName, mode) => context.getTag(tagName).foreach(tagValue => addRequestTag(tagName, tagValue, mode))
      }

      span.start()
    }

    private def operationName(request: HttpRequest): String = {
      val requestPath = request.path
      val customMapping = settings.operationMappings.find {
        case (pattern, _) => pattern.accept(requestPath)
      }.map(_._2)

      customMapping.getOrElse("http.request")
    }
  }


  case class Settings(
    enableContextPropagation: Boolean,
    propagationChannel: String,
    enableServerMetrics: Boolean,
    serverMetricsTags: Seq[String],
    enableTracing: Boolean,
    traceIDTag: Option[String],
    enableSpanMetrics: Boolean,
    urlTagMode: TagMode,
    methodTagMode: TagMode,
    statusCodeTagMode: TagMode,
    contextTags: Map[String, TagMode],
    unhandledOperationName: String,
    operationMappings: Map[GlobPathFilter, String]
  )

  object Settings {

    sealed trait TagMode
    object TagMode {
      case object Metric extends TagMode
      case object Span extends TagMode
      case object Off extends TagMode

      def from(value: String): TagMode = value.toLowerCase match {
        case "metric" => TagMode.Metric
        case "span" => TagMode.Span
        case _ => TagMode.Off
      }
    }

    def from(config: Config): Settings = {

      // Context propagation settings
      val enablePropagation = config.getBoolean("propagation.enabled")
      val propagationChannel = config.getString("propagation.channel")

      // HTTP Server metrics settings
      val enableServerMetrics = config.getBoolean("metrics.enabled")
      val serverMetricsTags = config.getStringList("metrics.tags").asScala

      // Tracing settings
      val enableTracing = config.getBoolean("tracing.enabled")
      val traceIdTag = Option(config.getString("tracing.trace-id-tag")).filterNot(_ == "none")
      val enableSpanMetrics = config.getBoolean("tracing.span-metrics")
      val urlTagMode = TagMode.from(config.getString("tracing.tags.url"))
      val methodTagMode = TagMode.from(config.getString("tracing.tags.method"))
      val statusCodeTagMode = TagMode.from(config.getString("tracing.tags.status-code"))
      val contextTags = config.getConfig("tracing.tags.from-context").pairs.map {
        case (tagName, mode) => (tagName, TagMode.from(mode))
      }

      val unhandledOperationName = config.getString("tracing.operations.unhandled")
      val operationMappings = config.getConfig("tracing.operations.mappings").pairs.map {
        case (pattern, operationName) => (new GlobPathFilter(pattern), operationName)
      }

      Settings(
        enablePropagation,
        propagationChannel,
        enableServerMetrics,
        serverMetricsTags,
        enableTracing,
        traceIdTag,
        enableSpanMetrics,
        urlTagMode,
        methodTagMode,
        statusCodeTagMode,
        contextTags,
        unhandledOperationName,
        operationMappings
      )
    }
  }
}
