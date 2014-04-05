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

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicLong
import javax.xml.bind.DatatypeConverter
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.actor.{Cancellable, Scheduler}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer

private[tracing] case class Span(id: Long, parentId: Option[Long], traceId: Long)

/**
 * Internal API
 */
private[tracing] class SpanHolder(client: thrift.Scribe[Option], scheduler: Scheduler, var sampleRate: Int) {

  private[this] val counter = new AtomicLong(0)

  private[this] val spans = new ConcurrentHashMap[UUID, thrift.Span]()
  private[this] val sendJobs = new ConcurrentHashMap[UUID, Cancellable]()
  private[this] val serviceNames = new ConcurrentHashMap[Long, String]()
  private[this] val protocolFactory = new TBinaryProtocol.Factory()
  private[this] val sendQueue: util.Queue[thrift.Span] = new ConcurrentLinkedQueue[thrift.Span]()

  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt

  scheduler.schedule(0.seconds, 2.seconds) {
    val result = send()
    if (result != thrift.ResultCode.Ok) {
      throw new RuntimeException
    }
  }

  private def getSpan(msgId: UUID): Option[thrift.Span] =
    Option(spans.get(msgId))

  def sample(ts: TracingSupport): Boolean =
    getSpan(ts.msgId) match {
      case None if counter.incrementAndGet() % sampleRate == 0 =>
        val span = ts.span.getOrElse(Span(Random.nextLong(), None, Random.nextLong()))
        val spanInt = createSpan(ts.msgId, span)
        val oldValue = spans.putIfAbsent(ts.msgId, spanInt)
        oldValue == null

      case _ =>
        false
    }

  def update(msgId: UUID, send: Boolean = false)(f: thrift.Span => thrift.Span): Unit =
    getSpan(msgId) foreach { spanInt =>
      spanInt.synchronized {
        spans.put(msgId, f(spanInt))
      }
      if (send) {
        enqueue(msgId, cancelJob = true)
      }
    }

  def setServiceName(msgId: UUID, service: String): Unit =
    getSpan(msgId) foreach { span =>
      serviceNames.putIfAbsent(span.id, service)
    }

  private[tracing] def createChildSpan(msgId: UUID): Option[Span] =
    getSpan(msgId) match {
      case Some(parentSpan) =>
        Some(Span(Random.nextLong(), Some(parentSpan.id), parentSpan.traceId))
      case _ =>
        None
    }

  private def createSpan(id: UUID, span: Span): thrift.Span = {
    sendJobs.putIfAbsent(id, scheduler.scheduleOnce(30.seconds) {
      enqueue(id, cancelJob = false)
    })
    thrift.Span(span.traceId, null, span.id, span.parentId, Nil, Nil)
  }

  private def enqueue(id: UUID, cancelJob: Boolean): Unit = {
    val spanInt = spans.remove(id)
    val job = sendJobs.remove(id)
    if (job != null)
      job.cancel()
    if (spanInt != null)
      sendQueue.offer(spanInt)
  }

  private def send(): thrift.ResultCode = {
    var next = sendQueue.poll()
    var list: List[thrift.LogEntry] = Nil
    while (next != null) {
      list ::= spanToLogEntry(next)
      next = sendQueue.poll()
    }

    if (!list.isEmpty)
      client.log(list).getOrElse(thrift.ResultCode.TryLater)
    else
      thrift.ResultCode.Ok
  }

  private def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    val buffer = new TMemoryBuffer(1024)
    val endpoint = getEndpoint(spanInt.id)

    spanInt.copy(
      annotations = spanInt.annotations.map(a => a.copy(host = Some(endpoint))),
      binaryAnnotations = spanInt.binaryAnnotations.map(a => a.copy(host = Some(endpoint)))
    ).write(protocolFactory.getProtocol(buffer))

    val thriftBytes = buffer.getArray.take(buffer.length)
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    thrift.LogEntry("zipkin", encodedSpan)
  }

  private def getEndpoint(spanId: Long): thrift.Endpoint = {
    val service = Option(serviceNames.remove(spanId)).getOrElse("Unknown")
    thrift.Endpoint(localAddress, 0, service)
  }

}
