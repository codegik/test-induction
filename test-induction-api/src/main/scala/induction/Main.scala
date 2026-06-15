package induction

/** Entry point for the test-induction sidecar.
  *
  * A single HTTP listener is started: the WireMock mock engine
  * (INDUCTION_MOCK_PORT, default 8080) serves the configured behaviors to the
  * application under test, and the control plane used to register and toggle
  * those behaviors at runtime is multiplexed onto the same port under the
  * reserved `/__induction` namespace.
  */
object Main:
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val mockPort = sys.env.getOrElse("INDUCTION_MOCK_PORT", "8080").toInt

    val engine = new MockEngine(mockPort)
    engine.start()

    log.info("test-induction sidecar up on :{} (mock engine + /__induction control plane)",
      Integer.valueOf(mockPort))

    sys.addShutdownHook(engine.stop())

    // Park the main thread so the JVM stays alive until shut down.
    Thread.currentThread().join()
