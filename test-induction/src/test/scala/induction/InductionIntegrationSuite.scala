package induction

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import scala.jdk.CollectionConverters.*

/** Black-box integration tests: we start the real sidecar (mock engine + control
  * plane, on one port) and drive it the way a caller would — over HTTP. No
  * internal classes are poked directly, so these tests exercise routing,
  * stub-mapping injection, the induction-header matchers, the single-port control
  * namespace, and the observability logging end to end.
  */
class InductionIntegrationSuite extends munit.FunSuite:

  private var engine: MockEngine = null
  private var port: Int          = 0
  private val client             = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    port = freePort()
    engine = new MockEngine(port)
    engine.start()

  override def afterAll(): Unit =
    if engine != null then engine.stop()

  // Each test starts from a clean registry.
  override def beforeEach(context: BeforeEach): Unit =
    post(s"$controlBase/__induction/reset", "")

  // --- the cases ---------------------------------------------------------

  test("health endpoint reports UP") {
    val (code, body) = get(s"$controlBase/__induction/health")
    assertEquals(code, 200)
    assert(body.contains("\"status\":\"UP\""), body)
  }

  test("register returns 201 with a stub id and status lists it") {
    val (code, body) = register("happy", 200, """{"status":"CONFIRMED"}""")
    assertEquals(code, 201)
    assert(body.contains("\"stubId\""), body)

    val (sc, sb) = get(s"$controlBase/__induction/status")
    assertEquals(sc, 200)
    assert(sb.contains("\"profile\":\"happy\""), sb)
    assert(sb.contains("\"caller\":\"payment-service\""), sb)
  }

  test("a request carrying the induction headers gets the induced response") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    val (code, body) = post(s"$mockBase/payments", "{}", inductionHeaders("happy"))
    assertEquals(code, 200)
    assert(body.contains("CONFIRMED"), body)
  }

  test("a different status code is served per profile") {
    register("payment-500", 500, "\"boom\"")
    val (code, _) = post(s"$mockBase/payments", "{}", inductionHeaders("payment-500"))
    assertEquals(code, 500)
  }

  test("zero impact: a request without induction headers matches nothing (404)") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = post(s"$mockBase/payments", "{}")
    assertEquals(code, 404)
  }

  test("wrong profile does not match a registered behavior") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = post(s"$mockBase/payments", "{}", inductionHeaders("nope"))
    assertEquals(code, 404)
  }

  test("delete removes a behavior and reports how many") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    val (dc, db) = delete(s"$controlBase/__induction/happy/payment-service")
    assertEquals(dc, 200)
    assert(db.contains("\"removed\":1"), db)

    val (code, _) = post(s"$mockBase/payments", "{}", inductionHeaders("happy"))
    assertEquals(code, 404)
  }

  test("reset clears every behavior") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    register("payment-500", 500, "\"boom\"")

    val (rc, rb) = post(s"$controlBase/__induction/reset", "")
    assertEquals(rc, 200)
    assert(rb.contains("\"reset\":true"), rb)

    val (sc, sb) = get(s"$controlBase/__induction/status")
    assertEquals(sb, "[]")
    assertEquals(sc, 200)
  }

  test("register without a profile is a 400") {
    val body = """{"caller":"payment-service","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"status":200}}}"""
    val (code, resp) = post(s"$controlBase/__induction/register", body)
    assertEquals(code, 400)
    assert(resp.contains("required"), resp)
  }

  test("register without a mapping is a 400") {
    val body = """{"profile":"happy","caller":"payment-service"}"""
    val (code, resp) = post(s"$controlBase/__induction/register", body)
    assertEquals(code, 400)
    assert(resp.contains("required"), resp)
  }

  test("an unknown route is a 404") {
    val (code, resp) = get(s"$controlBase/__induction/unknown")
    assertEquals(code, 404)
    assert(resp.contains("no route"), resp)
  }

  test("observability: the mock engine logs induced vs passthrough") {
    register("happy", 200, """{"status":"CONFIRMED"}""")
    val logs = capturingLogs("induction.MockEngine") {
      post(s"$mockBase/payments", "{}", inductionHeaders("happy")) // matches -> induced
      post(s"$mockBase/payments", "{}")                            // no headers -> passthrough
    }
    assert(
      logs.exists(_.startsWith("induced POST /payments -> 200 [profile=happy, caller=payment-service]")),
      logs.mkString("\n")
    )
    assert(
      logs.exists(_.startsWith("passthrough POST /payments -> 404")),
      logs.mkString("\n")
    )
  }

  test("observability: the control plane logs registrations") {
    val logs = capturingLogs("induction.InductionControlFilter") {
      register("happy", 200, """{"status":"CONFIRMED"}""")
    }
    assert(logs.exists(_ == "control POST /__induction/register"), logs.mkString("\n"))
  }

  // --- helpers -----------------------------------------------------------

  // Same port for both: the control plane is multiplexed onto the mock engine.
  private def controlBase = s"http://localhost:$port"
  private def mockBase    = s"http://localhost:$port"

  private def inductionHeaders(profile: String): Map[String, String] =
    Map(InductionHeaders.Profile -> profile, InductionHeaders.Caller -> "payment-service")

  /** Register a POST /payments stub returning `status` with `jsonBody`. */
  private def register(profile: String, status: Int, jsonBody: String): (Int, String) =
    val body =
      s"""{"profile":"$profile","caller":"payment-service","mapping":{
         |  "request":{"method":"POST","urlPath":"/payments"},
         |  "response":{"status":$status,"headers":{"Content-Type":"application/json"},"jsonBody":$jsonBody}
         |}}""".stripMargin
    post(s"$controlBase/__induction/register", body)

  private def get(url: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).GET().build())

  private def delete(url: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).DELETE().build())

  private def post(url: String, body: String, headers: Map[String, String] = Map.empty): (Int, String) =
    val builder = HttpRequest.newBuilder(URI.create(url)).POST(BodyPublishers.ofString(body))
    headers.foreach((k, v) => builder.header(k, v))
    send(builder.build())

  private def send(req: HttpRequest): (Int, String) =
    val resp = client.send(req, BodyHandlers.ofString())
    (resp.statusCode, resp.body)

  /** Attach a logback list appender to `loggerName`, run `body`, and return the
    * formatted messages it emitted. This is how the observability tests assert
    * on actual log output rather than eyeballing the console.
    */
  private def capturingLogs(loggerName: String)(body: => Unit): List[String] =
    val logger   = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try body
    finally logger.detachAppender(appender)
    appender.list.asScala.toList.map(_.getFormattedMessage)

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()
