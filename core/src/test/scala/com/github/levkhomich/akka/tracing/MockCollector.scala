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
import java.nio.ByteBuffer
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

trait MockCollector { this: Specification with TracingTestCommons =>

  private[this] var socket = new ServerSocket(0)
  val collectorPort = socket.getLocalPort
  var collector: TServer = startCollector()
  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  val MaxAwaitTimeout = 10000 * (if (ciEnvironment) 30 else 1)
  val AwaitTimeout = 1000 * (if (ciEnvironment) 4 else 1)
  val AwaitStep = 100

  def startCollector(): TServer = {
    val handler = new thrift.Scribe.Iface {
      override def Log(messages: util.List[LogEntry]): ResultCode = {
        results.addAll(messages)
        println(s"collector received ${messages.size} message${if (messages.size > 1) "s" else ""}")
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
        println("collector started")
        collector.serve()
        println("collector stopped")
      }
    }).start()
    Thread.sleep(100)
    collector
  }

  def awaitSpans(): Unit = {
    val prevSize = results.size()
    val start = System.currentTimeMillis
    while (results.size == prevSize && System.currentTimeMillis - start < MaxAwaitTimeout) {
      Thread.sleep(AwaitStep)
    }
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
    val start = System.currentTimeMillis
    while (results.size < 1 && System.currentTimeMillis - start < MaxAwaitTimeout) {
      Thread.sleep(AwaitStep)
    }
    val spans = results.map(e => decodeSpan(e.message))
    results.clear()
    spans.size mustEqual 1
    spans.head
  }

  def receiveSpans(): List[thrift.Span] = {
    Thread.sleep(AwaitTimeout)
    val spans = results.map(e => decodeSpan(e.message))
    results.clear()
    spans.toList
  }

  def expectSpans(count: Int): MatchResult[_] = {
    if (count == 0) {
      Thread.sleep(AwaitTimeout)
      results.isEmpty mustEqual true
    } else
      expectSpans(count, count)
  }

  def expectSpans(minCount: Int, maxCount: Int): MatchResult[_] = {
    require(minCount > 0)
    require(minCount <= maxCount)
    val start = System.currentTimeMillis
    while (results.size < minCount && System.currentTimeMillis - start < MaxAwaitTimeout) {
      Thread.sleep(AwaitStep)
    }
    val size = results.size
    results.clear()
    size must be <= maxCount and (size must be >= minCount)
  }

  def getSpanCount: Int = {
    var start = System.currentTimeMillis
    var prevSize = 0

    var lastBatch = false
    while (!lastBatch) {
      while (results.size == prevSize && System.currentTimeMillis - start < MaxAwaitTimeout / 5) {
        Thread.sleep(AwaitStep)
      }
      if (results.size != prevSize) {
        prevSize = results.size
        start = System.currentTimeMillis
      } else
        lastBatch = true
    }
    results.clear()
    prevSize
  }

  private[this] def checkBinaryAnnotationInt[T](span: thrift.Span, key: String, expValue: T)(f: Array[Byte] => T): MatchResult[Any] = {
    span.binary_annotations.find(_.get_key == key) match {
      case Some(ba) =>
        val actualValue = f(ba.get_value)
        actualValue mustEqual expValue
      case _ =>
        ko(key + " = " + expValue + " not found").orThrow
    }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: String): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => new String(bytes, "UTF-8") }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Short): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes).getShort }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Int): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes).getInt }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Long): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes).getLong }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Double): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes).getDouble }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Boolean): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes).get == 1 }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: Array[Byte]): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => bytes }
  }

  def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: ByteBuffer): MatchResult[Any] = {
    checkBinaryAnnotationInt(span, key, expValue) { bytes => ByteBuffer.wrap(bytes) }
  }

  def checkAbsentBinaryAnnotation(span: thrift.Span, key: String): MatchResult[Any] = {
    span.binary_annotations.find(_.get_key == key) must beNone
  }

  def checkAnnotation(span: thrift.Span, expValue: String): MatchResult[Any] = {
    span.annotations.exists(_.get_value == expValue) mustEqual true
  }

  def printAnnotations(span: thrift.Span): Unit = {
    if (span.get_binary_annotations_size > 0) {
      println("Binary annotations:")
      span.binary_annotations.foreach { ba =>
        val actualValue = new String(ba.get_value, "UTF-8")
        println(ba.get_key + " -> " + actualValue)
      }
    }
    if (span.get_annotations_size > 0) {
      println("Annotations:")
      span.annotations.foreach { a =>
        println(a.get_value)
      }
    }
  }

}
