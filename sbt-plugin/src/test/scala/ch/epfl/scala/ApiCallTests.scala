package ch.epfl.scala

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import munit.FunSuite
import sbt.Logger
import sbt.internal.util.MessageOnlyException

class ApiCallTests extends FunSuite {

  import ApiCall.ServerError

  /** Captures warn messages; all other log levels are no-ops. */
  private def stubLog(warnings: mutable.Buffer[String]): Logger =
    new Logger {
      override def trace(t: => Throwable): Unit = ()
      override def success(message: => String): Unit = ()
      override def log(level: sbt.Level.Value, message: => String): Unit =
        if (level == sbt.Level.Warn) warnings += message
    }

  test("retryOnServerError returns immediately on success") {
    var callCount = 0
    val warnings = mutable.Buffer.empty[String]
    val result = ApiCall.retryOnServerError(stubLog(warnings), delay = Duration.Zero) {
      callCount += 1
      Success("ok")
    }
    assertEquals(result, Success("ok"))
    assertEquals(callCount, 1)
    assert(warnings.isEmpty)
  }

  test("retryOnServerError retries on 5XX and succeeds on second attempt") {
    var callCount = 0
    val warnings = mutable.Buffer.empty[String]
    val result = ApiCall.retryOnServerError(stubLog(warnings), retriesLeft = 3, delay = Duration.Zero) {
      callCount += 1
      if (callCount == 1) Failure(ServerError(503, "Service Unavailable", "Error details"))
      else Success("ok")
    }
    assertEquals(result, Success("ok"))
    assertEquals(callCount, 2)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("503"))
  }

  test("retryOnServerError exhausts retries and returns MessageOnlyException") {
    var callCount = 0
    val warnings = mutable.Buffer.empty[String]
    val result = ApiCall.retryOnServerError(stubLog(warnings), retriesLeft = 2, delay = Duration.Zero) {
      callCount += 1
      Failure(ServerError(502, "Bad Gateway", "Error details"))
    }
    assert(result.isFailure)
    assertEquals(callCount, 3) // initial attempt + 2 retries
    assertEquals(warnings.size, 2)
    result match {
      case Failure(e: MessageOnlyException) => assert(e.getMessage.contains("502"))
      case other                            => fail(s"Unexpected result: $other")
    }
  }

  test("retryOnServerError does not retry on non-5XX failure") {
    var callCount = 0
    val warnings = mutable.Buffer.empty[String]
    val err = new RuntimeException("client error")
    val result = ApiCall.retryOnServerError(stubLog(warnings), delay = Duration.Zero) {
      callCount += 1
      Failure(err)
    }
    assertEquals(callCount, 1)
    assert(warnings.isEmpty)
    assertEquals(result, Failure(err))
  }

  test("retryOnServerError with zero retries returns MessageOnlyException immediately") {
    var callCount = 0
    val result = ApiCall.retryOnServerError(
      stubLog(mutable.Buffer.empty),
      retriesLeft = 0,
      delay = Duration.Zero
    ) {
      callCount += 1
      Failure(ServerError(503, "Service Unavailable", "Error details"))
    }
    assertEquals(callCount, 1)
    result match {
      case Failure(e: MessageOnlyException) => assert(e.getMessage.contains("503"))
      case other                            => fail(s"Unexpected result: $other")
    }
  }
}
