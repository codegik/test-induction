package induction

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.extension.requestfilter.{RequestFilterAction, StubRequestFilterV2}
import com.github.tomakehurst.wiremock.http.{Request, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.slf4j.LoggerFactory

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
  *   - POST   /__induction/register            { profile, caller, mapping }
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
          case e: Throwable =>
            log.warn("control {} {} failed: {}", method, path, e.getMessage)
            json(400, errJson(e.getMessage))
      RequestFilterAction.stopWith(response)

  private def route(method: String, rest: List[String], request: Request): ResponseDefinition =
    (method, rest) match
      case ("POST", "register" :: Nil) => register(request)
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
    val root    = mapper.readTree(request.getBodyAsString)
    val profile = requireText(root, "profile")
    val caller  = requireText(root, "caller")
    val mapping = root.get("mapping") match
      case o: ObjectNode => o
      case _ => throw new IllegalArgumentException("'mapping' (a WireMock stub mapping) is required")
    val id = engine.register(profile, caller, mapping)
    json(201, s"""{"profile":"$profile","caller":"$caller","stubId":"$id"}""")

  private def status(): ResponseDefinition =
    val arr = mapper.createArrayNode()
    engine.status.foreach { r =>
      val o = arr.addObject()
      o.put("profile", r.profile)
      o.put("caller", r.caller)
      o.put("stubId", r.stubId)
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
