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
import java.util.{ArrayList, Queue, UUID}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicLong
import javax.xml.bind.DatatypeConverter
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.actor.{Cancellable, Scheduler}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer

case class Span(id: Long, parentId: Option[Long], traceId: Long)

private[tracing] class SpanHolder(client: thrift.Scribe.Client, scheduler: Scheduler, var sampleRate: Int) {

  private[this] val counter = new AtomicLong(0)

  private[this] val spans = new ConcurrentHashMap[UUID, thrift.Span]()
  private[this] val sendJobs = new ConcurrentHashMap[UUID, Cancellable]()
  private[this] val serviceNames = new ConcurrentHashMap[Long, String]()
  private[this] val protocolFactory = new TBinaryProtocol.Factory()
  private[this] val sendQueue: Queue[thrift.Span] = new ConcurrentLinkedQueue[thrift.Span]()

  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt

  scheduler.schedule(0.seconds, 2.seconds) {
    val result = send()
    if (result != thrift.ResultCode.OK) {
      throw new RuntimeException
    }
  }

  private def getSpan(ts: TracingSupport): Option[thrift.Span] =
    Option(spans.get(ts.msgId))

  def sample(ts: TracingSupport): Boolean =
    getSpan(ts) match {
      case None if counter.incrementAndGet() % sampleRate == 0 =>
        val span = ts.span.getOrElse(Span(Random.nextLong(), None, Random.nextLong()))
        val spanInt = createSpan(ts.msgId, span)
        val oldValue = spans.putIfAbsent(ts.msgId, spanInt)
        oldValue == null

      case _ =>
        false
    }

  def update(ts: TracingSupport, send: Boolean = false)(f: thrift.Span => Unit): Unit =
    getSpan(ts) foreach { spanInt =>
      spanInt.synchronized {
        f(spanInt)
      }
      if (send) {
        enqueue(ts.msgId, cancelJob = true)
      }
    }

  def setServiceName(ts: TracingSupport, service: String): Unit =
    getSpan(ts) foreach { span =>
      serviceNames.putIfAbsent(span.id, service)
    }

  private[tracing] def createChildSpan(ts: TracingSupport): Option[Span] =
    getSpan(ts) match {
      case Some(parentSpan) =>
        Some(Span(Random.nextLong(), Some(parentSpan.id), parentSpan.trace_id))
      case _ =>
        None
    }

  private def createSpan(id: UUID, span: Span): thrift.Span = {
    sendJobs.putIfAbsent(id, scheduler.scheduleOnce(30.seconds) {
      enqueue(id, cancelJob = false)
    })
    val spanInt = new thrift.Span(span.traceId, null, span.id, new ArrayList(), new ArrayList())
    span.parentId.foreach(spanInt.set_parent_id)
    spanInt
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
    val list = new ArrayList[thrift.LogEntry]()
    while (next != null) {
      list.add(spanToLogEntry(next))
      next = sendQueue.poll()
    }

    if (!list.isEmpty)
      client.Log(list)
    else
      thrift.ResultCode.OK
  }

  private def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    val buffer = new TMemoryBuffer(1024)
    val endpoint = getEndpoint(spanInt.id)

    val iter = spanInt.annotations.iterator()
    while (iter.hasNext)
      iter.next().set_host(endpoint)
    val iter2 = spanInt.binary_annotations.iterator()
    while (iter2.hasNext)
      iter2.next().set_host(endpoint)

    import scala.collection.JavaConversions._
    spanInt.annotations.foreach(_.set_host(endpoint))
    spanInt.binary_annotations.foreach(_.set_host(endpoint))

    spanInt.write(protocolFactory.getProtocol(buffer))
    val thriftBytes = buffer.getArray.take(buffer.length)
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    new thrift.LogEntry("zipkin", encodedSpan)
  }

  private def getEndpoint(spanId: Long): thrift.Endpoint = {
    val service = Option(serviceNames.remove(spanId)).getOrElse("Unknown")
    new thrift.Endpoint(localAddress, 0, service)
  }

}
