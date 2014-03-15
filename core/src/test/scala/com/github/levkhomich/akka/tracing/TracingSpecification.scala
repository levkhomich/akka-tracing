package com.github.levkhomich.akka.tracing

import java.util.UUID
import scala.util.Random

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import org.specs2.mutable.Specification


class TracingSpecification extends Specification {

  case class StringMessage(content: String) extends TracingSupport

  val SpanCount = 100000
  val system = ActorSystem("TestSystem", ConfigFactory.empty())
  val trace = TracingExtension(system)

  "SpanHolder" should {
    "process more than 40000 traces per second using single thread" in {
      val startingTime = System.currentTimeMillis()
      for (_ <- 1 to SpanCount) {
        val msg = StringMessage(UUID.randomUUID().toString)
        trace.recordServerReceive(msg)
        trace.recordRPCName(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
        trace.recordKeyValue(msg, "keyLong", Random.nextLong())
        trace.recordKeyValue(msg, "keyString", UUID.randomUUID().toString + "-" + UUID.randomUUID().toString + "-")
        trace.recordServerSend(msg)
      }
      val tracesPerSecond = SpanCount * 1000 / (System.currentTimeMillis() - startingTime)
      tracesPerSecond must beGreaterThan(40000L)
    }
  }

}
