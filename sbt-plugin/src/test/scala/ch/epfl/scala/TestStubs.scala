package ch.epfl.scala

import gigahorse.FullResponse

object TestStubs {

  /** Minimal FullResponse stub for testing HTTP response handling. */
  def stubResponse(statusCode: Int, statusMsg: String, body: String): FullResponse =
    new FullResponse {
      override def status: Int = statusCode
      override def statusText: String = statusMsg
      override def allHeaders: Map[String, List[String]] = Map.empty
      override def header(key: String): Option[String] = None
      override def underlying[A]: A = throw new UnsupportedOperationException
      override def close(): Unit = ()
      override def bodyAsString: String = body
      override def bodyAsByteBuffer: java.nio.ByteBuffer =
        java.nio.ByteBuffer.wrap(body.getBytes("UTF-8"))
    }
}
