package com.sumologic.sumopush

import com.sumologic.sumopush.actor.LogProcessor
import com.sumologic.sumopush.model.{LogRequest, SumoDataType}
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers
import org.mockito.{Mockito => M}
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.Logger
import org.slf4j.helpers.NOPLogger

class JsonLogTest extends BaseTest with MockitoSugar {
  private val withFallback = ConfigFactory.load("test-fallback")
  private val withoutFallback = ConfigFactory.load("test-nofallback")
  private val cfgWithFallback = AppConfig(SumoDataType.logs, withFallback)
  private val cfgWithoutFallback = AppConfig(SumoDataType.logs, withoutFallback)

  private val missingCategoryMsg = Utils.jsonLogEventFromResource("missingCategory.json")
  private val missingNameMsg = Utils.jsonLogEventFromResource("missingName.json")
  private val missingPayloadMsg = Utils.jsonLogEventFromResource("missingPayload.json")
  private val missingFieldMsg = Utils.jsonLogEventFromResource("missingField.json")

  "LogProcessor" should "fallback to topic for missing source category or name" in {
    val logger = NOPLogger.NOP_LOGGER
    val categoryFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgWithFallback, "topic", missingCategoryMsg, logger)
    categoryFallback.size shouldBe 1
    categoryFallback.head.sourceCategory shouldBe "topic"
    categoryFallback.head.sourceName shouldBe "sourceName"

    val nameFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgWithFallback, "topic", missingNameMsg, logger)
    nameFallback.size shouldBe 1
    nameFallback.head.sourceCategory shouldBe "sourceCategory"
    nameFallback.head.sourceName shouldBe "topic"
  }

  "LogProcessor" should "drop and log message with missing source category or name (if configured to do so)" in {
    val logger = mock[Logger]
    LogProcessor.createSumoRequestsFromLogEvent(cfgWithoutFallback, "topic", missingCategoryMsg, logger) shouldBe empty
    LogProcessor.createSumoRequestsFromLogEvent(cfgWithoutFallback, "topic", missingNameMsg, logger) shouldBe empty

    M.verify(logger, M.times(2))
      .warn(ArgumentMatchers.matches(""".*log.*log.*Removing unregistered node.*source.*source.*""")) //parts of the log message
  }

  "LogProcessor" should "forward full message if payload field not found" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgWithFallback, "topic", missingPayloadMsg, logger)
    logFallback.size shouldBe 1
    logFallback.head.logs.head should matchPattern {
      case LogRequest(_, """{"category":{"source":"sourceCategory"},"name":{"source":"sourceName"}}""") =>
    }
  }

  "LogProcessor" should "extract fields and not fail for not existing ones" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgWithFallback, "topic", missingFieldMsg, logger)
    logFallback.size shouldBe 1
    logFallback.head.fields shouldBe Seq("existingField=fieldValue")
  }
}
