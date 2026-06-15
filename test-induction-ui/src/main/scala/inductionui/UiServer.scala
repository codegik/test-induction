package inductionui

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.slf4j.LoggerFactory

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** The UI server: static assets under `/`, and a reverse proxy under `/api`.
  *
  * `/api/<rest>` is forwarded verbatim to `<apiBase>/<rest>`, e.g. the page calls
  * `GET /api/__induction/status` and this server forwards it to the API's
  * `GET /__induction/status`. Method, body and content-type are preserved.
  */
final class UiServer(port: Int, apiBase: String):
  private val log    = LoggerFactory.getLogger(getClass)
  private val http   = HttpServer.create(new InetSocketAddress(port), 0)
  private val client = HttpClient.newHttpClient()

  http.setExecutor(Executors.newFixedThreadPool(8))
  http.createContext("/api", (ex: HttpExchange) => proxy(ex))
  http.createContext("/", (ex: HttpExchange) => static(ex))

  def start(): Unit = http.start()
  def stop(): Unit  = http.stop(0)

  // --- reverse proxy -----------------------------------------------------

  private def proxy(ex: HttpExchange): Unit =
    try
      val uri    = ex.getRequestURI
      val path   = uri.getPath.stripPrefix("/api")
      val query  = Option(uri.getRawQuery).map("?" + _).getOrElse("")
      val target = URI.create(apiBase + path + query)
      val method = ex.getRequestMethod.toUpperCase
      val body   = ex.getRequestBody.readAllBytes()

      val builder = HttpRequest.newBuilder(target)
      val publisher =
        if body.isEmpty then HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofByteArray(body)
      builder.method(method, publisher)
      Option(ex.getRequestHeaders.getFirst("Content-Type")).foreach(builder.header("Content-Type", _))

      val resp  = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
      val out   = resp.body
      val ct    = resp.headers.firstValue("Content-Type").orElse("application/json")
      log.info("proxy {} {} -> {}", method, path, Integer.valueOf(resp.statusCode))
      respond(ex, resp.statusCode, ct, out)
    catch
      case e: Throwable =>
        log.warn("proxy error: {}", e.getMessage)
        respond(ex, 502, "application/json",
          s"""{"error":"proxy to API failed: ${Option(e.getMessage).getOrElse("error")}"}""".getBytes(StandardCharsets.UTF_8))
    finally ex.close()

  // --- static assets -----------------------------------------------------

  private def static(ex: HttpExchange): Unit =
    try
      val p        = ex.getRequestURI.getPath
      val resource = if p == "/" || p.isEmpty then "/web/index.html" else "/web" + p
      Option(getClass.getResourceAsStream(resource)) match
        case Some(stream) =>
          val bytes = try stream.readAllBytes() finally stream.close()
          respond(ex, 200, contentType(resource), bytes)
        case None =>
          respond(ex, 404, "text/plain; charset=utf-8", s"not found: $p".getBytes(StandardCharsets.UTF_8))
    catch
      case e: Throwable =>
        respond(ex, 500, "text/plain; charset=utf-8", Option(e.getMessage).getOrElse("error").getBytes(StandardCharsets.UTF_8))
    finally ex.close()

  private def contentType(path: String): String =
    if path.endsWith(".html") then "text/html; charset=utf-8"
    else if path.endsWith(".js") then "application/javascript; charset=utf-8"
    else if path.endsWith(".css") then "text/css; charset=utf-8"
    else if path.endsWith(".json") then "application/json"
    else if path.endsWith(".svg") then "image/svg+xml"
    else "application/octet-stream"

  private def respond(ex: HttpExchange, code: Int, ct: String, body: Array[Byte]): Unit =
    ex.getResponseHeaders.add("Content-Type", ct)
    ex.sendResponseHeaders(code, if body.isEmpty then -1 else body.length.toLong)
    if body.nonEmpty then
      val os = ex.getResponseBody
      try os.write(body)
      finally os.close()
