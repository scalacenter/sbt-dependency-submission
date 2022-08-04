package ch.epfl.scala

import munit.FunSuite
import sjsonnew.shaded.scalajson.ast.unsafe.JField
import sjsonnew.shaded.scalajson.ast.unsafe.JNumber
import sjsonnew.shaded.scalajson.ast.unsafe.JObject
import sjsonnew.shaded.scalajson.ast.unsafe.JString
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.Converter
import sjsonnew.support.scalajson.unsafe.Parser

class JsonProtocolTests extends FunSuite {
  test("encode metadata") {
    import ch.epfl.scala.githubapi.JsonProtocol._
    val metadata = Map("key1" -> JString("value1"), "key2" -> JNumber(1))
    val obtained = Converter.toJson(metadata).get
    val expected = JObject(JField("key1", JString("value1")), JField("key2", JNumber(1)))
    assertEquals(obtained, expected)
  }

  test("decode metadata") {
    import ch.epfl.scala.githubapi.JsonProtocol._
    val metadata = JObject(JField("key1", JString("value1")), JField("key2", JNumber(1)))
    val obtained = Converter.fromJson[Map[String, JValue]](metadata).get
    val expected = Map("key1" -> JString("value1"), "key2" -> JNumber(1))
    assertEquals(obtained, expected)
  }

  test("decode empty input") {
    import ch.epfl.scala.JsonProtocol._
    val raw = Parser.parseUnsafe("{}")
    val obtained = Converter.fromJson[SubmitInput](raw).get
    val expected = SubmitInput(None, Vector.empty, Vector.empty)
    assertEquals(obtained, expected)
  }

  test("decode input with onResolveFailure: warning") {
    import ch.epfl.scala.JsonProtocol._
    val raw = Parser.parseUnsafe("""{"onResolveFailure": "warning"}""")
    val obtained = Converter.fromJson[SubmitInput](raw).get
    val expected = SubmitInput(Some(OnFailure.warning), Vector.empty, Vector.empty)
    assertEquals(obtained, expected)
  }

  test("decode input with ignoredDependencies") {
    import ch.epfl.scala.JsonProtocol._
    val raw = Parser.parseUnsafe("""{"ignoredDependencies": [{"organization": "org.test", "name": "thing"}]}""")
    val obtained = Converter.fromJson[SubmitInput](raw).get
    val expected = SubmitInput(None, Vector.empty, Vector(Dependency("org.test", Some("thing"), None)))
    assertEquals(obtained, expected)
  }
}
