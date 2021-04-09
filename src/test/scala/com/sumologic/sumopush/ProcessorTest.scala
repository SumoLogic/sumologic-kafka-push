package com.sumologic.sumopush

import com.sumologic.sumopush.actor.MessageProcessor
import com.sumologic.sumopush.actor.MessageProcessor._
import com.sumologic.sumopush.model.SumoDataType
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should._

class TestProcessor extends MessageProcessor {}

class ProcessorTest extends AnyFlatSpec with Matchers {
  val cfg: Config = ConfigFactory.load()
  val dataType: SumoDataType.Value = SumoDataType.withName(cfg.getString("sumopush.dataType"))
  val appConfig: AppConfig = AppConfig(dataType, cfg)
  val processor: TestProcessor = new TestProcessor

  "processor" should "find a pod annotation endpoint name" in {
    val annotations = Map("sumologic.com/ep" -> "test")
    val container = "mycontainer"
    val epname = processor.findPodMetadataValue(EndpointAnnotationPrefix, "default", annotations, container)

    assert(epname == "test")
  }

  "processor" should "handle pod annotation endpoint name container override" in {
    val annotations = Map("sumologic.com/ep" -> "test","sumologic.com/ep.mycontainer" -> "testcontainer" )
    val container = "mycontainer"
    val epname = processor.findPodMetadataValue(EndpointAnnotationPrefix, "default", annotations, container)

    assert(epname == "testcontainer")
  }

  "processor" should "handle default pod annotation endpoint name" in {
    val annotations = Map.empty[String, String]
    val container = "mycontainer"
    val epname = processor.findPodMetadataValue(EndpointAnnotationPrefix, "default", annotations, container)

    assert(epname == "default")
  }
}
