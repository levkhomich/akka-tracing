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

import java.util
import java.util.UUID
import java.util.concurrent.{TimeoutException, ConcurrentLinkedQueue}
import scala.util.Random
import javax.xml.bind.DatatypeConverter
import scala.collection.JavaConversions._
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorRef, Props, ActorSystem}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TFramedTransport, TServerSocket, TMemoryBuffer}
import org.apache.thrift.server.{TThreadPoolServer, TServer}
import org.specs2.mutable.Specification
import com.typesafe.config.ConfigFactory

import com.github.levkhomich.akka.tracing.thrift.{ResultCode, LogEntry}

class TestActor extends TracingActorLogging with ActorTracing {
  override def receive: Receive = {
    case msg: TracingSupport =>
      trace.sample(msg, "test")
      log.info("received message " + msg)
      Thread.sleep(100)
      trace.recordServerSend(msg)
  }
}

class TracingSpecification extends Specification {

  case class StringMessage(content: String) extends TracingSupport

  val collector: TServer = startCollector()

  val system: ActorSystem = ActorSystem("TestSystem", ConfigFactory.empty())
  val trace = TracingExtension(system)

  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  sequential

  "TracingExtension" should {
    "sample traces" in {
      def traceMessages(count: Int): Unit =
        for (_ <- 1 to count) {
          val msg = StringMessage(UUID.randomUUID().toString)
          trace.sample(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
          trace.recordServerSend(msg)
        }

      trace.holder ! SpanHolderInternalAction.SetSampleRate(1)
      traceMessages(2)
      trace.holder ! SpanHolderInternalAction.SetSampleRate(2)
      traceMessages(60)
      trace.holder ! SpanHolderInternalAction.SetSampleRate(5)
      traceMessages(500)

      Thread.sleep(3000)

      results.size() must beEqualTo(132)
    }

    "pipe logs to traces" in {
      results.clear()
      trace.holder ! SpanHolderInternalAction.SetSampleRate(1)

      val testActor: ActorRef = system.actorOf(Props[TestActor])

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

    val ExpectedTPS = 50000
    s"process more than $ExpectedTPS traces per second using single thread" in {
      val SpanCount = ExpectedTPS * 4
      trace.holder ! SpanHolderInternalAction.SetSampleRate(1)

      val startingTime = System.currentTimeMillis()
      for (_ <- 1 to SpanCount) {
        val msg = StringMessage(UUID.randomUUID().toString)
        trace.sample(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
        trace.recordKeyValue(msg, "keyLong", Random.nextLong())
        trace.recordKeyValue(msg, "keyString", UUID.randomUUID().toString + "-" + UUID.randomUUID().toString + "-")
        trace.recordServerSend(msg)
      }
      val tracesPerSecond = SpanCount * 1000 / (System.currentTimeMillis() - startingTime)
      Thread.sleep(5000)
      println("TPS = " + tracesPerSecond)

      tracesPerSecond must beGreaterThan(ExpectedTPS.toLong)
    }.pendingUntilFixed("Ignored due to performance issues of travis-ci")
  }

  "shutdown correctly" in {
    system.shutdown()
    collector.stop()
    system.awaitTermination(FiniteDuration(5, duration.SECONDS)) must not(throwA[TimeoutException])
  }

  private def startCollector(): TServer = {

    val handler = new thrift.Scribe.Iface {
      override def Log(messages: util.List[LogEntry]): ResultCode = {
        results.addAll(messages)
        thrift.ResultCode.OK
      }
    }
    val processor = new thrift.Scribe.Processor(handler)

    val transport = new TServerSocket(9410)
    val collector = new TThreadPoolServer(
      new TThreadPoolServer.Args(transport).processor(processor).
        transportFactory(new TFramedTransport.Factory).protocolFactory(new TBinaryProtocol.Factory).minWorkerThreads(3)
    )
    new Thread(new Runnable() {
      override def run(): Unit = {
        collector.serve()
      }
    }).start()
    Thread.sleep(3000)
    collector
  }

  private def decodeSpan(logEntryMessage: String): thrift.Span = {
    val protocolFactory = new TBinaryProtocol.Factory()
    val thriftBytes = DatatypeConverter.parseBase64Binary(logEntryMessage.dropRight(1))
    val buffer = new TMemoryBuffer(1024)
    buffer.write(thriftBytes, 0, thriftBytes.length)
    val span = new thrift.Span
    span.read(protocolFactory.getProtocol(buffer))
    span
  }

}
