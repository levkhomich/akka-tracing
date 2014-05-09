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
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.{promise, Future}
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.actor.{Actor, Cancellable}
import org.apache.thrift.async.AsyncMethodCallback
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer

private[tracing] object SpanHolderInternalAction {
  final case class Sample(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class Enqueue(spanId: Long, cancelJob: Boolean)
  case object SendEnqueued
  final case class AddAnnotation(spanId: Long, timestamp: Long, msg: String)
  final case class AddBinaryAnnotation(spanId: Long, key: String, value: ByteBuffer, valueType: thrift.AnnotationType)
  final case class CreateChildSpan(spanId: Long, parentId: Long)
  final case class SetSampleRate(sampleRate: Int)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder(client: thrift.Scribe.AsyncIface, var sampleRate: Int) extends Actor {

  import SpanHolderInternalAction._

  private[this] var counter = 0L

  private[this] val spans = mutable.Map[Long, thrift.Span]()
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()
  private[this] val endpoints = mutable.Map[Long, thrift.Endpoint]()
  private[this] val sendQueue = mutable.UnrolledBuffer[thrift.Span]()

  private[this] val protocolFactory = new TBinaryProtocol.Factory()

  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt
  private[this] val unknownEndpoint = new thrift.Endpoint(localAddress, 0, "unknown")

  private[this] val microTimeAdjustment = System.currentTimeMillis * 1000 - System.nanoTime / 1000

  context.system.scheduler.schedule(0.seconds, 2.seconds, self, SendEnqueued)

  override def receive: Receive = {
    case Sample(ts, serviceName, rpcName, timestamp) =>
      counter += 1
      lookup(ts.spanId) match {
        case None if counter % sampleRate == 0 =>
          val endpoint = new thrift.Endpoint(localAddress, 0, serviceName)
          val serverRecvAnn = new thrift.Annotation(adjustedMicroTime(timestamp), thrift.zipkinConstants.SERVER_RECV)
          serverRecvAnn.set_host(endpoint)
          if (ts.traceId.isEmpty)
            ts.setTraceId(Some(Random.nextLong()))
          val annotations = new util.ArrayList[thrift.Annotation]()
          annotations.add(serverRecvAnn)
          createSpan(ts.spanId, ts.parentId, ts.traceId.get, rpcName, annotations)
          endpoints.put(ts.spanId, endpoint)

        // TODO: check if it really needed
        case Some(spanInt) if spanInt.name != rpcName || !endpoints.contains(ts.spanId) =>
          spanInt.set_name(rpcName)
          endpoints.put(ts.spanId, new thrift.Endpoint(localAddress, 0, serviceName))

        case _ =>
      }

    case Enqueue(spanId, cancelJob) =>
      enqueue(spanId, cancelJob)

    case SendEnqueued =>
      send()

    case AddAnnotation(spanId, timestamp, msg) =>
      lookup(spanId) foreach { spanInt =>
        val a = new thrift.Annotation(adjustedMicroTime(timestamp), msg)
        a.set_host(endpointFor(spanId))
        spanInt.add_to_annotations(a)
        if (a.value == thrift.zipkinConstants.SERVER_SEND) {
          enqueue(spanId, cancelJob = true)
        }
      }

    case AddBinaryAnnotation(spanId, key, value, valueType) =>
      lookup(spanId) foreach { spanInt =>
        val a = new thrift.BinaryAnnotation(key, value, valueType)
        a.set_host(endpointFor(spanId))
        spanInt.add_to_binary_annotations(a)
      }

    case CreateChildSpan(spanId, parentId) =>
      lookup(spanId) match {
        case Some(parentSpan) =>
          createSpan(spanId, Some(parentSpan.id), parentSpan.trace_id)
        case _ =>
          None
      }

    case SetSampleRate(sampleRate) =>
      this.sampleRate = sampleRate
  }

  override def postStop(): Unit = {
    spans.keys.foreach(id =>
      enqueue(id, cancelJob = true)
    )
    send()
    super.postStop()
  }

  private def adjustedMicroTime(nanoTime: Long): Long =
    microTimeAdjustment + nanoTime / 1000

  @inline
  private def lookup(id: Long): Option[thrift.Span] =
    spans.get(id)

  private def createSpan(id: Long, parentId: Option[Long], traceId: Long, name: String = null,
                         annotations: util.List[thrift.Annotation] = new util.ArrayList(),
                         binaryAnnotations: util.List[thrift.BinaryAnnotation] = new util.ArrayList()): Unit = {
    sendJobs.put(id, context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(id, cancelJob = false)))
    val span = new thrift.Span(traceId, name, id, annotations, binaryAnnotations)
    parentId.foreach(span.set_parent_id)
    spans.put(id, span)
  }

  private def enqueue(id: Long, cancelJob: Boolean): Unit = {
    sendJobs.remove(id).foreach(job => if (cancelJob) job.cancel())
    spans.remove(id).foreach(span => sendQueue.append(span))
  }

  private def send(): Future[thrift.ResultCode] = {
    import scala.collection.JavaConversions._
    if (!sendQueue.isEmpty) {
      val messages = sendQueue.map(spanToLogEntry)
      sendQueue.clear()
      val p = promise[thrift.ResultCode]()
      client.Log(messages, new AsyncMethodCallback[thrift.Scribe.AsyncClient.Log_call] {
        override def onError(e: Exception): Unit =
          p.failure(e)
        override def onComplete(r: thrift.Scribe.AsyncClient.Log_call): Unit =
          p.success(r.getResult)
      })
      p.future
    } else {
      Future(thrift.ResultCode.OK)
    }
  }

  private def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    val buffer = new TMemoryBuffer(1024)
    spanInt.write(protocolFactory.getProtocol(buffer))
    val thriftBytes = buffer.getArray.take(buffer.length)
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    new thrift.LogEntry("zipkin", encodedSpan)
  }

  private def endpointFor(spanId: Long): thrift.Endpoint =
    endpoints.get(spanId).getOrElse(unknownEndpoint)

}
