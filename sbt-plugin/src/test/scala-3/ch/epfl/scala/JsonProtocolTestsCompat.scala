package ch.epfl.scala

trait JsonProtocolTestsCompat {
  export sbt.protocol.codec.JsonProtocol.given
}
