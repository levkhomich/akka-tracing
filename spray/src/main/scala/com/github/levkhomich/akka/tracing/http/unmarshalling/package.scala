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

package com.github.levkhomich.akka.tracing.http

import spray.httpx.unmarshalling.{Deserialized, Deserializer, FromMessageUnmarshaller, Unmarshaller}
import spray.http.HttpMessage
import com.github.levkhomich.akka.tracing.TracingSupport
import TracingHeaders._

package object unmarshalling {

  def unmarshallerWithTracingSupport[T <: TracingSupport](implicit um: Unmarshaller[T]): FromMessageUnmarshaller[T] =
    new Deserializer[HttpMessage, T] {
      override def apply(message: HttpMessage): Deserialized[T] =
        um(message.entity).right.map { result =>
          extractSpan(message).foreach(s => result.init(s.spanId, s.traceId, s.parentId))
          result
        }
    }
}