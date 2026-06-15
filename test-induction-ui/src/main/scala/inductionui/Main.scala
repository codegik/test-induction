package inductionui

/** Entry point for the test-induction UI.
  *
  * A tiny web app to manage the sidecar's mock behaviors. It is deliberately
  * segregated from the API and runs on its own port (UI_PORT, default 8090):
  *
  *   - serves a static single-page app (HTML/CSS/JS) from the classpath;
  *   - reverse-proxies everything under `/api` to the API control plane
  *     (INDUCTION_API_BASEURL, default http://localhost:8080), so the browser
  *     only ever talks to this server — no CORS, no API URL baked into the page.
  */
object Main:
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val uiPort  = sys.env.getOrElse("UI_PORT", "8090").toInt
    val apiBase = sys.env.getOrElse("INDUCTION_API_BASEURL", "http://localhost:8080").stripSuffix("/")

    val server = new UiServer(uiPort, apiBase)
    server.start()
    log.info("test-induction-ui up on :{} — proxying /api -> {}", Integer.valueOf(uiPort), apiBase)

    sys.addShutdownHook(server.stop())
    Thread.currentThread().join()
