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

import java.util.UUID

/**
 * Trait to be mixed in messages that should support tracing.
 */
trait TracingSupport extends Serializable {

  private[tracing] val msgId = UUID.randomUUID()
  private[tracing] var span: Option[Span] = None

  /**
   * Declares message as a child of another message.
   * @param ts parent message
   * @return child message with required tracing headers
   */
  def asChildOf(ts: TracingSupport)(implicit tracer: TracingExtensionImpl): this.type = {
    span = tracer.createChildSpan(ts)
    this
  }

}

class ResponseTracingSupport[T](val msg: T) extends AnyVal {

  /**
   * Declares message as a response to another message.
   * @param request parent message
   * @return unchanged message
   */
  def asResponseTo(request: TracingSupport)(implicit trace: TracingExtensionImpl): T = {
    trace.record(request, "response: " + msg)
    trace.recordServerSend(request)
    msg
  }
}

