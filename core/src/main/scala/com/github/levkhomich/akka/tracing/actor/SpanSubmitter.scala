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

import java.net.{ ConnectException, NoRouteToHostException, SocketException }
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.control.ControlThrowable
import scala.util.{ Failure, Success, Try }

import akka.actor.ActorLogging
import akka.stream.actor.{ ActorSubscriber, ActorSubscriberMessage, RequestStrategy }
import org.apache.thrift.TApplicationException
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{ TTransport, TTransportException }

import com.github.levkhomich.akka.tracing.thrift.TReusableTransport
import com.github.levkhomich.akka.tracing.{ TracingExtension, thrift }

private object SpanSubmitter {
  private case object SendEnqueued
}

/**
 * Internal API
 */
private[tracing] class SpanSubmitter(transport: TTransport, maxSpansPerSecond: Int) extends ActorSubscriber with ActorLogging {

  import SpanSubmitter._

  // buffer containing log entries ready to be sent
  private[this] val logEntries = mutable.UnrolledBuffer[thrift.LogEntry]()
  private[this] val highWatermark = maxSpansPerSecond

  // span submit duration stats
  private[this] var expectedSubmitDurationPerSpan = 0L // nanoseconds
  private[this] var accumulatedSubmitDuration = 0L // nanoseconds
  private[this] var accumulatedSpansSent = 0L

  private[this] val minSubmitDelay = 50
  private[this] val reconnectInterval = 10

  private[this] val protocolFactory = new TBinaryProtocol.Factory()
  private[this] val thriftBuffer = new TReusableTransport()

  private[this] val client = new thrift.Scribe.Client(new TBinaryProtocol(transport))

  override protected def requestStrategy = new RequestStrategy {
    def requestDemand(remainingRequested: Int): Int =
      highWatermark - logEntries.size - remainingRequested
  }

  override def receive: Receive = {
    case ActorSubscriberMessage.OnNext(span: thrift.Span) =>
      logEntries.append(spanToLogEntry(span))
    case ActorSubscriberMessage.OnError(ex) =>
      flush()
    case ActorSubscriberMessage.OnComplete =>
      flush()
    case SendEnqueued =>
      send()
      scheduleNextSubmit()
  }

  override def preStart(): Unit = {
    scheduleNextSubmit()
  }

  override def postStop(): Unit = {
    flush()
    super.postStop()
  }

  private[this] def flush(): Unit = {
    if (logEntries.nonEmpty) {
      log.info(s"Flushing ${logEntries.size} spans")
      send()
    }
    if (transport.isOpen)
      transport.close()
  }

  private[this] def send(): Unit = {
    import scala.collection.JavaConversions._
    val spanCount = logEntries.size
    if (spanCount > 0) {
      val startTime = System.nanoTime
      Try {
        if (!transport.isOpen) {
          transport.open()
          TracingExtension(context.system).markCollectorAsAvailable()
          log.info("Successfully connected to Zipkin collector.")
        }
        client.Log(logEntries)
      } match {
        case Success(thrift.ResultCode.OK) =>
          updateSubmitDurationStats(spanCount, System.nanoTime - startTime)
          logEntries.remove(0, spanCount)
        case Success(thrift.ResultCode.TRY_LATER) =>
          log.warning(s"Zipkin collector busy. Failed to send $spanCount spans.")
        case Success(response) =>
          log.warning(s"Unexpected Zipkin response $response. Failed to send $spanCount spans.")
        case Failure(e: ControlThrowable) =>
          throw e
        case Failure(e) =>
          log.warning(getSubmitErrorMessage(e) + s". Failed to send $spanCount spans.")
          // reconnect next time
          transport.close()
          TracingExtension(context.system).markCollectorAsUnavailable()
      }
    }
  }

  /**
   * Calculates delay before next submit considering expected workload and backpressure settings.
   */
  private[this] def getNextSubmitDelay: Int = {
    // percent of time spent on span submitting
    val sendTimeFactor = expectedSubmitDurationPerSpan * maxSpansPerSecond / 10000000
    val saturationTime = highWatermark * 1000 / maxSpansPerSecond
    val delay = saturationTime * (100 - sendTimeFactor) / (100 + sendTimeFactor)
    delay.toInt max minSubmitDelay
  }

  /**
   * Updates submit duration stats used to schedule next span submit.
   * @param spansSent number of spans sent previously
   * @param submitDuration time previous submit took
   */
  private[this] def updateSubmitDurationStats(spansSent: Int, submitDuration: Long): Unit = {
    accumulatedSubmitDuration += submitDuration
    accumulatedSpansSent += spansSent
    expectedSubmitDurationPerSpan = accumulatedSubmitDuration / accumulatedSpansSent
    if (accumulatedSpansSent > maxSpansPerSecond) {
      accumulatedSubmitDuration /= 2
      accumulatedSpansSent /= 2
    }
  }

  private[this] def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    spanInt.write(protocolFactory.getProtocol(thriftBuffer))
    val thriftBytes = thriftBuffer.getArray().take(thriftBuffer.length)
    thriftBuffer.reset()
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    new thrift.LogEntry("zipkin", encodedSpan)
  }

  private[this] def getSubmitErrorMessage(e: Throwable): String =
    e match {
      case te: TTransportException =>
        te.getCause match {
          case null if te.getMessage == null =>
            "Thrift transport error"
          case null =>
            "Thrift transport error: " + te.getMessage
          case e: ConnectException =>
            "Can't connect to Zipkin: " + e.getMessage
          case e: NoRouteToHostException =>
            "No route to Zipkin: " + e.getMessage
          case e: SocketException =>
            "Socket error: " + e.getMessage
          case t: Throwable =>
            "Unknown transport error: " + TracingExtension.getStackTrace(t)
        }
      case t: TApplicationException =>
        "Thrift client error: " + t.getMessage
      case t: Throwable =>
        "Oh, look! We have an unknown error here: " + TracingExtension.getStackTrace(t)
    }

  private[this] def scheduleNextSubmit(): Unit = {
    import context.dispatcher
    if (TracingExtension(context.system).isEnabled) {
      context.system.scheduler.scheduleOnce(getNextSubmitDelay.milliseconds, self, SendEnqueued)
    } else {
      log.info(s"Trying to reconnect in $reconnectInterval seconds")
      context.system.scheduler.scheduleOnce(reconnectInterval.seconds, self, SendEnqueued)
    }
  }

}
