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

import java.io.{ PrintWriter, StringWriter }
import java.nio.ByteBuffer
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import scala.collection.mutable
import scala.util.Random

import akka.actor._
import akka.agent.Agent
import akka.stream.actor.{ ActorSubscriber, ActorPublisher }
import org.apache.thrift.transport.{ TSocket, TFramedTransport }

import com.github.levkhomich.akka.tracing.actor.{ SpanHolder, SpanSubmitter }

/**
 * Tracer instance providing trace related methods.
 * @param system parent actor system
 */
class TracingExtensionImpl(system: ActorSystem) extends Extension {

  import TracingExtension._
  import SpanHolder._

  private[this] val enabled = new AtomicBoolean(system.settings.config.getBoolean(AkkaTracingEnabled))
  private[this] val msgCounter = new AtomicLong()
  private[this] val sampleRate = system.settings.config.getInt(AkkaTracingSampleRate)

  private[this] val spans = Agent(mutable.Map[Long, thrift.Span]())(system.dispatcher)

  private[tracing] val holder = {
    val config = system.settings.config

    if (config.hasPath(AkkaTracingHost) && isEnabled) {
      val transport = new TFramedTransport(
        new TSocket(config.getString(AkkaTracingHost), config.getInt(AkkaTracingPort))
      )
      system.actorOf(Props({
        val holder = new SpanHolder(spans)
        val maxSpansPerSecond = config.getInt(AkkaTracingMaxSpansPerSecond)
        require(maxSpansPerSecond > 0, s"invalid $AkkaTracingMaxSpansPerSecond = $maxSpansPerSecond (should be > 0)")
        val submitter = holder.context.actorOf(Props(classOf[SpanSubmitter], transport, maxSpansPerSecond), "spanSubmitter")
        ActorPublisher(holder.self).subscribe(ActorSubscriber(submitter))
        holder
      }), "spanHolder")
    } else {
      system.actorOf(Props.empty)
    }
  }

  @inline
  private[tracing] def isEnabled: Boolean =
    enabled.get()

  private[tracing] def markCollectorAsUnavailable(): Unit =
    enabled.set(false)

  private[tracing] def markCollectorAsAvailable(): Unit =
    if (!enabled.get()) enabled.set(system.settings.config.getBoolean(AkkaTracingEnabled))

  /**
   * Records string message and attaches it to timeline.
   * @param ts traced message
   * @param msg recorded string
   */
  def record(ts: BaseTracingSupport, msg: String): Unit =
    record(ts.tracingId, msg)

  /**
   * Records exception's stack trace to trace.
   * @param ts traced message
   * @param e recorded exception
   */
  def record(ts: BaseTracingSupport, e: Throwable): Unit =
    record(ts, getStackTrace(e))

