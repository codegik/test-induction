package induction

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.extension.requestfilter.{RequestFilterAction, StubRequestFilterV2}
import com.github.tomakehurst.wiremock.http.{Request, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** The control plane (and UI host), folded into the mock engine's own HTTP port
  * as a WireMock request filter. This is what lets the sidecar expose a single
  * port. Routing for an incoming request:
  *
  *   1. path under `/__induction/`         -> control plane (API), handled here
  *   2. else, has the induction caller header -> data plane (real proxied mock
  *      call), passed through to WireMock stub matching
  *   3. else                                -> the bundled web UI (served here)
  *
  * So the UI lives at the root (`/`, `/assets/...`) and the API under
  * `/__induction/`. The caller header is what distinguishes a browser hit on
  * `/` from a proxied data-plane request to some external service's root path —
  * real induction traffic always carries it (the stubs require it).
  *
  * The `/__induction` namespace mirrors WireMock's own reserved `/__admin`.
  *
  * Endpoints (all on the mock engine port):
  *   - GET    /                                the bundled web UI (SPA + assets)
  *   - POST   /__induction/register            { profile, caller, behaviors } (create-only; 409 on duplicate match)
  *   - PUT    /__induction/update              { profile, caller, behaviors } (change existing; 404 if absent)
  *   - DELETE /__induction/{profile}/{caller}
  *   - POST   /__induction/reset
  *   - GET    /__induction/status
  *   - GET    /__induction/requests           (recorded data-plane requests + responses)
  *   - DELETE /__induction/requests           (clear the request log)
  *   - GET    /__induction/health
  */
final class InductionControlFilter(engine: MockEngine) extends StubRequestFilterV2:
  import InductionControlFilter.Prefix

  private val log    = LoggerFactory.getLogger(getClass)
  private val mapper = new ObjectMapper()

  override def getName: String       = "induction-control"
  override def applyToStubs: Boolean = true
  override def applyToAdmin: Boolean = false

  override def filter(request: Request, serveEvent: ServeEvent): RequestFilterAction =
    val path = request.getUrl.takeWhile(_ != '?')
    if path == Prefix || path.startsWith(Prefix + "/") then
      RequestFilterAction.stopWith(control(request, path))
    else if request.getHeader(InductionHeaders.Caller) != null then
      RequestFilterAction.continueWith(request) // data plane -> WireMock stub matching
    else
      RequestFilterAction.stopWith(serveUi(path)) // the bundled UI

  private def control(request: Request, path: String): ResponseDefinition =
    val method = request.getMethod.value.toUpperCase
    // The UI polls health/requests on a timer; log those at DEBUG so they don't
    // spam the log, but keep mutations (register/update/delete/reset) at INFO.
    if isPoll(method, path) then log.debug("control {} {}", method, path)
    else log.info("control {} {}", method, path)
    try
      path.split("/").filter(_.nonEmpty).toList match
        case "__induction" :: rest => route(method, rest, request)
        case _                      => notFound(method, path)
    catch
      case e: DuplicateMatchException =>
        log.warn("control {} {} conflict: {}", method, path, e.getMessage)
        json(409, errJson(e.getMessage))
      case e: MockNotFoundException =>
        log.warn("control {} {} not found: {}", method, path, e.getMessage)
        json(404, errJson(e.getMessage))
      case e: Throwable =>
        log.warn("control {} {} failed: {}", method, path, e.getMessage)
        json(400, errJson(e.getMessage))

  private def route(method: String, rest: List[String], request: Request): ResponseDefinition =
    (method, rest) match
      case ("POST", "register" :: Nil) => register(request)
      case ("PUT", "update" :: Nil)    => update(request)
      case ("POST", "reset" :: Nil) =>
        engine.reset(); json(200, """{"reset":true}""")
      case ("GET", "status" :: Nil)   => status()
      case ("GET", "requests" :: Nil) => requests()
      case ("DELETE", "requests" :: Nil) =>
        engine.clearRequests(); json(200, """{"cleared":true}""")
      case ("GET", "health" :: Nil) => json(200, """{"status":"UP"}""")
      case ("DELETE", profile :: caller :: Nil) =>
        json(200, s"""{"removed":${engine.remove(profile, caller)}}""")
      case _ => notFound(method, s"$Prefix/${rest.mkString("/")}")

  /** Routine read-only polls the UI issues on a timer (not worth logging at INFO). */
  private def isPoll(method: String, path: String): Boolean =
    method == "GET" && (path == s"$Prefix/health" || path == s"$Prefix/requests")

  private def notFound(method: String, path: String): ResponseDefinition =
    log.warn("no route for {} {}", method, path)
    json(404, errJson(s"no route for $method $path"))

  private def register(request: Request): ResponseDefinition =
    val (profile, caller, behaviors) = parseEnvelope(request)
    respondWithIds(201, profile, caller, engine.register(profile, caller, behaviors))

  private def update(request: Request): ResponseDefinition =
    val (profile, caller, behaviors) = parseEnvelope(request)
    respondWithIds(200, profile, caller, engine.update(profile, caller, behaviors))

  /** Parse the shared `{ profile, caller, behaviors[] }` envelope. */
  private def parseEnvelope(request: Request): (String, String, List[BehaviorSpec]) =
    val root      = mapper.readTree(request.getBodyAsString)
    val profile   = requireText(root, "profile")
    val caller    = requireText(root, "caller")
    val behaviors = root.get("behaviors") match
      case a: ArrayNode if !a.isEmpty => a.asScala.toList.map(behaviorSpec)
      case _ => throw new IllegalArgumentException("'behaviors' (a non-empty array) is required")
    (profile, caller, behaviors)

  private def respondWithIds(status: Int, profile: String, caller: String, ids: List[String]): ResponseDefinition =
    val resp  = mapper.createObjectNode()
    resp.put("profile", profile)
    resp.put("caller", caller)
    val stubs = resp.putArray("stubIds")
    ids.foreach(stubs.add)
    json(status, mapper.writeValueAsString(resp))

  /** Parse one element of `behaviors`: a `match` (baseUrl/method/path|pathPattern)
    * plus a verbatim WireMock `response`.
    */
  private def behaviorSpec(node: JsonNode): BehaviorSpec =
    val m = node.get("match") match
      case o: ObjectNode => o
      case _ => throw new IllegalArgumentException("each behavior needs a 'match' object")
    val response = node.get("response") match
      case r: JsonNode if r != null && !r.isNull => r
      case _ => throw new IllegalArgumentException("each behavior needs a 'response' object")
    val (path, isPattern) = m.get("pathPattern") match
      case p: JsonNode if p != null && !p.isNull && !p.asText.isBlank => (p.asText, true)
      case _                                                          => (requireText(m, "path"), false)
    BehaviorSpec(
      name          = Option(node.get("name")).map(_.asText).filter(!_.isBlank).getOrElse(s"${requireText(m, "method")} ${requireText(m, "baseUrl")}"),
      baseUrl       = requireText(m, "baseUrl"),
      method        = requireText(m, "method"),
      path          = path,
      pathIsPattern = isPattern,
      response      = response
    )

  private def status(): ResponseDefinition =
    // Group flat registrations by (profile, caller) and nest their behaviors.
    val arr = mapper.createArrayNode()
    engine.status.groupBy(r => (r.profile, r.caller)).foreach { case ((profile, caller), regs) =>
      val o = arr.addObject()
      o.put("profile", profile)
      o.put("caller", caller)
      val behaviors = o.putArray("behaviors")
      regs.foreach { r =>
        val b = behaviors.addObject()
        b.put("name", r.name)
        b.put("baseUrl", r.baseUrl)
        b.put("method", r.method)
        if r.pathIsPattern then b.put("pathPattern", r.path) else b.put("path", r.path)
        b.set[ObjectNode]("response", r.response)
        b.put("stubId", r.stubId)
      }
    }
    json(200, mapper.writeValueAsString(arr))

  private def requests(): ResponseDefinition =
    val arr = mapper.createArrayNode()
    engine.serveEvents.foreach { r =>
      val o = arr.addObject()
      o.put("id", r.id)
      o.put("loggedAt", r.loggedAt)
      o.put("method", r.method)
      o.put("url", r.url)
      o.put("profile", r.profile)
      o.put("caller", r.caller)
      o.put("matched", r.matched)
      o.put("status", r.status)
      o.put("requestBody", r.requestBody)
      o.put("responseBody", r.responseBody)
    }
    json(200, mapper.writeValueAsString(arr))

  private def requireText(node: JsonNode, field: String): String =
    val v = node.get(field)
    if v == null || v.isNull || v.asText.isBlank then
      throw new IllegalArgumentException(s"'$field' is required")
    v.asText

  private def errJson(msg: String): String =
    mapper.writeValueAsString(mapper.createObjectNode().put("error", Option(msg).getOrElse("error")))

  private def json(status: Int, body: String): ResponseDefinition =
    ResponseDefinitionBuilder
      .responseDefinition()
      .withStatus(status)
      .withHeader("Content-Type", "application/json")
      .withBody(body)
      .build()

  /** Serve the bundled React build (classpath `/web`) for a root-relative request
    * path. `/` (and any extension-less route) serves index.html (SPA); existing
    * files are served as-is; a missing file (with an extension) is a 404.
    */
  private def serveUi(path: String): ResponseDefinition =
    val rel0 = path.stripPrefix("/")
    val rel  = if rel0.isEmpty then "index.html" else rel0
    Option(getClass.getResourceAsStream("/web/" + rel)) match
      case Some(in) =>
        val bytes = try in.readAllBytes() finally in.close()
        ResponseDefinitionBuilder
          .responseDefinition()
          .withStatus(200)
          .withHeader("Content-Type", contentType(rel))
          .withBody(bytes)
          .build()
      case None =>
        if rel.contains(".") then notFound("GET", "/" + rel) // a real missing asset
        else serveUi("/")                                    // SPA route -> index.html

  private def contentType(path: String): String =
    if path.endsWith(".html") then "text/html; charset=utf-8"
    else if path.endsWith(".js") then "text/javascript; charset=utf-8"
    else if path.endsWith(".css") then "text/css; charset=utf-8"
    else if path.endsWith(".json") then "application/json"
    else if path.endsWith(".svg") then "image/svg+xml"
    else if path.endsWith(".ico") then "image/x-icon"
    else if path.endsWith(".woff2") then "font/woff2"
    else if path.endsWith(".png") then "image/png"
    else "application/octet-stream"

object InductionControlFilter:
  /** Reserved control-plane path prefix on the mock engine port. */
  val Prefix = "/__induction"
