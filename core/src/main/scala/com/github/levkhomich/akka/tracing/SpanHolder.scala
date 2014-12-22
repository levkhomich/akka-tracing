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

import java.net.{SocketException, NoRouteToHostException, ConnectException, InetAddress}
import java.nio.ByteBuffer
import java.util
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Try}
import scala.util.control.ControlThrowable

import akka.actor.{Actor, ActorLogging, Cancellable}
import org.apache.thrift.TApplicationException
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TTransportException, TTransport}

import com.github.levkhomich.akka.tracing.thrift.TReusableTransport

private[tracing] object SpanHolderInternalAction {
  final case class Sample(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class Receive(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class Enqueue(spanId: Long, cancelJob: Boolean)
  case object SendEnqueued
  final case class AddAnnotation(spanId: Long, timestamp: Long, msg: String)
  final case class AddBinaryAnnotation(spanId: Long, key: String, value: ByteBuffer, valueType: thrift.AnnotationType)
  final case class CreateChildSpan(spanId: Long, parentId: Long, optTraceId: Option[Long], spanName: String)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder(transport: TTransport) extends Actor with ActorLogging {

  import SpanHolderInternalAction._

  // map of spanId -> span for uncompleted traces
  private[this] val spans = mutable.Map[Long, thrift.Span]()
  // scheduler jobs which send incomplete traces by timeout
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()
  // next submission batch
  private[this] val nextBatch = mutable.UnrolledBuffer[thrift.Span]()

  // buffer for submitted spans, which should be resent in case of connectivity problems
  private[this] var submittedSpans: mutable.Buffer[thrift.LogEntry] = mutable.Buffer.empty
  // buffer's size limit
  private[this] val maxSubmissionBufferSize = 1000

  private[this] val protocolFactory = new TBinaryProtocol.Factory()
  private[this] val thriftBuffer = new TReusableTransport()

  private[this] val endpoints = mutable.Map[Long, thrift.Endpoint]()
  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt
  private[this] val unknownEndpoint = new thrift.Endpoint(localAddress, 0, "unknown")

  private[this] val microTimeAdjustment = System.currentTimeMillis * 1000 - System.nanoTime / 1000

  private[this] val client = new thrift.Scribe.Client(new TBinaryProtocol(transport))

  scheduleNextBatch()

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

    case CreateChildSpan(spanId, parentId, optTraceId, spanName) =>
      optTraceId match {
        case Some(traceId) =>
          createSpan(spanId, Some(parentId), traceId, spanName)
        case _ =>
          // do not sample if parent was not sampled
          None
      }
  }

  override def postStop(): Unit = {
    import scala.collection.JavaConversions._
    // we don't want to resend at this point
    submittedSpans.clear()
    spans.keys.foreach(id =>
      enqueue(id, cancelJob = true)
    )
    Try {
      client.Log(nextBatch.map(spanToLogEntry))
      if (transport.isOpen)
        transport.close()
    } recover {
      case e =>
        handleSubmissionError(e)
        log.error(s"Zipkin collector is unavailable. Failed to send ${nextBatch.size} spans during postStop.")
    }
    super.postStop()
  }

  private[this] def adjustedMicroTime(nanoTime: Long): Long =
    microTimeAdjustment + nanoTime / 1000

  @inline
  private[this] def lookup(id: Long): Option[thrift.Span] =
    spans.get(id)

  private[this] def createSpan(id: Long, parentId: Option[Long], traceId: Long, name: String,
                         annotations: util.List[thrift.Annotation] = null): Unit = {
    sendJobs.put(id, context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(id, cancelJob = false)))
    val span = new thrift.Span(traceId, name, id, annotations, null)
    parentId.foreach(span.set_parent_id)
    spans.put(id, span)
  }

  private[this] def enqueue(id: Long, cancelJob: Boolean): Unit = {
    sendJobs.remove(id).foreach(job => if (cancelJob) job.cancel())
    spans.remove(id).foreach(span => nextBatch.append(span))
    endpoints.remove(id)
  }

  private[this] def send(): Unit = {
    import scala.collection.JavaConversions._
    if (!nextBatch.isEmpty) {
      submittedSpans ++= nextBatch.map(spanToLogEntry)
      nextBatch.clear()
    }
    if (!submittedSpans.isEmpty) {
      Future {
        if (!transport.isOpen) {
          transport.open()
          TracingExtension(context.system).markCollectorAsAvailable()
          log.warning("Successfully connected to Zipkin collector.")
        }
        client.Log(submittedSpans)
      } recover {
        case e =>
          handleSubmissionError(e)
          // reconnect next time
          transport.close()
          thrift.ResultCode.TRY_LATER
      } onComplete {
        case Success(thrift.ResultCode.OK) =>
          submittedSpans.clear()
          scheduleNextBatch()

        case _ =>
          log.warning(s"Zipkin collector unavailable. Failed to send ${submittedSpans.size} spans.")
          limitSubmittedSpansSize()
          scheduleNextBatch()
      }
    } else
      scheduleNextBatch()
  }

  private[this] def limitSubmittedSpansSize(): Unit = {
    val delta = submittedSpans.size - maxSubmissionBufferSize
    if (delta > 0) {
      log.error(s"Dropping $delta spans because of maxSubmissionBufferSize policy.")
      submittedSpans = submittedSpans.takeRight(maxSubmissionBufferSize)
    }
  }

  private[this] def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    spanInt.write(protocolFactory.getProtocol(thriftBuffer))
    val thriftBytes = thriftBuffer.getArray.take(thriftBuffer.length)
    thriftBuffer.reset()
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    new thrift.LogEntry("zipkin", encodedSpan)
  }

  private[this] def endpointFor(spanId: Long): thrift.Endpoint =
    endpoints.get(spanId).getOrElse(unknownEndpoint)

  private[this] def recvAnnotationList(nanoTime: Long, endpont: thrift.Endpoint): util.ArrayList[thrift.Annotation] = {
    val serverRecvAnn = new thrift.Annotation(adjustedMicroTime(nanoTime), thrift.zipkinConstants.SERVER_RECV)
    serverRecvAnn.set_host(endpont)
    val annotations = new util.ArrayList[thrift.Annotation]()
    annotations.add(serverRecvAnn)
    annotations
  }

  private[this] def handleSubmissionError(e: Throwable): Unit =
    e match {
      case te: TTransportException =>
        te.getCause match {
          case null =>
            log.error("Thrift transport error: " + te.getMessage)
          case e: ConnectException =>
            log.error("Can't connect to Zipkin: " + e.getMessage)
          case e: NoRouteToHostException =>
            log.error("No route to Zipkin: " + e.getMessage)
          case e: SocketException =>
            log.error("Socket error: " + TracingExtension.getStackTrace(e))
          case t: Throwable =>
            log.error("Unknown transport error: " + TracingExtension.getStackTrace(t))
        }
        TracingExtension(context.system).markCollectorAsUnavailable()
      case t: TApplicationException =>
        log.error("Thrift client error: " + t.getMessage)
      case ct: ControlThrowable =>
        throw ct
      case t: Throwable =>
        log.error("Oh, look! We have an unknown error here: " + TracingExtension.getStackTrace(t))
    }

  private[this] def scheduleNextBatch(): Unit =
    if (TracingExtension(context.system).enabled) {
      context.system.scheduler.scheduleOnce(2.seconds, self, SendEnqueued)
    } else {
      log.error("Trying to reconnect in 10 seconds")
      context.system.scheduler.scheduleOnce(10.seconds, self, SendEnqueued)
    }


}
