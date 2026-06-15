package induction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** A single registered behavior, surfaced by the status endpoint. */
final case class Registration(profile: String, caller: String, stubId: String)

/** Wraps an embedded WireMock server. We never reimplement WireMock: callers
  * provide behaviors in WireMock's own stub-mapping JSON format and we simply
  * augment each mapping with header matchers for the induction headers so that
  * the behavior only fires when the matching profile/caller headers are present.
  *
  * State lives in a [[ConcurrentHashMap]] so behaviors can be toggled at runtime
  * from multiple control-API threads without a restart.
  */
final class MockEngine(val mockPort: Int):
  private val log      = LoggerFactory.getLogger(getClass)
  private val mapper   = new ObjectMapper()
  // key = "profile::caller" -> the stub mappings registered for that pair.
  private val registry = new ConcurrentHashMap[String, java.util.Set[StubMapping]]()
  // The control plane shares this same port, served as a request filter.
  private val control  = new InductionControlFilter(this)
  private val server   = new WireMockServer(options().port(mockPort).extensions(control))

  // Every data-plane request reaching the mock engine is logged with the
  // induction headers and the resulting status. This is the "what is being used"
  // signal: a non-404 means an induced behavior fired; a 404 means nothing
  // matched (zero impact). Control-plane (/__induction) calls are skipped here.
  server.addMockServiceRequestListener { (request, response) =>
    if !request.getUrl.startsWith(InductionControlFilter.Prefix) then
      val profile = Option(request.getHeader(InductionHeaders.Profile)).getOrElse("-")
      val caller  = Option(request.getHeader(InductionHeaders.Caller)).getOrElse("-")
      val matched = response.getStatus != 404
      log.info(
        "{} {} {} -> {} [profile={}, caller={}]",
        if matched then "induced" else "passthrough",
        request.getMethod,
        request.getUrl,
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

  /** Register a behavior for (profile, caller). `mapping` is a WireMock stub
    * mapping; we inject the induction header matchers into its request pattern,
    * register it with WireMock, and remember it so it can be removed later.
    * Returns the created stub id.
    */
  def register(profile: String, caller: String, mapping: ObjectNode): String =
    val request = mapping.get("request") match
      case o: ObjectNode => o
      case _             => mapping.putObject("request")
    val headers = request.get("headers") match
      case o: ObjectNode => o
      case _             => request.putObject("headers")

    headers.putObject(InductionHeaders.Profile).put("equalTo", profile)
    headers.putObject(InductionHeaders.Caller).put("equalTo", caller)

    val stub = StubMapping.buildFrom(mapper.writeValueAsString(mapping))
    server.addStubMapping(stub)
    registry
      .computeIfAbsent(key(profile, caller), _ => ConcurrentHashMap.newKeySet[StubMapping]())
      .add(stub)
    log.info("registered behavior [profile={}, caller={}] stubId={}", profile, caller, stub.getId)
    stub.getId.toString

  /** Remove every behavior registered for (profile, caller). Returns how many. */
  def remove(profile: String, caller: String): Int =
    Option(registry.remove(key(profile, caller))) match
      case Some(stubs) =>
        stubs.asScala.foreach(server.removeStubMapping)
        log.info("removed {} behavior(s) [profile={}, caller={}]", Integer.valueOf(stubs.size), profile, caller)
        stubs.size
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
    registry.asScala.toList.flatMap { case (k, stubs) =>
      val parts   = k.split("::", 2)
      val profile = parts(0)
      val caller  = if parts.length > 1 then parts(1) else ""
      stubs.asScala.toList.map(s => Registration(profile, caller, s.getId.toString))
    }
