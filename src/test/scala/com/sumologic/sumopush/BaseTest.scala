package com.sumologic.sumopush

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

trait BaseTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  override def beforeAll(): Unit = {
    super.beforeAll()
    SumoPushMain.configureJsonPath()
  }
}