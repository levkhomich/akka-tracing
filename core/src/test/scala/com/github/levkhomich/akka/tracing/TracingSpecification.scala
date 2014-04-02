/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.levkhomich.akka.tracing

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{TimeoutException, ConcurrentLinkedQueue}
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import org.specs2.mutable.Specification
import com.typesafe.config.ConfigFactory

import com.github.levkhomich.akka.tracing.thrift.ScribeFinagleService
import com.twitter.finagle.builder.{Server, ServerBuilder}
import com.twitter.finagle.thrift.ThriftServerFramedCodec
import com.twitter.util.{Future, Time}
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocolFactory}
import scala.util.Random


class TracingSpecification extends Specification {

  case class StringMessage(content: String) extends TracingSupport

  sequential

  startCollector()

  var collector: Server = _

  val system = ActorSystem("TestSystem", ConfigFactory.empty())
  val trace = TracingExtension(system)
  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  "TracingExtension" should {
    "sample traces" in {
      def traceMessages(count: Int): Unit =
        for (_ <- 1 to count) {
          val msg = StringMessage(UUID.randomUUID().toString)
          trace.sample(msg)
          trace.recordRPCName(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
          trace.recordServerSend(msg)
        }

      trace.holder.sampleRate = 1
      traceMessages(2)
      trace.holder.sampleRate = 2
      traceMessages(60)
      trace.holder.sampleRate = 5
      traceMessages(500)

      Thread.sleep(3000)

      results.size() must beEqualTo(132)
    }

//    "process more than 40000 traces per second using single thread" in {
//      val SpanCount = 200000
//      trace.holder.sampleRate = 1
//      val startingTime = System.currentTimeMillis()
//      for (_ <- 1 to SpanCount) {
//        val msg = StringMessage(UUID.randomUUID().toString)
//        trace.sample(msg)
//        trace.recordRPCName(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
//        trace.recordKeyValue(msg, "keyLong", Random.nextLong())
//        trace.recordKeyValue(msg, "keyString", UUID.randomUUID().toString + "-" + UUID.randomUUID().toString + "-")
//        trace.recordServerSend(msg)
//      }
//      val tracesPerSecond = SpanCount * 1000 / (System.currentTimeMillis() - startingTime)
//      Thread.sleep(5000)
//      println(results.size)
//      tracesPerSecond must beGreaterThan(40000L)
//    }
  }

  "corretly shutdown" in {
    system.shutdown()
    collector.close(Time.Top)
    system.awaitTermination(FiniteDuration(5, duration.SECONDS)) must not(throwA[TimeoutException])
  }

  def startCollector(): Unit = {
    val handler = new thrift.Scribe[Future] {
      override def log(messages: Seq[thrift.LogEntry]): Future[thrift.ResultCode] = {
        import scala.collection.JavaConversions._
        results.addAll(messages)
        Future(thrift.ResultCode.Ok)
      }
    }
    val service = new ScribeFinagleService(handler, new TBinaryProtocol.Factory)

    new Thread(new Runnable() {
      override def run(): Unit = {
        collector = ServerBuilder()
          .name("DummyScribeService")
          .bindTo(new InetSocketAddress(9410))
          .codec(ThriftServerFramedCodec())
          .build(service)
      }
    }).start()
    Thread.sleep(500)
  }


}
