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

trait BaseTracingSupport extends Any {

  private[tracing] def tracingId: Long = {
    val a = System.identityHashCode(this)
    val b = hashCode
    a.toLong << 32 | b & 0xFFFFFFFFL
  }

  protected[tracing] def spanName: String

  /**
   * Declares a message as child of another message.
   * @param parent parent message
   * @return child message with required tracing headers
   */
  def asChildOf(parent: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): BaseTracingSupport
}

/**
 * Trait to be mixed with messages that should support tracing.
 */
trait TracingSupport extends BaseTracingSupport with Serializable {

  override def spanName: String =
    this.getClass.getSimpleName

  override def asChildOf(parent: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): this.type = {
    tracer.createChildSpan(tracingId, parent.tracingId, spanName)
    this
  }

}

class ResponseTracingSupport[T](val msg: T) extends AnyVal {

  /**
   * Declares message as a response to another message.
   * @param request parent message
   * @return unchanged message
   */
  def asResponseTo(request: BaseTracingSupport)(implicit trace: TracingExtensionImpl): T = {
    trace.record(request, "response: " + msg)
    trace.finish(request)
    msg
  }
}

