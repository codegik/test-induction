package induction

/** Headers a caller sends at request time to trigger a registered behavior.
  *
  *   - [[Profile]] selects which test profile is active (e.g. "payment-timeout").
  *   - [[Caller]]  identifies the client type making the call (e.g. "payment-service").
  *
  * The sidecar registers WireMock stubs that match on both of these, so the same
  * mock engine can serve different behaviors per profile and per client type.
  */
object InductionHeaders:
  val Profile = "x-induction-test-profile"
  val Caller  = "x-induction-test-caller"
