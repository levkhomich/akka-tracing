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

import java.io.{PrintWriter, StringWriter}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import scala.util.Random

import akka.actor._
import org.apache.thrift.transport.{TSocket, TFramedTransport}

/**
 * Tracer instance providing trace related methods.
 * @param system parent actor system
 */
class TracingExtensionImpl(system: ActorSystem) extends Extension {

  import TracingExtension._
  import SpanHolderInternalAction._

  private[tracing] var enabled = system.settings.config.getBoolean(AkkaTracingEnabled)
  private[this] val msgCounter = new AtomicLong()
  @volatile private[this] var sampleRate = system.settings.config.getInt(AkkaTracingSampleRate)

  private[tracing] val holder = {
    val config = system.settings.config

    if (config.hasPath(AkkaTracingHost) && enabled) {
      val transport = new TFramedTransport(
        new TSocket(config.getString(AkkaTracingHost), config.getInt(AkkaTracingPort))
      )
      system.actorOf(Props(classOf[SpanHolder], transport), "spanHolder")
    } else {
      system.actorOf(Props.empty)
    }
  }

  private[tracing] def markCollectorAsUnavailable(): Unit =
    enabled = false

  private[tracing] def markCollectorAsAvailable(): Unit =
    if (!enabled) enabled = system.settings.config.getBoolean(AkkaTracingEnabled)

  /**
   * Records string message and attaches it to timeline.
   * @param ts traced message
   * @param msg recorded string
   */
  def record(ts: BaseTracingSupport, msg: String): Unit =
    if (ts.isSampled)
      record(ts.spanId, msg)

  /**
   * Records exception's stack trace to trace.
   * @param ts traced message
   * @param e recorded exception
   */
  def record(ts: BaseTracingSupport, e: Throwable): Unit =
    record(ts, getStackTrace(e))

  private[tracing] def record(spanId: Long, msg: String): Unit =
    if (enabled)
      holder ! AddAnnotation(spanId, System.nanoTime, msg)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: String): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.wrap(value.getBytes), thrift.AnnotationType.STRING)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Int): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.allocate(4).putInt(0, value), thrift.AnnotationType.I32)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Long): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.allocate(8).putLong(0, value), thrift.AnnotationType.I64)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Boolean): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.wrap(Array[Byte](if (value) 1 else 0)), thrift.AnnotationType.BOOL)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Double): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.allocate(8).putDouble(0, value), thrift.AnnotationType.DOUBLE)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Short): Unit =
    addBinaryAnnotation(ts, key, ByteBuffer.allocate(2).putShort(0, value), thrift.AnnotationType.I16)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Array[Byte]): Unit =    
    addBinaryAnnotation(ts, key, ByteBuffer.wrap(value), thrift.AnnotationType.BYTES)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: ByteBuffer): Unit =
    addBinaryAnnotation(ts, key, value, thrift.AnnotationType.BYTES)

  /**
   * Enables message tracing, names and samples it. After sampling any nth message
   * (defined by akka.tracing.sample-rate setting) will be actually traced.
   * @param ts traced message
   * @param service service name
   * @param rpc RPC name
   */
  def sample(ts: BaseTracingSupport, service: String, rpc: String): Unit =
    if (enabled && msgCounter.incrementAndGet() % sampleRate == 0) {
      ts.sample()
      holder ! Sample(ts, service, rpc, System.nanoTime)
    }

  /**
   * Enables message tracing, names (rpc name is assumed to be message's class name)
   * and samples it. After sampling any nth message (defined by akka.tracing.sample-rate setting)
   * will be actually traced.
   * @param ts traced message
   * @param service service name
   */
  def sample(ts: BaseTracingSupport, service: String): Unit =
    sample(ts, service, ts.getClass.getSimpleName)

  def finish(ts: BaseTracingSupport): Unit =
    addAnnotation(ts, thrift.zipkinConstants.SERVER_SEND, send = true)

  private def addAnnotation(ts: BaseTracingSupport, value: String, send: Boolean = false): Unit =
    if (enabled && ts.isSampled)
      holder ! AddAnnotation(ts.spanId, System.nanoTime, value)

  private def addBinaryAnnotation(ts: BaseTracingSupport, key: String, value: ByteBuffer,
                                  valueType: thrift.AnnotationType): Unit =
    if (enabled && ts.isSampled)
      holder ! AddBinaryAnnotation(ts.spanId, key, value, valueType)

  private[tracing] def createChildSpan(spanId: Long, ts: BaseTracingSupport): Unit =
    if (enabled && ts.isSampled)
      holder ! CreateChildSpan(spanId, ts.spanId, ts.traceId)

  def setSampleRate(newSampleRate: Int): Unit =
    sampleRate = newSampleRate
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

  override def lookup() =
    TracingExtension

  override def createExtension(system: ExtendedActorSystem) =
    new TracingExtensionImpl(system)

  override def get(system: ActorSystem): TracingExtensionImpl =
    super.get(system)

  private[tracing] def getStackTrace(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    e.getClass.getCanonicalName + ": " + sw.toString
  }

}
