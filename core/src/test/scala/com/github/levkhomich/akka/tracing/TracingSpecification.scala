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
import java.util.concurrent.{CountDownLatch, TimeoutException, ConcurrentLinkedQueue}
import javax.xml.bind.DatatypeConverter
import scala.collection.JavaConversions._
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorRef, Props, ActorSystem}
import org.specs2.mutable.Specification
import com.typesafe.config.ConfigFactory

import com.github.levkhomich.akka.tracing.thrift.ScribeFinagleService
import com.twitter.finagle.builder.{Server, ServerBuilder}
import com.twitter.finagle.thrift.ThriftServerFramedCodec
import com.twitter.util.{Future, Time}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer


class TestActor extends TracingActorLogging with ActorTracing {
  override def receive: Receive = {
    case msg: TracingSupport =>
      trace.sample(msg)
      log.info("received message " + msg)
      Thread.sleep(100)
      trace.recordServerSend(msg)
  }
}

class TracingSpecification extends Specification {

  case class StringMessage(content: String) extends TracingSupport

  val collector: Server = startCollector()

  val system: ActorSystem = ActorSystem("TestSystem", ConfigFactory.empty())
  val trace = TracingExtension(system)
  val testActor: ActorRef = system.actorOf(Props[TestActor])

  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  sequential

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

    "pipe logs to traces" in {
      results.clear()
      trace.holder.sampleRate = 1

      testActor ! StringMessage("1")
      testActor ! StringMessage("2")
      testActor ! StringMessage("3")

      Thread.sleep(3000)

      results.size() must beEqualTo(3)
      results.forall { e =>
        val span = decodeSpan(e.message)
        span.annotations.size == 3 && span.annotations.get(1).value.startsWith("Info")
      } must beTrue
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

  private def startCollector(): Server = {
    val handler = new thrift.Scribe[Future] {
      override def log(messages: Seq[thrift.LogEntry]): Future[thrift.ResultCode] = {
        import scala.collection.JavaConversions._
        results.addAll(messages)
        Future(thrift.ResultCode.Ok)
      }
    }
    val service = new ScribeFinagleService(handler, new TBinaryProtocol.Factory)

    val latch = new CountDownLatch(1)
    var collector: Server = null
    new Thread(new Runnable() {
      override def run(): Unit = {
        collector = ServerBuilder()
          .name("DummyScribeService")
          .bindTo(new InetSocketAddress(9410))
          .codec(ThriftServerFramedCodec())
          .build(service)
        latch.countDown()
      }
    }).start()
    latch.await()
    collector
  }

  private def decodeSpan(logEntryMessage: String): thrift.Span = {
    val protocolFactory = new TBinaryProtocol.Factory()
    val thriftBytes = DatatypeConverter.parseBase64Binary(logEntryMessage.dropRight(1))
    val buffer = new TMemoryBuffer(1024)
    buffer.write(thriftBytes, 0, thriftBytes.length)
    thrift.Span.decode(protocolFactory.getProtocol(buffer))
  }

}
