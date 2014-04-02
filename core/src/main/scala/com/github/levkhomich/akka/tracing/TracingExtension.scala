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
import java.util.UUID

import akka.actor._
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TSocket, TFramedTransport}


class TracingExtensionImpl(system: ActorSystem) extends Extension {

  import TracingExtension._

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

        new SpanHolder(client, system.scheduler, config.getInt(AkkaTracingSampleRate))
      } catch {
        case e: org.apache.thrift.transport.TTransportException =>
          throw e
      }
    } else
      throw new IllegalStateException("Tracing host not configured")
  }

  def record(ts: TracingSupport, msg: String): Unit =
    record(ts.msgId, msg)

  private[tracing] def record(msgId: UUID, msg: String): Unit = {
    holder.update(msgId) { spanInt =>
      val a = thrift.Annotation(System.currentTimeMillis * 1000, msg, None, None)
      spanInt.copy(annotations = a +: spanInt.annotations)
    }
  }

  def recordKeyValue(ts: TracingSupport, key: String, value: Any): Unit = {
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

  def recordRPCName(ts: TracingSupport, service: String, rpc: String): Unit = {
    holder.update(ts.msgId) { spanInt =>
      spanInt.copy(name = rpc)
    }
    holder.setServiceName(ts.msgId, service)
  }

  def recordRPCName(ts: TracingSupport, service: String): Unit = 
    recordRPCName(ts, service, ts.getClass.getSimpleName)

  def sample(ts: TracingSupport): Unit =
    if (holder.sample(ts))
      addAnnotation(ts, thrift.Constants.SERVER_RECV)

  private[tracing] def recordServerSend(ts: TracingSupport): Unit =
    addAnnotation(ts, thrift.Constants.SERVER_SEND, send = true)

  def recordClientSend(ts: TracingSupport): Unit =
    addAnnotation(ts, thrift.Constants.CLIENT_SEND)

  def recordClientReceive(ts: TracingSupport): Unit =
    addAnnotation(ts, thrift.Constants.CLIENT_RECV, send = true)

  def recordException(ts: TracingSupport, e: Throwable): Unit =
    record(ts, getStackTrace(e))

  private def getStackTrace(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    e.getClass.getCanonicalName + ": " + sw.toString
  }

  private def addAnnotation(ts: TracingSupport, value: String, send: Boolean = false): Unit =
    holder.update(ts.msgId, send) { spanInt =>
      val a = thrift.Annotation(System.currentTimeMillis * 1000, value, None, None)
      spanInt.copy(annotations = a +: spanInt.annotations)
    }

  private def addBinaryAnnotation(ts: TracingSupport, key: String, value: ByteBuffer,
                                        valueType: thrift.AnnotationType): Unit =
    holder.update(ts.msgId) { spanInt =>
      val a = thrift.BinaryAnnotation(key, value, valueType, None)
      spanInt.copy(binaryAnnotations = a +: spanInt.binaryAnnotations)
    }

  private[tracing] def createChildSpan(ts: TracingSupport): Option[Span] =
    holder.createChildSpan(ts.msgId)

}

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
