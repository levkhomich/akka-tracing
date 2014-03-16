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
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import org.apache.thrift.transport.{TFramedTransport, TServerSocket}
import org.apache.thrift.server.{TServer, TSimpleServer}
import org.apache.thrift.server.TServer.Args
import org.specs2.mutable.Specification


class TracingSpecification extends Specification {

  val SpanCount = 10000

  case class StringMessage(content: String) extends TracingSupport

  sequential

  val collector = startCollector()

  val system = ActorSystem("TestSystem", ConfigFactory.empty())
  val trace = TracingExtension(system)
  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  "TracingExtension" should {
    "sample traces" in {
      def traceMessages(count: Int): Unit =
        for (_ <- 1 to count) {
          val msg = StringMessage(UUID.randomUUID().toString)
          trace.recordServerReceive(msg)
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

    "corretly shutdown" in {
      system.shutdown()
      collector.stop()
      system.awaitTermination(FiniteDuration(5, duration.SECONDS)) must not(throwA[TimeoutException])
    }

//    "process more than 40000 traces per second using single thread" in {
//      val startingTime = System.currentTimeMillis()
//      for (_ <- 1 to SpanCount) {
//        val msg = StringMessage(UUID.randomUUID().toString)
//        trace.recordServerReceive(msg)
//        trace.recordRPCName(msg, "test", "message-" + Math.abs(msg.content.hashCode) % 50)
//        trace.recordKeyValue(msg, "keyLong", Random.nextLong())
//        trace.recordKeyValue(msg, "keyString", UUID.randomUUID().toString + "-" + UUID.randomUUID().toString + "-")
//        trace.recordServerSend(msg)
//      }
//      val tracesPerSecond = SpanCount * 1000 / (System.currentTimeMillis() - startingTime)
//      Thread.sleep(5000)
//      println(results)
//      tracesPerSecond must beGreaterThan(40000L)
//    }
  }

  def startCollector(): TServer = {
    val handler = new thrift.Scribe.Iface {
      override def Log(messages: util.List[thrift.LogEntry]): thrift.ResultCode = {
        results.addAll(messages)
        thrift.ResultCode.OK
      }
    }
    val processor = new thrift.Scribe.Processor(handler)
    val transport = new TServerSocket(9410)
    val server = new TSimpleServer(
      new Args(transport).processor(processor).transportFactory(new TFramedTransport.Factory)
    )
    val collectorThread = new Thread(new Runnable() {
      override def run(): Unit = {
        server.serve()
      }
    }).start()
    Thread.sleep(500)
    server
  }


}
