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

package com.github.levkhomich.akka.tracing.serialization

import java.nio.ByteBuffer

import akka.actor.ExtendedActorSystem
import akka.serialization.{ JavaSerializer, Serializer }

import com.github.levkhomich.akka.tracing.{ TracingExtension, BaseTracingSupport }

class BaseTracingSupportSerializer(system: ExtendedActorSystem) extends SerializerTracingSupport(system, new JavaSerializer(system))

class SerializerTracingSupport(system: ExtendedActorSystem, delegate: Serializer) extends Serializer {

  def includeManifest: Boolean = false
  def identifier = 18361348
  val trace = TracingExtension(system)

  def toBinary(obj: AnyRef): Array[Byte] = {
    obj match {
      case ts: BaseTracingSupport =>
        val nested = delegate.toBinary(obj)
        val maybeSpan = trace.getId(ts.tracingId)
        ByteBuffer.allocate(24 + nested.length).putLong(maybeSpan.map(_.spanId).getOrElse(0))
          .putLong(maybeSpan.map(_.traceId).getOrElse(0))
          .putLong(maybeSpan.flatMap(_.parentId).getOrElse(0))
          .put(nested)
          .array
      case _ =>
        throw new IllegalArgumentException
    }
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val msg = delegate.fromBinary(bytes.slice(24, bytes.length), clazz)

    msg match {
      case ts: BaseTracingSupport =>
        val spanId = ByteBuffer.wrap(bytes.slice(0, 8)).getLong
        val traceId = ByteBuffer.wrap(bytes.slice(8, 16)).getLong
        val parentId = ByteBuffer.wrap(bytes.slice(16, 24)).getLong
        if (spanId != 0) {
          trace.sample(ts, spanId,
            if (parentId == 0) None else Some(parentId), traceId, "", force = true)
        }
        msg
      case _ =>
        throw new IllegalArgumentException
    }
  }
}
