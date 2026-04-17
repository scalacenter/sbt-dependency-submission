package ch.epfl.scala

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
}
