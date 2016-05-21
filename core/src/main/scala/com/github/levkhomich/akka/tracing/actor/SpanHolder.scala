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

import akka.actor.{ ActorLogging, Cancellable }
import akka.stream.actor.{ ActorPublisher, ActorPublisherMessage }

import com.github.levkhomich.akka.tracing.{ TracingAnnotation, TracingAnnotations, SpanMetadata, thrift }

private[tracing] object SpanHolder {
  final case class Enqueue(tracingId: Long, cancelJob: Boolean)

  final case class Sample(initialAnnotation: TracingAnnotation, tracingId: Long, metadata: SpanMetadata,
                          serviceName: String, rpcName: String, timestamp: Long)
  final case class Receive(tracingId: Long, serviceName: String, rpcName: String, timestamp: Long)
  final case class AddAnnotation(tracingId: Long, timestamp: Long, msg: String)
  final case class AddBinaryAnnotation(tracingId: Long, key: String, value: ByteBuffer, valueType: thrift.AnnotationType)
  final case class CreateFromMetadata(tracingId: Long, metadata: SpanMetadata, spanName: String)
  final case class SubmitSpans(spans: TraversableOnce[thrift.Span])
  final case class ImportMetadata(tracingId: Long, span: SpanMetadata,
                                  serviceName: String, rpcName: String, timestamp: Long)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder() extends ActorPublisher[thrift.Span] with ActorLogging {

  import com.github.levkhomich.akka.tracing.actor.SpanHolder._

  // map of spanId -> span for uncompleted traces
  private[this] val spans = mutable.Map[Long, thrift.Span]()

  // scheduler jobs which send incomplete traces by timeout
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()

  private[this] val endpoints = mutable.Map[Long, thrift.Endpoint]()
  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt
  private[this] val unknownEndpoint = new thrift.Endpoint(localAddress, 0, "unknown")

  private[this] val microTimeAdjustment = System.currentTimeMillis * 1000 - System.nanoTime / 1000

  override def receive: Receive = {
    case m @ Sample(initialAnnotation, tracingId, metadata, serviceName, rpcName, timestamp) =>
      if (!spans.contains(tracingId)) {
        val annotations = toAnnotationList(initialAnnotation, tracingId, serviceName, timestamp)
        createSpan(tracingId, metadata.spanId, metadata.parentId, metadata.traceId, rpcName, annotations)
      }

    case m @ ImportMetadata(tracingId, metadata, serviceName, rpcName, timestamp) =>
      newEndpoint(tracingId, serviceName)
      createSpan(tracingId, metadata.spanId, metadata.parentId, metadata.traceId, rpcName)

    case Receive(tracingId, serviceName, rpcName, timestamp) =>
      spans.get(tracingId).foreach { span =>
        if (span.get_annotations_size() == 0) {
          span.set_annotations(toAnnotationList(TracingAnnotations.ServerReceived, tracingId, serviceName, timestamp))
        }
      }

    case Enqueue(spanId, cancelJob) =>
      enqueue(spanId, cancelJob)

    case AddAnnotation(tracingId, timestamp, msg) =>
      spans.get(tracingId).foreach { spanInt =>
        val a = new thrift.Annotation(adjustedMicroTime(timestamp), msg)
        a.set_host(endpointFor(tracingId))
        spanInt.add_to_annotations(a)
        if (a.value == TracingAnnotations.ServerSend.text || a.value == TracingAnnotations.ClientReceived.text) {
          enqueue(tracingId, cancelJob = true)
        }
      }

    case AddBinaryAnnotation(tracingId, key, value, valueType) =>
      spans.get(tracingId).foreach { spanInt =>
        val a = new thrift.BinaryAnnotation(key, value, valueType)
        a.set_host(endpointFor(tracingId))
        spanInt.add_to_binary_annotations(a)
      }

    case m @ CreateFromMetadata(tracingId, metadata, spanName) =>
      createSpan(tracingId, metadata.spanId, metadata.parentId, metadata.traceId, spanName)

    case SubmitSpans(spans) =>
      spans.foreach { span =>
        if (isActive && totalDemand > 0)
          onNext(span)
      }

    case _: ActorPublisherMessage => // default behaviour is enough
  }

  private[this] def adjustedMicroTime(nanoTime: Long): Long =
    microTimeAdjustment + nanoTime / 1000

  private[this] def createSpan(tracingId: Long, spanId: Long, parentId: Option[Long], traceId: Long, name: String,
                               annotations: util.List[thrift.Annotation] = null): Unit = {
    val span = new thrift.Span(traceId, name, spanId, annotations, null)
    parentId.foreach(span.set_parent_id)
    spans.put(tracingId, span)

    import context.dispatcher
    context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(tracingId, cancelJob = false))
  }

  private[this] def enqueue(tracingId: Long, cancelJob: Boolean): Unit = {
    val ready = isActive && totalDemand > 0
    if (ready || !cancelJob) {
      spans.remove(tracingId).foreach(span => if (ready) onNext(span))
      sendJobs.remove(tracingId).foreach(job => if (cancelJob) job.cancel())
      endpoints.remove(tracingId)
    }
  }

  private[this] def endpointFor(tracingId: Long): thrift.Endpoint =
    endpoints.getOrElse(tracingId, unknownEndpoint)

  private[this] def newEndpoint(tracingId: Long, serviceName: String): thrift.Endpoint = {
    val endpoint = new thrift.Endpoint(localAddress, 0, serviceName)
    endpoints.put(tracingId, endpoint)
    endpoint
  }

  private[this] def toAnnotationList(annotation: TracingAnnotation, tracingId: Long, serviceName: String, nanoTime: Long): util.ArrayList[thrift.Annotation] = {
    val endpoint = newEndpoint(tracingId, serviceName)
    val serverRecvAnn = new thrift.Annotation(adjustedMicroTime(nanoTime), annotation.text)
    serverRecvAnn.set_host(endpoint)
    val annotations = new util.ArrayList[thrift.Annotation]()
    annotations.add(serverRecvAnn)
    annotations
  }

}
