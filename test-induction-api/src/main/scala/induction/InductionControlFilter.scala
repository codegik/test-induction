package induction

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.extension.requestfilter.{RequestFilterAction, StubRequestFilterV2}
import com.github.tomakehurst.wiremock.http.{Request, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** The control plane, folded into the mock engine's own HTTP port as a WireMock
  * request filter. This is what lets the sidecar expose a single port: requests
  * under the reserved [[InductionControlFilter.Prefix]] namespace are handled
  * here and short-circuited, while everything else falls through to normal stub
  * matching (the data plane).
  *
  * The namespace is `/__induction` — chosen to mirror WireMock's own reserved
  * `/__admin` so it can never shadow a stub a caller registers.
  *
  * Endpoints (all on the mock engine port):
  *   - POST   /__induction/register            { profile, caller, behaviors } (create-only; 409 on duplicate match)
  *   - PUT    /__induction/update              { profile, caller, behaviors } (change existing; 404 if absent)
  *   - DELETE /__induction/{profile}/{caller}
  *   - POST   /__induction/reset
  *   - GET    /__induction/status
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
    if !path.startsWith(Prefix) then RequestFilterAction.continueWith(request)
    else
      val method = request.getMethod.value.toUpperCase
      log.info("control {} {}", method, path)
      val response =
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
      RequestFilterAction.stopWith(response)

  private def route(method: String, rest: List[String], request: Request): ResponseDefinition =
    (method, rest) match
      case ("POST", "register" :: Nil) => register(request)
      case ("PUT", "update" :: Nil)    => update(request)
      case ("POST", "reset" :: Nil) =>
        engine.reset(); json(200, """{"reset":true}""")
      case ("GET", "status" :: Nil) => status()
      case ("GET", "health" :: Nil) => json(200, """{"status":"UP"}""")
      case ("DELETE", profile :: caller :: Nil) =>
        json(200, s"""{"removed":${engine.remove(profile, caller)}}""")
      case _ => notFound(method, s"$Prefix/${rest.mkString("/")}")

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

object InductionControlFilter:
  /** Reserved control-plane path prefix on the mock engine port. */
  val Prefix = "/__induction"
