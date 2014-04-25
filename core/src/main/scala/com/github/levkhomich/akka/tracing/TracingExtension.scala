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

import akka.actor._
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TSocket, TFramedTransport}

/**
 * Tracer instance providing trace related methods.
 * @param system parent actor system
 */
class TracingExtensionImpl(system: ActorSystem) extends Extension {

  import TracingExtension._
  import SpanHolderInternalAction._

  // TODO: handle transport issues
  private[tracing] val holder = {
    val config = system.settings.config

    if (config.hasPath(AkkaTracingHost)) {
      val transport = new TFramedTransport(
        new TSocket(config.getString(AkkaTracingHost), config.getInt(AkkaTracingPort))
      )
      try {
        transport.open()
        val protocol = new TBinaryProtocol(transport)
        val client = new ScribeClient(protocol)

        system.registerOnTermination {
          transport.close()
        }

        system.actorOf(Props(classOf[SpanHolder], client, config.getInt(AkkaTracingSampleRate)), "spanHolder")
      } catch {
        case e: org.apache.thrift.transport.TTransportException =>
          throw e
      }
    } else
      throw new IllegalStateException("Tracing host not configured")
  }

  /**
   * Records string message and attaches it to timeline.
   * @param ts traced message
   * @param msg recorded string
   */
  def record(ts: BaseTracingSupport, msg: String): Unit =
    record(ts.msgId, msg)

  private[tracing] def record(msgId: Long, msg: String): Unit =
    holder ! AddAnnotation(msgId, System.nanoTime, msg)

  /**
   * Records key-value pair and attaches it to trace's binary annotations.
   * @param ts traced message
   * @param key recorded key
   * @param value recorded value
   */
  def recordKeyValue(ts: BaseTracingSupport, key: String, value: Any): Unit = {
    value match {
      case v: String =>
        addBinaryAnnotation(ts, key, ByteBuffer.wrap(v.getBytes), thrift.AnnotationType.String)
      case v: Int =>
        addBinaryAnnotation(ts, key, ByteBuffer.allocate(4).putInt(0, v), thrift.AnnotationType.I32)
      case v: Long =>
        addBinaryAnnotation(ts, key, ByteBuffer.allocate(8).putLong(0, v), thrift.AnnotationType.I64)
      case v: Boolean =>
        addBinaryAnnotation(ts, key, ByteBuffer.wrap(Array[Byte](if (v) 1 else 0)), thrift.AnnotationType.Bool)
      case v: Double =>
        addBinaryAnnotation(ts, key, ByteBuffer.allocate(8).putDouble(0, v), thrift.AnnotationType.Double)
      case v: Short =>
        addBinaryAnnotation(ts, key, ByteBuffer.allocate(2).putShort(0, v), thrift.AnnotationType.I16)
      case v: Array[Byte] =>
        addBinaryAnnotation(ts, key, ByteBuffer.wrap(v), thrift.AnnotationType.Bytes)
      case v: ByteBuffer =>
        addBinaryAnnotation(ts, key, v, thrift.AnnotationType.Bytes)
      case v =>
        throw new IllegalArgumentException("Unsupported value type")
    }
  }

  /**
   * Enables message tracing, names and samples it. After sampling any nth message
   * (defined by akka.tracing.sample-rate setting) will be actually traced.
   * @param ts traced message
   * @param service service name
   * @param rpc RPC name
   */
  def sample(ts: BaseTracingSupport, service: String, rpc: String): Unit =
    holder ! Sample(ts, service, rpc, System.nanoTime)

  /**
   * Enables message tracing, names (rpc name is assumed to be message's class name)
   * and samples it. After sampling any nth message (defined by akka.tracing.sample-rate setting)
   * will be actually traced.
   * @param ts traced message
   * @param service service name
   */
  def sample(ts: BaseTracingSupport, service: String): Unit =
    sample(ts, service, ts.getClass.getSimpleName)

  private[tracing] def recordServerSend(ts: BaseTracingSupport): Unit =
    addAnnotation(ts, thrift.Constants.SERVER_SEND, send = true)

//  def recordClientSend(ts: TracingSupport): Unit =
//    addAnnotation(ts, thrift.Constants.CLIENT_SEND)

//  def recordClientReceive(ts: TracingSupport): Unit =
//    addAnnotation(ts, thrift.Constants.CLIENT_RECV, send = true)

  /**
   * Records exception's stack trace to trace.
   * @param ts traced message
   * @param e recorded exception
   */
  def recordException(ts: BaseTracingSupport, e: Throwable): Unit =
    record(ts, getStackTrace(e))

  private def getStackTrace(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    e.getClass.getCanonicalName + ": " + sw.toString
  }

  private def addAnnotation(ts: BaseTracingSupport, value: String, send: Boolean = false): Unit =
    holder ! AddAnnotation(ts.msgId, System.nanoTime, value)

  private def addBinaryAnnotation(ts: BaseTracingSupport, key: String, value: ByteBuffer,
                                        valueType: thrift.AnnotationType): Unit =
    holder ! AddBinaryAnnotation(ts.msgId, thrift.BinaryAnnotation(key, value, valueType, None))

  private[tracing] def createChildSpan(msgId: Long, ts: BaseTracingSupport): Unit =
    holder ! CreateChildSpan(msgId, ts.msgId)

}

/**
 * Tracing extension. Provides tracer for actors mixed with [[com.github.levkhomich.akka.tracing.ActorTracing]].
 *
 * Configuration parameters:
 * - akka.tracing.host - Scribe or Zipkin collector host
 * - akka.tracing.port - Scribe or Zipkin collector port (9410 by default)
 * - akka.tracing.sample-rate - trace sample rate, means that every nth message will be sampled
 *
 */
object TracingExtension extends ExtensionId[TracingExtensionImpl] with ExtensionIdProvider {

  private[tracing] val AkkaTracingHost = "akka.tracing.host"
  private[tracing] val AkkaTracingPort = "akka.tracing.port"
  private[tracing] val AkkaTracingSampleRate = "akka.tracing.sample-rate"

  override def lookup() =
    TracingExtension

  override def createExtension(system: ExtendedActorSystem) =
    new TracingExtensionImpl(system)

  override def get(system: ActorSystem): TracingExtensionImpl =
    super.get(system)

}
