package induction

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.slf4j.LoggerFactory

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** One behavior to induce for a single external endpoint, as parsed from the
  * control API. `baseUrl` is the full base URL of the target service
  * (scheme + host + port + optional base path); `path` is the request path
  * (combined with any base path from `baseUrl`). `response` is a verbatim
  * WireMock response definition.
  */
final case class BehaviorSpec(
    name: String,
    baseUrl: String,
    method: String,
    path: String,
    pathIsPattern: Boolean,
    response: JsonNode
)

/** A registered behavior, surfaced by the status endpoint. */
final case class Registration(
    profile: String,
    caller: String,
    name: String,
    baseUrl: String,
    method: String,
    path: String,
    stubId: String
)

/** Wraps an embedded WireMock server. We never reimplement WireMock: each
  * behavior is turned into WireMock's own stub-mapping JSON. A behavior is keyed
  * by the **full target base URL** — the sidecar matches on the request's
  * scheme/host/port/path — plus the induction profile/caller headers, so the
  * same engine serves different behaviors per external service, per profile, and
  * per caller. Unmatched requests get WireMock's default 404 (the sidecar never
  * forwards to a real service).
  *
  * State lives in a [[ConcurrentHashMap]] so behaviors can be toggled at runtime
  * from multiple control threads without a restart.
  */
final class MockEngine(val mockPort: Int):
  import MockEngine.Entry

  private val log      = LoggerFactory.getLogger(getClass)
  private val mapper   = new ObjectMapper()
  // key = "profile::caller" -> the behaviors registered for that pair.
  private val registry = new ConcurrentHashMap[String, java.util.Set[Entry]]()
  // The control plane shares this same port, served as a request filter.
  private val control  = new InductionControlFilter(this)
  private val server   = new WireMockServer(options().port(mockPort).extensions(control))

  // Every data-plane request reaching the mock engine is logged with the target
  // URL and induction headers. This is the "what is being used" signal: a non-404
  // means a behavior fired; a 404 means nothing matched for that profile/target.
  // Control-plane (/__induction) calls are skipped here.
  server.addMockServiceRequestListener { (request, response) =>
    if !request.getUrl.startsWith(InductionControlFilter.Prefix) then
      val profile = Option(request.getHeader(InductionHeaders.Profile)).getOrElse("-")
      val caller  = Option(request.getHeader(InductionHeaders.Caller)).getOrElse("-")
      val matched = response.getStatus != 404
      log.info(
        "{} {} {} -> {} [profile={}, caller={}]",
        if matched then "induced" else "no-match",
        request.getMethod,
        request.getAbsoluteUrl,
        Integer.valueOf(response.getStatus),
        profile,
        caller
      )
  }

  private def key(profile: String, caller: String): String = s"$profile::$caller"

  def start(): Unit =
    server.start()
    log.info("WireMock mock engine + control plane started on :{}", Integer.valueOf(mockPort))

  def stop(): Unit =
    server.stop()
    log.info("WireMock mock engine stopped")

  /** Register every behavior in a profile for (profile, caller). Each behavior is
    * expanded into a WireMock stub matching its target base URL, path, method and
    * the injected profile/caller headers. Returns the created stub ids.
    */
  def register(profile: String, caller: String, behaviors: List[BehaviorSpec]): List[String] =
    behaviors.map { spec =>
      val stub = StubMapping.buildFrom(mapper.writeValueAsString(buildMapping(profile, caller, spec)))
      server.addStubMapping(stub)
      registry
        .computeIfAbsent(key(profile, caller), _ => ConcurrentHashMap.newKeySet[Entry]())
        .add(Entry(spec.name, spec.baseUrl, spec.method.toUpperCase, spec.path, stub))
      log.info(
        "registered behavior [profile={}, caller={}] {} {} ({}) stubId={}",
        profile, caller, spec.method.toUpperCase, spec.baseUrl + spec.path, spec.name, stub.getId
      )
      stub.getId.toString
    }

  /** Turn a [[BehaviorSpec]] into a WireMock stub-mapping JSON: the base URL
    * becomes host/port matchers (scheme is intentionally ignored so http/https
    * don't have to be distinguished), the path becomes urlPath/urlPathPattern,
    * and the induction headers are injected so the behavior only fires for the
    * right profile and caller.
    */
  private def buildMapping(profile: String, caller: String, spec: BehaviorSpec): ObjectNode =
    val uri      = URI.create(spec.baseUrl)
    val scheme   = Option(uri.getScheme).map(_.toLowerCase).getOrElse("http")
    val host     = Option(uri.getHost).getOrElse(
      throw new IllegalArgumentException(s"'baseUrl' must include a host: ${spec.baseUrl}")
    )
    // Port is still matched; when absent we fall back to the scheme's default.
    val port     = uri.getPort match
      case -1 => if scheme == "https" then 443 else 80
      case p  => p
    val basePath = Option(uri.getPath).filter(p => p.nonEmpty && p != "/").getOrElse("")
    val fullPath = basePath + spec.path

    val mapping = mapper.createObjectNode()
    val request = mapping.putObject("request")
    request.put("method", spec.method.toUpperCase)
    request.putObject("host").put("equalTo", host)
    request.put("port", port)
    request.put(if spec.pathIsPattern then "urlPathPattern" else "urlPath", fullPath)
    val headers = request.putObject("headers")
    headers.putObject(InductionHeaders.Profile).put("equalTo", profile)
    headers.putObject(InductionHeaders.Caller).put("equalTo", caller)
    mapping.set[ObjectNode]("response", spec.response)
    mapping

  /** Remove every behavior registered for (profile, caller). Returns how many. */
  def remove(profile: String, caller: String): Int =
    Option(registry.remove(key(profile, caller))) match
      case Some(entries) =>
        entries.asScala.foreach(e => server.removeStubMapping(e.stub))
        log.info("removed {} behavior(s) [profile={}, caller={}]", Integer.valueOf(entries.size), profile, caller)
        entries.size
      case None =>
        log.info("remove found nothing [profile={}, caller={}]", profile, caller)
        0

  /** Drop all registered behaviors. */
  def reset(): Unit =
    val cleared = registry.asScala.values.map(_.size).sum
    server.resetAll()
    registry.clear()
    log.info("reset: cleared {} behavior(s)", Integer.valueOf(cleared))

  /** A flat view of everything currently registered. */
  def status: List[Registration] =
    registry.asScala.toList.flatMap { case (k, entries) =>
      val parts   = k.split("::", 2)
      val profile = parts(0)
      val caller  = if parts.length > 1 then parts(1) else ""
      entries.asScala.toList.map { e =>
        Registration(profile, caller, e.name, e.baseUrl, e.method, e.path, e.stub.getId.toString)
      }
    }

object MockEngine:
  /** A registered behavior plus the WireMock stub backing it (for removal). */
  private final case class Entry(name: String, baseUrl: String, method: String, path: String, stub: StubMapping)
