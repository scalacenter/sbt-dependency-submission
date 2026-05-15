package ch.epfl.scala

import scala.util.Failure
import scala.util.Success

import ch.epfl.scala.AnalyzeDependencyGraph.getVulnerabilities
import ch.epfl.scala.ApiCall.ServerError
import munit.FunSuite

class AnalyzeDependencyGraphTests extends FunSuite {

  import TestStubs.stubResponse

  private val emptyAlertsBody = "[]"

  test("getVulnerabilities returns empty list on 200 with empty array") {
    val resp = stubResponse(200, "OK", emptyAlertsBody)
    assertEquals(getVulnerabilities(resp), Success(Seq.empty))
  }

  test("getVulnerabilities returns ServerError on 500") {
    val resp = stubResponse(500, "Internal Server Error", "oops")
    getVulnerabilities(resp) match {
      case Failure(ServerError(500, "Internal Server Error", _)) => // expected
      case other                                                 => fail(s"Unexpected result: $other")
    }
  }

  test("getVulnerabilities returns ServerError on 503") {
    val resp = stubResponse(503, "Service Unavailable", "")
    getVulnerabilities(resp) match {
      case Failure(ServerError(503, _, _)) => // expected
      case other                           => fail(s"Unexpected result: $other")
    }
  }

  test("getVulnerabilities returns failure on 4XX") {
    val resp = stubResponse(401, "Unauthorized", "not allowed")
    getVulnerabilities(resp) match {
      case Failure(_: sbt.internal.util.MessageOnlyException) => // expected
      case other                                              => fail(s"Unexpected result: $other")
    }
  }
}
