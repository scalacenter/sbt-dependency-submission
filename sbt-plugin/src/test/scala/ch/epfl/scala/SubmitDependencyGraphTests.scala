package ch.epfl.scala

import scala.util.Failure
import scala.util.Success

import ch.epfl.scala.ApiCall.ServerError
import ch.epfl.scala.SubmitDependencyGraph.getSnapshot
import ch.epfl.scala.SubmitDependencyGraph.successMessages
import ch.epfl.scala.SubmitDependencyGraph.successOutputs
import munit.FunSuite

class SubmitDependencyGraphTests extends FunSuite {

  test("successMessages shows submission id and response body") {
    val body =
      """{"id":123,"created_at":"2024-05-31T00:11:22.957Z","result":"ACCEPTED","message":"The snapshot was accepted, but it is not for the default branch. It will not update dependency results for the repository."}"""

    val res = successMessages(123L, body)

    assertEquals(
      res,
      Seq(
        "Submitted successfully, submission id: 123",
        s"GitHub submission response: $body"
      )
    )
  }

  test("successMessages does not contain fake success url") {
    val body = """{"id":123,"result":"ACCEPTED","message":"ok"}"""

    val res = successMessages(123L, body).mkString("\n")

    assert(!res.contains("/dependency-graph/snapshots/123"))
  }

  test("successOutputs preserves submission-id and url") {
    val res = successOutputs(
      "https://api.github.com/repos/foo/bar/dependency-graph/snapshots",
      123L
    )

    assertEquals(
      res,
      Seq(
        "submission-id" -> "123",
        "submission-api-url" -> "https://api.github.com/repos/foo/bar/dependency-graph/snapshots/123"
      )
    )
  }

  import TestStubs.stubResponse

  test("getSnapshot parses a valid 2XX response") {
    val body = """{"id":42,"created_at":"2024-05-31T00:11:22.957Z","result":"ACCEPTED","message":"ok"}"""
    val resp = stubResponse(201, "Created", body)
    getSnapshot(resp) match {
      case Success(snapshot) => assertEquals(snapshot.id, 42)
      case other             => fail(s"Unexpected result: $other")
    }
  }

  test("getSnapshot returns ServerError on 5XX") {
    val resp = stubResponse(503, "Service Unavailable", "")
    getSnapshot(resp) match {
      case Failure(ServerError(503, _, _)) => // expected
      case other                           => fail(s"Unexpected result: $other")
    }
  }

  test("getSnapshot returns failure on 4XX") {
    val resp = stubResponse(403, "Forbidden", "denied")
    getSnapshot(resp) match {
      case Failure(_: sbt.internal.util.MessageOnlyException) => // expected
      case other                                              => fail(s"Unexpected result: $other")
    }
  }
}
