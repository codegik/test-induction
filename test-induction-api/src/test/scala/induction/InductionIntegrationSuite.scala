package induction

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import scala.jdk.CollectionConverters.*

/** Black-box integration tests for the single-port sidecar.
  *
  *   - Control-plane calls (`/__induction/...`) go directly to the sidecar.
  *   - Data-plane calls are issued the way the sample-app issues them: through
  *     the sidecar as an HTTP proxy, so the absolute target URL is on the wire and
  *     the sidecar matches on its full base-url + path (never on a rewritten URL).
  *
  * Nothing internal is poked directly; these exercise registration, baseUrl/path
  * matching, the terminal 404 (no forwarding), and the observability logging.
  */
class InductionIntegrationSuite extends munit.FunSuite:

  private var engine: MockEngine = null
  private var port: Int          = 0
  private val direct             = HttpClient.newHttpClient()  // control plane
  private var proxied: HttpClient = null                       // data plane (sidecar as proxy)

  override def beforeAll(): Unit =
    port = freePort()
    engine = new MockEngine(port)
    engine.start()
    proxied = HttpClient.newBuilder()
      .proxy(ProxySelector.of(new InetSocketAddress("localhost", port)))
      .build()

  override def afterAll(): Unit =
    if engine != null then engine.stop()

  override def beforeEach(context: BeforeEach): Unit =
    post(s"$controlBase/__induction/reset", "")

  // --- control plane -----------------------------------------------------

  test("health endpoint reports UP") {
    val (code, body) = get(s"$controlBase/__induction/health")
    assertEquals(code, 200)
    assert(body.contains("\"status\":\"UP\""), body)
  }

  test("register returns 201 with stub ids and status nests behaviors by base-url") {
    val (code, body) = register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    assertEquals(code, 201)
    assert(body.contains("\"stubIds\""), body)

    val (sc, sb) = get(s"$controlBase/__induction/status")
    assertEquals(sc, 200)
    assert(sb.contains("\"profile\":\"happy\""), sb)
    assert(sb.contains("\"baseUrl\":\"http://api.payments.com\""), sb)
    assert(sb.contains("\"path\":\"/v1/charges\""), sb)
  }

  // --- data plane (matching by full base-url) ----------------------------

  test("a proxied request to the registered base-url + path gets the induced response") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, body) = call("http://api.payments.com/v1/charges", "happy")
    assertEquals(code, 200)
    assert(body.contains("CONFIRMED"), body)
  }

  test("per-profile status code") {
    register("payment-500", "http://api.payments.com", "POST", "/v1/charges", 500, """{"x":1}""")
    val (code, _) = call("http://api.payments.com/v1/charges", "payment-500")
    assertEquals(code, 500)
  }

  test("a different base-url (host) does not match -> 404") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = call("http://api.inventory.com/v1/charges", "happy")
    assertEquals(code, 404)
  }

  test("a different path does not match -> 404") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = call("http://api.payments.com/v1/refunds", "happy")
    assertEquals(code, 404)
  }

  test("an unknown profile is terminal -> 404 (never forwarded)") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = call("http://api.payments.com/v1/charges", "does-not-exist")
    assertEquals(code, 404)
  }

  test("no profile header does not match -> 404") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, _) = callRaw("http://api.payments.com/v1/charges", "POST", Map(InductionHeaders.Caller -> "payment-service"))
    assertEquals(code, 404)
  }

  test("one profile can mock several services at once") {
    val body =
      s"""{ "profile":"chaos", "caller":"payment-service", "behaviors":[
         |  { "name":"pay", "match":{"baseUrl":"http://api.payments.com","method":"POST","path":"/v1/charges"},
         |    "response":{"status":200,"jsonBody":{"ok":true}} },
         |  { "name":"inv", "match":{"baseUrl":"http://api.inventory.com","method":"GET","pathPattern":"/v2/stock/.*"},
         |    "response":{"status":503,"body":"down"} }
         |]}""".stripMargin
    assertEquals(post(s"$controlBase/__induction/register", body)._1, 201)

    assertEquals(call("http://api.payments.com/v1/charges", "chaos")._1, 200)
    assertEquals(callRaw("http://api.inventory.com/v2/stock/42", "GET",
      Map(InductionHeaders.Profile -> "chaos", InductionHeaders.Caller -> "payment-service"))._1, 503)
  }

  // --- create-only register + update -------------------------------------

  test("register rejects a duplicate match -> 409") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (code, body) = register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"AGAIN"}""")
    assertEquals(code, 409)
    assert(body.contains("already exists"), body)
  }

  test("register rejects a cosmetically-different duplicate match -> 409") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    // Same effective target: trailing slash on base-url, upper-case host, and the
    // base-url/path split chosen differently — all normalize to the same matcher.
    val (c1, _) = register("happy", "http://api.payments.com/", "POST", "/v1/charges", 200, """{"x":1}""")
    assertEquals(c1, 409)
    val (c2, _) = register("happy", "http://API.PAYMENTS.COM", "POST", "/v1/charges", 200, """{"x":1}""")
    assertEquals(c2, 409)
    val (c3, _) = register("happy", "http://api.payments.com/v1", "POST", "/charges", 200, """{"x":1}""")
    assertEquals(c3, 409)
  }

  test("update replaces an existing behavior's response") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (uc, _) = updateMock("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"DECLINED"}""")
    assertEquals(uc, 200)
    val (_, body) = call("http://api.payments.com/v1/charges", "happy")
    assert(body.contains("DECLINED"), body)
  }

  test("update a mock that does not exist -> 404") {
    val (code, body) = updateMock("ghost", "http://api.payments.com", "POST", "/v1/charges", 200, """{"x":1}""")
    assertEquals(code, 404)
    assert(body.contains("no mock"), body)
  }

  // --- request log -------------------------------------------------------

  test("the request log records data-plane calls with response + induction headers") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    call("http://api.payments.com/v1/charges", "happy")          // matched
    call("http://api.payments.com/v1/charges", "ghost")          // no-match 404

    val (code, body) = get(s"$controlBase/__induction/requests")
    assertEquals(code, 200)
    assert(body.contains("\"url\":\"http://api.payments.com/v1/charges\""), body)
    assert(body.contains("\"profile\":\"happy\""), body)
    assert(body.contains("\"matched\":true"), body)
    assert(body.contains("\"matched\":false"), body)
    assert(body.contains("CONFIRMED"), body)
  }

  test("control-plane calls are excluded from the request log") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (_, body) = get(s"$controlBase/__induction/requests")
    assert(!body.contains("__induction"), body)
  }

  test("clearing the request log empties it but keeps behaviors") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    call("http://api.payments.com/v1/charges", "happy")
    assertEquals(delete(s"$controlBase/__induction/requests")._1, 200)
    assertEquals(get(s"$controlBase/__induction/requests")._2, "[]")
    // behavior still registered -> still serves
    assertEquals(call("http://api.payments.com/v1/charges", "happy")._1, 200)
  }

  // --- lifecycle ---------------------------------------------------------

  test("delete removes a profile's behaviors") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val (dc, db) = delete(s"$controlBase/__induction/happy/payment-service")
    assertEquals(dc, 200)
    assert(db.contains("\"removed\":1"), db)
    assertEquals(call("http://api.payments.com/v1/charges", "happy")._1, 404)
  }

  test("reset clears everything") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    assertEquals(post(s"$controlBase/__induction/reset", "")._1, 200)
    assertEquals(get(s"$controlBase/__induction/status")._2, "[]")
  }

  // --- validation --------------------------------------------------------

  test("register without a profile is a 400") {
    val body = """{"caller":"payment-service","behaviors":[{"match":{"baseUrl":"http://x.com","method":"GET","path":"/"},"response":{"status":200}}]}"""
    val (code, resp) = post(s"$controlBase/__induction/register", body)
    assertEquals(code, 400)
    assert(resp.contains("required"), resp)
  }

  test("register without behaviors is a 400") {
    val (code, resp) = post(s"$controlBase/__induction/register", """{"profile":"p","caller":"c"}""")
    assertEquals(code, 400)
    assert(resp.contains("behaviors"), resp)
  }

  test("a behavior missing baseUrl is a 400") {
    val body = """{"profile":"p","caller":"c","behaviors":[{"match":{"method":"GET","path":"/"},"response":{"status":200}}]}"""
    val (code, resp) = post(s"$controlBase/__induction/register", body)
    assertEquals(code, 400)
    assert(resp.contains("required"), resp)
  }

  test("an unknown route is a 404") {
    val (code, resp) = get(s"$controlBase/__induction/unknown")
    assertEquals(code, 404)
    assert(resp.contains("no route"), resp)
  }

  // --- observability -----------------------------------------------------

  test("the mock engine logs induced vs no-match with the target URL") {
    register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    val logs = capturingLogs("induction.MockEngine") {
      call("http://api.payments.com/v1/charges", "happy")          // matches  -> induced
      call("http://api.payments.com/v1/charges", "ghost")          // unknown  -> no-match 404
    }
    assert(
      logs.exists(l => l.startsWith("induced POST") && l.contains("api.payments.com/v1/charges") &&
        l.contains("-> 200") && l.contains("profile=happy")),
      logs.mkString("\n")
    )
    assert(
      logs.exists(l => l.startsWith("no-match POST") && l.contains("-> 404")),
      logs.mkString("\n")
    )
  }

  test("the control plane logs registrations") {
    val logs = capturingLogs("induction.InductionControlFilter") {
      register("happy", "http://api.payments.com", "POST", "/v1/charges", 200, """{"status":"CONFIRMED"}""")
    }
    assert(logs.exists(_ == "control POST /__induction/register"), logs.mkString("\n"))
  }

  // --- helpers -----------------------------------------------------------

  private def controlBase = s"http://localhost:$port"

  /** Register a single-behavior profile keyed by full base-url + method + path. */
  private def register(profile: String, baseUrl: String, method: String, path: String,
                       status: Int, jsonBody: String): (Int, String) =
    val body =
      s"""{ "profile":"$profile", "caller":"payment-service", "behaviors":[
         |  { "name":"b",
         |    "match":{"baseUrl":"$baseUrl","method":"$method","path":"$path"},
         |    "response":{"status":$status,"headers":{"Content-Type":"application/json"},"jsonBody":$jsonBody} }
         |]}""".stripMargin
    post(s"$controlBase/__induction/register", body)

  /** Update a single behavior (located by its match) with a new response. */
  private def updateMock(profile: String, baseUrl: String, method: String, path: String,
                         status: Int, jsonBody: String): (Int, String) =
    val body =
      s"""{ "profile":"$profile", "caller":"payment-service", "behaviors":[
         |  { "name":"b",
         |    "match":{"baseUrl":"$baseUrl","method":"$method","path":"$path"},
         |    "response":{"status":$status,"headers":{"Content-Type":"application/json"},"jsonBody":$jsonBody} }
         |]}""".stripMargin
    put(s"$controlBase/__induction/update", body)

  /** A data-plane POST through the sidecar proxy, with profile + caller headers. */
  private def call(targetUrl: String, profile: String): (Int, String) =
    callRaw(targetUrl, "POST", Map(InductionHeaders.Profile -> profile, InductionHeaders.Caller -> "payment-service"))

  private def callRaw(targetUrl: String, method: String, headers: Map[String, String]): (Int, String) =
    val publisher = if method == "GET" then HttpRequest.BodyPublishers.noBody() else BodyPublishers.ofString("{}")
    val builder   = HttpRequest.newBuilder(URI.create(targetUrl)).method(method, publisher)
    headers.foreach((k, v) => builder.header(k, v))
    val resp = proxied.send(builder.build(), BodyHandlers.ofString())
    (resp.statusCode, resp.body)

  private def get(url: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).GET().build())

  private def delete(url: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).DELETE().build())

  private def post(url: String, body: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).POST(BodyPublishers.ofString(body)).build())

  private def put(url: String, body: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).PUT(BodyPublishers.ofString(body)).build())

  private def send(req: HttpRequest): (Int, String) =
    val resp = direct.send(req, BodyHandlers.ofString())
    (resp.statusCode, resp.body)

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
