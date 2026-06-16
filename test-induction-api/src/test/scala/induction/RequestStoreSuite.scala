package induction

class RequestStoreSuite extends munit.FunSuite:

  private def entry(i: Int) =
    RequestLogEntry(
      id = s"id-$i",
      loggedAt = "2026-01-01T00:00:00Z",
      method = "POST",
      url = s"http://api.test/$i",
      profile = "p",
      caller = "c",
      matched = true,
      status = 200,
      requestBody = "{}",
      responseBody = s"""{"n":$i}""",
    )

  test("insert + all returns rows newest first") {
    val store = RequestStore.inMemory()
    try
      store.insert(entry(1))
      store.insert(entry(2))
      val all = store.all()
      assertEquals(all.map(_.id), List("id-2", "id-1"))
    finally store.close()
  }

  test("clear empties the store") {
    val store = RequestStore.inMemory()
    try
      store.insert(entry(1))
      store.clear()
      assertEquals(store.all(), Nil)
    finally store.close()
  }

  test("keeps only the most recent MaxRows requests") {
    val store = RequestStore.inMemory()
    try
      val n = RequestStore.MaxRows + 50
      (1 to n).foreach(i => store.insert(entry(i)))
      val all = store.all(limit = n)
      assertEquals(all.size, RequestStore.MaxRows)
      // newest kept, oldest pruned
      assertEquals(all.head.id, s"id-$n")
      assert(!all.exists(_.id == "id-1"), "oldest should have been pruned")
    finally store.close()
  }
