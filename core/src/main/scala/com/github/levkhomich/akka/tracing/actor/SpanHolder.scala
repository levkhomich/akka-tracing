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

package com.github.levkhomich.akka.tracing.actor

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

import akka.actor.{ Props, Actor, ActorLogging, Cancellable }
import org.apache.thrift.transport.TTransport

import com.github.levkhomich.akka.tracing.{ BaseTracingSupport, thrift }

private[tracing] object SpanHolder {
  private final case class Enqueue(spanId: Long, cancelJob: Boolean)

  final case class Sample(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class Receive(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class AddAnnotation(spanId: Long, timestamp: Long, msg: String)
  final case class AddBinaryAnnotation(spanId: Long, key: String, value: ByteBuffer, valueType: thrift.AnnotationType)
  final case class CreateChildSpan(spanId: Long, parentId: Long, optTraceId: Option[Long], spanName: String)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder(transport: TTransport) extends Actor with ActorLogging {

  import com.github.levkhomich.akka.tracing.actor.SpanHolder._

  private[this] val submitter = context.system.actorOf(Props(classOf[SpanSubmitter], transport), "spanSubmitter")

  // map of spanId -> span for uncompleted traces
  private[this] val spans = mutable.Map[Long, thrift.Span]()
  // scheduler jobs which send incomplete traces by timeout
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()

  private[this] val endpoints = mutable.Map[Long, thrift.Endpoint]()
  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt
  private[this] val unknownEndpoint = new thrift.Endpoint(localAddress, 0, "unknown")

  private[this] val microTimeAdjustment = System.currentTimeMillis * 1000 - System.nanoTime / 1000

  override def receive: Receive = {
    case Sample(ts, serviceName, rpcName, timestamp) =>
      lookup(ts.$spanId) match {
        case None =>
          val endpoint = new thrift.Endpoint(localAddress, 0, serviceName)
          val annotations = recvAnnotationList(timestamp, endpoint)
          createSpan(ts.$spanId, ts.$parentId, ts.$traceId.get, rpcName, annotations)
          endpoints.put(ts.$spanId, endpoint)
        case _ =>
      }

    case Receive(ts, serviceName, rpcName, timestamp) =>
      lookup(ts.$spanId) match {
        case Some(span) if span.get_annotations_size() == 0 =>
          val endpoint = new thrift.Endpoint(localAddress, 0, serviceName)
          span.set_annotations(recvAnnotationList(timestamp, endpoint))
          endpoints.put(ts.$spanId, endpoint)
        case _ =>
      }

    case Enqueue(spanId, cancelJob) =>
      enqueue(spanId, cancelJob)

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

    case CreateChildSpan(spanId, parentId, maybeTraceId, spanName) =>
      // do not sample if parent was not sampled
      maybeTraceId.foreach(
        createSpan(spanId, Some(parentId), _, spanName)
      )
  }

  override def postStop(): Unit = {
    spans.keys.foreach(id =>
      enqueue(id, cancelJob = true)
    )
    super.postStop()
  }

  private[this] def adjustedMicroTime(nanoTime: Long): Long =
    microTimeAdjustment + nanoTime / 1000

  @inline
  private[this] def lookup(id: Long): Option[thrift.Span] =
    spans.get(id)

  private[this] def createSpan(id: Long, parentId: Option[Long], traceId: Long, name: String,
                               annotations: util.List[thrift.Annotation] = null): Unit = {
    import context.dispatcher
    sendJobs.put(id, context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(id, cancelJob = false)))
    val span = new thrift.Span(traceId, name, id, annotations, null)
    parentId.foreach(span.set_parent_id)
    spans.put(id, span)
  }

  private[this] def enqueue(id: Long, cancelJob: Boolean): Unit = {
    sendJobs.remove(id).foreach(job => if (cancelJob) job.cancel())
    spans.remove(id).foreach(submitter ! SpanSubmitter.Enqueue(_))
    endpoints.remove(id)
  }

  private[this] def endpointFor(spanId: Long): thrift.Endpoint =
    endpoints.getOrElse(spanId, unknownEndpoint)

  private[this] def recvAnnotationList(nanoTime: Long, endpont: thrift.Endpoint): util.ArrayList[thrift.Annotation] = {
    val serverRecvAnn = new thrift.Annotation(adjustedMicroTime(nanoTime), thrift.zipkinConstants.SERVER_RECV)
    serverRecvAnn.set_host(endpont)
    val annotations = new util.ArrayList[thrift.Annotation]()
    annotations.add(serverRecvAnn)
    annotations
  }

}
