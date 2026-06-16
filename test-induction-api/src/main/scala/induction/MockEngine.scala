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

/** A registered behavior, surfaced by the status endpoint. Carries the verbatim
  * WireMock `response` and the path/pattern flag so the UI can show and edit the
  * full configuration, not just the match.
  */
final case class Registration(
    profile: String,
    caller: String,
    name: String,
    baseUrl: String,
    method: String,
    path: String,
    pathIsPattern: Boolean,
    response: JsonNode,
    stubId: String
)

/** A recorded data-plane request and the response the sidecar served for it. */
final case class RequestLogEntry(
    id: String,
    loggedAt: String,
    method: String,
    url: String,
    profile: String,
    caller: String,
    matched: Boolean,
    status: Int,
    requestBody: String,
    responseBody: String
)

/** A register attempt collided with an already-registered mock's match. */
final class DuplicateMatchException(message: String) extends RuntimeException(message)

/** An update referenced a mock that is not registered. */
final class MockNotFoundException(message: String) extends RuntimeException(message)

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
final class MockEngine(val mockPort: Int, store: RequestStore = RequestStore.inMemory()):
  import MockEngine.Entry

  private val log      = LoggerFactory.getLogger(getClass)
  private val mapper   = new ObjectMapper()
  // key = "profile::caller" -> the behaviors registered for that pair.
  private val registry = new ConcurrentHashMap[String, java.util.Set[Entry]]()
  // The control plane shares this same port, served as a request filter.
  private val control  = new InductionControlFilter(this)
  private val server   = new WireMockServer(options().port(mockPort).extensions(control))

  // Every data-plane request reaching the mock engine is recorded (persisted to
  // the request store) and logged. A non-404 means a behavior fired; a 404 means
  // nothing matched. Only real induction traffic is recorded — identified by the
  // caller header — so control-plane calls, the UI, and stray browser hits
  // (favicon, "/", etc.) don't pollute the log.
  server.addMockServiceRequestListener { (request, response) =>
    if request.getHeader(InductionHeaders.Caller) != null
      && !request.getUrl.startsWith(InductionControlFilter.Prefix) then
      val profile = Option(request.getHeader(InductionHeaders.Profile)).getOrElse("-")
      val caller  = Option(request.getHeader(InductionHeaders.Caller)).getOrElse("-")
      val matched = response.getStatus != 404
      store.insert(RequestLogEntry(
        id           = java.util.UUID.randomUUID().toString,
        loggedAt     = java.time.Instant.now().toString,
        method       = request.getMethod.value,
        url          = request.getAbsoluteUrl,
        profile      = profile,
        caller       = caller,
        matched      = matched,
        status       = response.getStatus,
        requestBody  = Option(request.getBodyAsString).getOrElse(""),
        responseBody = Option(response.getBodyAsString).getOrElse("")
      ))
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

  /** Normalize a target into the (host, port, fullPath) actually matched by
    * WireMock: the scheme is ignored (only host/port/path are matched), the host
    * is lower-cased (hostnames are case-insensitive), the port falls back to the
    * scheme default when absent, and any base path on `baseUrl` is folded into
    * `path`. Inputs that differ only cosmetically (trailing slash, host case,
    * how the base-url/path split is chosen) normalize to the same triple.
    */
  private def normalizedMatch(baseUrl: String, path: String): (String, Int, String) =
    val uri    = URI.create(baseUrl)
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("http")
    val host   = Option(uri.getHost)
      .getOrElse(throw new IllegalArgumentException(s"'baseUrl' must include a host: $baseUrl"))
      .toLowerCase
    val port   = uri.getPort match
      case -1 => if scheme == "https" then 443 else 80
      case p  => p
    val basePath = Option(uri.getPath).filter(p => p.nonEmpty && p != "/").getOrElse("")
    (host, port, basePath + path)

  /** Identity of a mock within a (profile, caller): method + the *normalized*
    * target + path. Two behaviors with the same identity would match the same
    * request, so we forbid registering a duplicate (use [[update]] instead).
    * Keying off the normalized form means cosmetic differences can't sneak a
    * duplicate past the check.
    */
  private def matchKey(method: String, baseUrl: String, path: String, isPattern: Boolean): String =
    val (host, port, fullPath) = normalizedMatch(baseUrl, path)
    s"${method.toUpperCase}|$host:$port|${if isPattern then "~" else "="}$fullPath"
  private def specKey(b: BehaviorSpec): String = matchKey(b.method, b.baseUrl, b.path, b.pathIsPattern)
  private def entryKey(e: Entry): String       = matchKey(e.method, e.baseUrl, e.path, e.pathIsPattern)

  def start(): Unit =
    server.start()
    log.info("WireMock mock engine + control plane started on :{}", Integer.valueOf(mockPort))

  def stop(): Unit =
    server.stop()
    store.close()
    log.info("WireMock mock engine stopped")

  /** Register every behavior in a profile for (profile, caller). Each behavior is
    * expanded into a WireMock stub matching its target base URL, path, method and
    * the injected profile/caller headers. A behavior whose match already exists
    * for that (profile, caller) — or is duplicated within the request — is
    * rejected with [[DuplicateMatchException]] (change it via [[update]]).
    * Validation runs before anything is added, so a rejected request leaves the
    * registry untouched. Returns the created stub ids.
    */
  def register(profile: String, caller: String, behaviors: List[BehaviorSpec]): List[String] =
    val incoming = behaviors.map(b => (b, specKey(b)))
    incoming.map(_._2).groupBy(identity).collectFirst { case (mk, xs) if xs.sizeIs > 1 => mk }.foreach { mk =>
      throw DuplicateMatchException(s"duplicate match within request: $mk")
    }
    val existing = Option(registry.get(key(profile, caller)))
      .map(_.asScala.iterator.map(entryKey).toSet)
      .getOrElse(Set.empty)
    incoming.find { case (_, mk) => existing.contains(mk) }.foreach { case (b, _) =>
      throw DuplicateMatchException(
        s"a mock already exists for [profile=$profile, caller=$caller] ${b.method.toUpperCase} ${b.baseUrl}${b.path} — use update")
    }
    behaviors.map(addStub(profile, caller, _))

  /** Update existing behaviors in place. Each behavior is located by its match
    * within (profile, caller) and its response/name are replaced. A behavior
    * whose match is not registered is a [[MockNotFoundException]]. Validation
    * runs before anything changes. Returns the new stub ids.
    */
  def update(profile: String, caller: String, behaviors: List[BehaviorSpec]): List[String] =
    val set = Option(registry.get(key(profile, caller))).getOrElse(
      throw MockNotFoundException(s"no mocks registered for [profile=$profile, caller=$caller]"))
    val targets = behaviors.map { spec =>
      val mk = specKey(spec)
      val entry = set.asScala.find(e => entryKey(e) == mk).getOrElse(
        throw MockNotFoundException(
          s"no mock to update for [profile=$profile, caller=$caller] ${spec.method.toUpperCase} ${spec.baseUrl}${spec.path}"))
      (spec, entry)
    }
    targets.map { case (spec, entry) =>
      server.removeStubMapping(entry.stub)
      set.remove(entry)
      log.info("updating behavior [profile={}, caller={}] {} {}",
        profile, caller, spec.method.toUpperCase, spec.baseUrl + spec.path)
      addStub(profile, caller, spec)
    }

  /** Build a WireMock stub for one behavior, register it, and remember it. */
  private def addStub(profile: String, caller: String, spec: BehaviorSpec): String =
    val stub = StubMapping.buildFrom(mapper.writeValueAsString(buildMapping(profile, caller, spec)))
    server.addStubMapping(stub)
    registry
      .computeIfAbsent(key(profile, caller), _ => ConcurrentHashMap.newKeySet[Entry]())
      .add(Entry(spec.name, spec.baseUrl, spec.method.toUpperCase, spec.path, spec.pathIsPattern, spec.response, stub))
    log.info(
      "registered behavior [profile={}, caller={}] {} {} ({}) stubId={}",
      profile, caller, spec.method.toUpperCase, spec.baseUrl + spec.path, spec.name, stub.getId
    )
    stub.getId.toString

  /** Turn a [[BehaviorSpec]] into a WireMock stub-mapping JSON: the base URL
    * becomes host/port matchers (scheme is intentionally ignored so http/https
    * don't have to be distinguished), the path becomes urlPath/urlPathPattern,
    * and the induction headers are injected so the behavior only fires for the
    * right profile and caller.
    */
  private def buildMapping(profile: String, caller: String, spec: BehaviorSpec): ObjectNode =
    val (host, port, fullPath) = normalizedMatch(spec.baseUrl, spec.path)

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
        Registration(profile, caller, e.name, e.baseUrl, e.method, e.path, e.pathIsPattern, e.response, e.stub.getId.toString)
      }
    }

  /** Every recorded data-plane request, newest first, from the persistent store
    * (survives restarts). Control-plane (/__induction) calls are never recorded.
    */
  def serveEvents: List[RequestLogEntry] = store.all()

  /** Clear the recorded request log (keeps registered behaviors). */
  def clearRequests(): Unit =
    store.clear()
    server.resetRequests()
    log.info("cleared request log")

object MockEngine:
  /** A registered behavior plus the WireMock stub backing it (for removal). */
  private final case class Entry(name: String, baseUrl: String, method: String, path: String,
                                 pathIsPattern: Boolean, response: JsonNode, stub: StubMapping)
