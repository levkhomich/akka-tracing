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

import java.net.ServerSocket
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import javax.xml.bind.DatatypeConverter
import scala.collection.JavaConversions._

import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{ TFramedTransport, TServerSocket, TMemoryBuffer }
import org.apache.thrift.server.{ TThreadPoolServer, TServer }
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import com.github.levkhomich.akka.tracing.thrift.{ ResultCode, LogEntry }

trait MockCollector { this: Specification =>

  private[this] var socket = new ServerSocket(0)
  val collectorPort = socket.getLocalPort
  var collector: TServer = startCollector()
  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  def startCollector(): TServer = {
    val handler = new thrift.Scribe.Iface {
      override def Log(messages: util.List[LogEntry]): ResultCode = {
        println(s"collector: received ${messages.size} message${if (messages.size > 1) "s" else ""}")
        results.addAll(messages)
        thrift.ResultCode.OK
      }
    }
    val processor = new thrift.Scribe.Processor(handler)

    // reuse port between collector start/stop cycles
    if (socket.isClosed)
      socket = new ServerSocket(collectorPort)

    val transport = new TServerSocket(socket)
    val collector = new TThreadPoolServer(
      new TThreadPoolServer.Args(transport).processor(processor).
        transportFactory(new TFramedTransport.Factory).protocolFactory(new TBinaryProtocol.Factory).minWorkerThreads(3)
    )
    new Thread(new Runnable() {
      override def run(): Unit = {
        println("collector: started")
        collector.serve()
        println("collector: stopped")
      }
    }).start()
    Thread.sleep(3000)
    collector
  }

  def decodeSpan(logEntryMessage: String): thrift.Span = {
    val protocolFactory = new TBinaryProtocol.Factory()
    val thriftBytes = DatatypeConverter.parseBase64Binary(logEntryMessage.dropRight(1))
    val buffer = new TMemoryBuffer(1024)
    buffer.write(thriftBytes, 0, thriftBytes.length)
    val span = new thrift.Span
    span.read(protocolFactory.getProtocol(buffer))
    span
  }

  def receiveSpan(): thrift.Span = {
    Thread.sleep(3000)
    val spans = results.map(e => decodeSpan(e.message))
    spans.size mustEqual 1
    results.clear()
    spans.head
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: String): MatchResult[Any] = {
    span.binary_annotations.find(_.get_key == key) match {
      case Some(ba) =>
        val actualValue = new String(ba.get_value, "UTF-8")
        actualValue mustEqual expValue
      case _ =>
        ko(key + " = " + expValue + " not found").orThrow
    }
  }

  def printAnnotations(span: thrift.Span): Unit = {
    span.binary_annotations.foreach { ba =>
      val actualValue = new String(ba.get_value, "UTF-8")
      println(ba.get_key + " -> " + actualValue)
    }
  }

}
