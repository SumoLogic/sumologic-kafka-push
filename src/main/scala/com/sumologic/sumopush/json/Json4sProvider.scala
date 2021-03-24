package com.sumologic.sumopush.json

import com.jayway.jsonpath.JsonPathException
import com.jayway.jsonpath.spi.json.JsonProvider
import org.json4s.JsonAST.{JArray, JObject, JString, JValue}
import org.json4s.native.Serialization
import org.json4s.{DefaultFormats, Formats, JDecimal, JDouble, JInt, JLong}

import java.io.{InputStream, InputStreamReader}
import java.{lang, util}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

//noinspection ScalaDeprecation
object Json4sProvider extends JsonProvider {
  implicit val formats: Formats = DefaultFormats

  override def parse(json: String): JValue = Serialization.read[JValue](json)

  override def parse(stream: InputStream, charset: String): JValue = Serialization.read(new InputStreamReader(stream, charset))

  override def toJson(obj: Any): String = Serialization.write(obj)

  override def createArray(): ArrayBuffer[Any] = ArrayBuffer[Any]()

  override def createMap(): Map[String, Any] = Map.empty

  override def isArray(obj: Any): Boolean = obj.isInstanceOf[JArray]

  override def length(obj: Any): Int = {
    obj match {
      case a: ArrayBuffer[_] => a.length
      case ja: JArray => ja.values.length
      case m: Map[_, _] => m.size
      case s: String => s.length
      case _ => throw new JsonPathException("length operation cannot be applied")
    }
  }

  override def toIterable(obj: Any): lang.Iterable[_] = {
    obj match {
      case ab: ArrayBuffer[Any] => ab.asJava
    }
  }

  override def getPropertyKeys(obj: Any): util.List[String] = {
    obj match {
      case o: JObject => o.values.keys.toList.asJava
      case _ => throw new UnsupportedOperationException
    }
  }

  override def getArrayIndex(obj: Any, idx: Int): Any = {
    obj match {
      case ja: JArray => unwrap(ja(idx))
      case ab: ArrayBuffer[_] => unwrap(ab(idx))
    }
  }

  override def getArrayIndex(obj: Any, idx: Int, unwrap: Boolean): Any = throw new UnsupportedOperationException

  override def setArrayIndex(array: Any, idx: Int, newValue: Any): Unit = {
    array match {
      case a: ArrayBuffer[Any] =>
        if (a.length == idx)
          a.addOne(newValue)
        else
          a(idx) = newValue
    }
  }

  override def getMapValue(obj: Any, key: String): Any = {
    obj match {
      case o: JObject => o \ key
      case m: Map[String, Any] => m(key)
      case _ => throw new UnsupportedOperationException
    }
  }

  override def setProperty(obj: Any, key: Any, value: Any): Unit = throw new UnsupportedOperationException

  override def removeProperty(obj: Any, key: Any): Unit = throw new UnsupportedOperationException

  override def isMap(obj: Any): Boolean = obj.isInstanceOf[JObject] || obj.isInstanceOf[Map[String, Any]]

  override def unwrap(obj: Any): Any = {
    obj match {
      case o: JObject => o.extract[Map[String, Any]]
      case JString(s) => s
      case JDouble(d) => d
      case JDecimal(d) => d
      case JLong(l) => l
      case JInt(i) => i
      case f => f
    }
  }
}