  private[tracing] def record(tracingId: Long, msg: String): Unit =
    if (isEnabled)
      holder ! AddAnnotation(tracingId, System.nanoTime, msg)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: String): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.wrap(value.getBytes), thrift.AnnotationType.STRING)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Int): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.allocate(4).putInt(0, value), thrift.AnnotationType.I32)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Long): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.allocate(8).putLong(0, value), thrift.AnnotationType.I64)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Boolean): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.wrap(Array[Byte](if (value) 1 else 0)), thrift.AnnotationType.BOOL)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Double): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.allocate(8).putDouble(0, value), thrift.AnnotationType.DOUBLE)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Short): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.allocate(2).putShort(0, value), thrift.AnnotationType.I16)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Array[Byte]): Unit =
    addBinaryAnnotation(ts.tracingId, key, ByteBuffer.wrap(value), thrift.AnnotationType.BYTES)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: ByteBuffer): Unit =
    addBinaryAnnotation(ts.tracingId, key, value, thrift.AnnotationType.BYTES)

  /**
   * Enables message tracing, names (rpc name is assumed to be message's class name)
   * and samples it. After sampling any nth message (defined by akka.tracing.sample-rate setting)
   * will be actually traced.
   * @param ts traced message
   * @param service service name
   */
  def sample(ts: BaseTracingSupport, service: String): Unit =
    sample(ts, Random.nextLong, None, Random.nextLong, service)

  private[tracing] def sample(ts: BaseTracingSupport, spanId: Long, parentId: Option[Long], traceId: Long, service: String): Unit =
    sample(ts.tracingId, spanId, parentId, traceId, service, ts.spanName)

  private[tracing] def sample(tracingId: Long, service: String, rpc: String): Unit =
    sample(tracingId, Random.nextLong, None, Random.nextLong, service, rpc)

  private[tracing] def sample(tracingId: Long, spanId: Long, parentId: Option[Long], traceId: Long, service: String, rpc: String): Unit =
    if (isEnabled && msgCounter.incrementAndGet() % sampleRate == 0) {
      holder ! Sample(tracingId, spanId, parentId, traceId, service, rpc, System.nanoTime)
    }

  /**
   * Enables message tracing, names (rpc name is assumed to be message's class name)
   * and samples it. Message will be traced ignoring akka.tracing.sample-rate setting.
   * @param ts traced message
   * @param service service name
   */
  def forcedSample(ts: BaseTracingSupport, service: String): Unit =
    forcedSample(ts.tracingId, Random.nextLong, None, Random.nextLong, service, ts.spanName)

  private[tracing] def forcedSample(ts: BaseTracingSupport, spanId: Long, parentId: Option[Long], traceId: Long, service: String): Unit =
    forcedSample(ts.tracingId, spanId, parentId, traceId, service, ts.spanName)

  private[tracing] def forcedSample(tracingId: Long, spanId: Long, parentId: Option[Long], traceId: Long, service: String, rpc: String): Unit =
    if (isEnabled)
      holder ! Sample(tracingId, spanId, parentId, traceId, service, rpc, System.nanoTime)

  /**
   * Marks request processing start.
   * @param ts traced message
   * @param service service name
   */
  def start(ts: BaseTracingSupport, service: String): Unit =
    if (isEnabled)
      holder ! Receive(ts.tracingId, service, ts.spanName, System.nanoTime)

  def finish(ts: BaseTracingSupport): Unit =
    addAnnotation(ts.tracingId, thrift.zipkinConstants.SERVER_SEND, send = true)

  def finishChildRequest(ts: BaseTracingSupport): Unit =
    addAnnotation(ts.tracingId, thrift.zipkinConstants.CLIENT_RECV, send = true)

  def submitSpans(spans: TraversableOnce[thrift.Span]): Unit =
    if (isEnabled)
      holder ! SubmitSpans(spans)

  private[tracing] def addAnnotation(tracingId: Long, value: String, send: Boolean = false): Unit =
    if (isEnabled)
      holder ! AddAnnotation(tracingId, System.nanoTime, value)

  private[tracing] def addBinaryAnnotation(tracingId: Long, key: String, value: ByteBuffer,
                                           valueType: thrift.AnnotationType): Unit =
    if (isEnabled)
      holder ! AddBinaryAnnotation(tracingId, key, value, valueType)

  private[tracing] def createChildSpan(tracingId: Long, parentTracingId: Long, spanName: String): Unit =
    if (isEnabled)
      holder ! CreateChildSpan(tracingId, parentTracingId, spanName)

  private[tracing] def getId(tracingId: Long): Option[Span] = {
    spans.get.get(tracingId) map { spanInt =>
      Span(spanInt.get_trace_id, spanInt.get_id, Option(spanInt.get_parent_id), false)
    }
  }
}

/**
 * Tracing extension. Provides tracer for actors mixed with [[com.github.levkhomich.akka.tracing.ActorTracing]].
 *
 * Configuration parameters:
 * - akka.tracing.host - Scribe or Zipkin collector host
 * - akka.tracing.port - Scribe or Zipkin collector port (9410 by default)
 * - akka.tracing.sample-rate - trace sample rate, means that every nth message will be sampled
 * - akka.tracing.enabled - defaults to true, can be used to disable tracing
 *
 */
object TracingExtension extends ExtensionId[TracingExtensionImpl] with ExtensionIdProvider {

  private[tracing] val AkkaTracingHost = "akka.tracing.host"
  private[tracing] val AkkaTracingPort = "akka.tracing.port"
  private[tracing] val AkkaTracingSampleRate = "akka.tracing.sample-rate"
  private[tracing] val AkkaTracingEnabled = "akka.tracing.enabled"
  private[tracing] val AkkaTracingMaxSpansPerSecond = "akka.tracing.max-spans-per-second"

  override def lookup(): this.type =
    TracingExtension

  override def createExtension(system: ExtendedActorSystem): TracingExtensionImpl =
    new TracingExtensionImpl(system)

  override def get(system: ActorSystem): TracingExtensionImpl =
    super.get(system)

  private[tracing] def getStackTrace(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    e.getClass.getCanonicalName + ": " + sw.toString
  }

}
