package com.zbsplatform.matcher.market
import java.io.File
import java.nio.file.Files

import akka.actor.ActorSystem
import akka.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import com.zbsplatform.TestHelpers.deleteRecursively
import com.zbsplatform.settings.loadConfig
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

abstract class MatcherSpec(actorSystemName: String) extends TestKitBase with WordSpecLike with Matchers with BeforeAndAfterAll {
  import MatcherSpec._
  implicit lazy val system: ActorSystem = ActorSystem(
    actorSystemName,
    loadConfig(ConfigFactory.parseString(s"$SnapshotStorePath = ${Files.createTempDirectory("order-book-actor-spec")}"))
  )
  override protected def afterAll(): Unit = {
    super.afterAll()
    shutdown(system)
    deleteRecursively(new File(system.settings.config.getString(SnapshotStorePath)).toPath)
  }
}

object MatcherSpec {
  private[MatcherSpec] val SnapshotStorePath = "akka.persistence.snapshot-store.local.dir"
}