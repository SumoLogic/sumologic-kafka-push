package com.sumologic.sumopush

import com.sumologic.sumopush.actor.LogProcessor
import com.sumologic.sumopush.model.{HeaderField, LogRequest, RawJsonRequest, SumoDataType, SumoLogsSerializer}
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers
import org.mockito.{Mockito => M}
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.Logger
import org.slf4j.helpers.NOPLogger

class JsonLogTest extends BaseTest with MockitoSugar {
  private val withFallback = ConfigFactory.load("test-fallback")
  private val withoutFallback = ConfigFactory.load("test-nofallback")
  private val withFieldTypes = ConfigFactory.load("test-field-types")
  private val withFieldInvalidChars = ConfigFactory.load("test-field-invalid-characters")
  private val withFieldsInPayload = ConfigFactory.load("test-fields-in-payload")
  private val withFieldsInPayloadAndWrapperKeyDisabled = ConfigFactory.load("test-fields-in-payload-payloadText")
  private val cfgWithFallback = AppConfig(SumoDataType.logs, withFallback)
  private val cfgWithoutFallback = AppConfig(SumoDataType.logs, withoutFallback)
  private val cfgFieldTypes = AppConfig(SumoDataType.logs, withFieldTypes)
  private val cfgFieldInvalidChars = AppConfig(SumoDataType.logs, withFieldInvalidChars)
  private val cfgFieldsInPayload = AppConfig(SumoDataType.logs, withFieldsInPayload)
  private val cfgFieldsInPayloadAndWrapperKeyDisabled = AppConfig(SumoDataType.logs, withFieldsInPayloadAndWrapperKeyDisabled)

  private val missingCategoryMsg = Utils.jsonLogEventFromResource("missingCategory.json")
  private val missingNameMsg = Utils.jsonLogEventFromResource("missingName.json")
  private val missingPayloadMsg = Utils.jsonLogEventFromResource("missingPayload.json")
  private val missingFieldMsg = Utils.jsonLogEventFromResource("missingField.json")
  private val badPayloadArrayMsg = Utils.jsonLogEventFromResource("badPayloadArray.json")
  private val fieldsTypesMsg = Utils.jsonLogEventFromResource("fieldsTypes.json")
  private val fieldsWithInvalidCharactersMsg = Utils.jsonLogEventFromResource("fieldsWithInvalidCharacters.json")
  private val jsonPayloadWithFieldsMsg = Utils.jsonLogEventFromResource("jsonPayloadWithFields.json")

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
    logFallback.head.fields shouldBe Seq(HeaderField("existingField", "fieldValue"))
    logFallback.head.fields.head.toHeaderPart shouldBe "existingField=fieldValue"
  }

  "LogProcessor" should "forward full message if payload is an empty array" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgFieldTypes, "topic", badPayloadArrayMsg, logger)
    logFallback.size shouldBe 1
    logFallback.head.logs.head should matchPattern {
      case LogRequest(_, """{"log":{"log":[]},"category":{"source":"sourceCategory"},"name":{"source":"sourceName"},"additionalFields":{"string":"stringvalue","null":null,"bool":true,"int":100,"bigint":228930314431312345}}""") =>
    }
  }

  "LogProcessor" should "extract fields with various types" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgFieldTypes, "topic", fieldsTypesMsg, logger)
    logFallback.size shouldBe 1
    logFallback.head.fields should contain theSameElementsAs Seq(
      HeaderField("stringfield", "stringvalue"),
      HeaderField("intfield", "100"),
      HeaderField("bigintfield", "228930314431312345"),
      HeaderField("boolfield", "true"),
    )
    logFallback.head.fields.map(_.toHeaderPart) should contain theSameElementsAs Seq(
      "stringfield=stringvalue",
      "intfield=100",
      "bigintfield=228930314431312345",
      "boolfield=true",
    )
  }

  "LogProcessor" should "make sure fields do not contain invalid characters" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgFieldInvalidChars, "topic", fieldsWithInvalidCharactersMsg, logger)
    logFallback.size shouldBe 1
    logFallback.head.fields should contain theSameElementsAs Seq(
      HeaderField("stringfield", "stringvalue"),
    )
    logFallback.head.fields.head.toHeaderPart shouldBe "stringfield=stringvalue"
  }

  "LogProcessor" should "combine fields with log payload" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgFieldsInPayload, "topic", jsonPayloadWithFieldsMsg, logger)
    logFallback.size shouldBe 1
    val jsonRequest = SumoLogsSerializer.toJson(logFallback.head)
    jsonRequest should include("\"log\":{\"timestamp\":1654016401446,\"PID\":\"3\",\"USER\":\"root\",\"MEM\":\"0.0\",\"CPU\":\"0.0\"},\"metadata\":{\"bigintfield\":\"228930314431312345\",\"boolfield\":\"true\",\"intfield\":\"100\",\"stringfield\":\"stringvalue\"}}")
    logFallback.head.fields.size should be(0)
  }

  "LogProcessor" should "combine fields with log payload as JSON when payloadText is true for a raw text payload" in {
    val logger = NOPLogger.NOP_LOGGER
    val logFallback = LogProcessor.createSumoRequestsFromLogEvent(cfgFieldsInPayloadAndWrapperKeyDisabled, "topic", fieldsTypesMsg, logger)
    logFallback.size shouldBe 1
    val jsonRequest = SumoLogsSerializer.toJson(logFallback.head)
    jsonRequest should include("\"log\":\"I0416 19:32:53.813537       1 utils.go:467] Removing unregistered node aws:///us-west-2c/i-testtesttest1test\\n\",\"metadata\":{\"bigintfield\":\"228930314431312345\",\"boolfield\":\"true\",\"intfield\":\"100\",\"stringfield\":\"stringvalue\"}}")
    logFallback.head.fields.size should be(0)
  }
}
