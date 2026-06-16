package induction

import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer

/** Disk-backed (H2) store of recorded data-plane requests, so the request log
  * survives sidecar restarts. A single connection guarded by `synchronized` —
  * request volume in a test tool is low, so this is simpler than a pool.
  */
final class RequestStore(jdbcUrl: String):
  private val log = LoggerFactory.getLogger(getClass)
  Class.forName("org.h2.Driver")
  private val conn: Connection = DriverManager.getConnection(jdbcUrl, "sa", "")
  initSchema()

  private def initSchema(): Unit =
    val s = conn.createStatement()
    try
      s.execute(
        """CREATE TABLE IF NOT EXISTS requests (
          |  seq           BIGINT AUTO_INCREMENT PRIMARY KEY,
          |  id            VARCHAR(64),
          |  logged_at     VARCHAR(40),
          |  method        VARCHAR(16),
          |  url           VARCHAR(8192),
          |  profile       VARCHAR(512),
          |  caller        VARCHAR(512),
          |  matched       BOOLEAN,
          |  status        INT,
          |  request_body  CLOB,
          |  response_body CLOB
          |)""".stripMargin)
    finally s.close()
    log.info("request store ready: {}", jdbcUrl)

  def insert(e: RequestLogEntry): Unit = synchronized {
    val ps = conn.prepareStatement(
      "INSERT INTO requests (id, logged_at, method, url, profile, caller, matched, status, request_body, response_body)" +
        " VALUES (?,?,?,?,?,?,?,?,?,?)")
    try
      ps.setString(1, e.id)
      ps.setString(2, e.loggedAt)
      ps.setString(3, e.method)
      ps.setString(4, e.url)
      ps.setString(5, e.profile)
      ps.setString(6, e.caller)
      ps.setBoolean(7, e.matched)
      ps.setInt(8, e.status)
      ps.setString(9, e.requestBody)
      ps.setString(10, e.responseBody)
      ps.executeUpdate()
    finally ps.close()
    prune()
  }

  /** Keep only the most recent [[RequestStore.MaxRows]] rows so the file can't
    * grow without bound. `seq` is a monotonic identity, so anything at or below
    * `max(seq) - MaxRows` is older than the window we keep.
    */
  private def prune(): Unit =
    val s = conn.createStatement()
    try s.executeUpdate(s"DELETE FROM requests WHERE seq <= (SELECT MAX(seq) FROM requests) - ${RequestStore.MaxRows}")
    finally s.close()

  /** Recorded requests, newest first, capped at `limit`. */
  def all(limit: Int = 500): List[RequestLogEntry] = synchronized {
    val ps = conn.prepareStatement(
      "SELECT id, logged_at, method, url, profile, caller, matched, status, request_body, response_body" +
        " FROM requests ORDER BY seq DESC LIMIT ?")
    ps.setInt(1, limit)
    val rs = ps.executeQuery()
    val buf = ListBuffer.empty[RequestLogEntry]
    try
      while rs.next() do
        buf += RequestLogEntry(
          id = nz(rs.getString(1)),
          loggedAt = nz(rs.getString(2)),
          method = nz(rs.getString(3)),
          url = nz(rs.getString(4)),
          profile = nz(rs.getString(5)),
          caller = nz(rs.getString(6)),
          matched = rs.getBoolean(7),
          status = rs.getInt(8),
          requestBody = nz(rs.getString(9)),
          responseBody = nz(rs.getString(10)),
        )
    finally
      rs.close()
      ps.close()
    buf.toList
  }

  def clear(): Unit = synchronized {
    val s = conn.createStatement()
    try s.execute("DELETE FROM requests")
    finally s.close()
  }

  def close(): Unit = synchronized { conn.close() }

  private def nz(s: String): String = if s == null then "" else s

object RequestStore:
  /** Most recent requests retained on disk; older ones are pruned on insert. */
  val MaxRows = 1000

  /** File-backed store; creates the parent directory if needed. */
  def file(path: String): RequestStore =
    val p = Paths.get(path).toAbsolutePath
    Option(p.getParent).foreach(Files.createDirectories(_))
    new RequestStore(s"jdbc:h2:file:$p;AUTO_SERVER=TRUE")

  /** In-memory store (used by tests); kept alive for the JVM's lifetime. */
  def inMemory(): RequestStore =
    new RequestStore(s"jdbc:h2:mem:induction_${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1")
