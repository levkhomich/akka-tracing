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

package com.github.levkhomich.akka.tracing.thrift

import org.apache.thrift.TByteArrayOutputStream
import org.apache.thrift.transport.TTransport

private[tracing] class TReusableTransport extends TTransport {

  private[this] val buffer = new TByteArrayOutputStream(1024)
  private[this] var readPos = 0

  override def open(): Unit = throw new UnsupportedOperationException
  
  override def isOpen: Boolean = true

  override def close(): Unit = throw new UnsupportedOperationException

  def getArray(): Array[Byte] =
    buffer.get()

  def length: Int =
    buffer.len()

  def reset(): Unit = {
    buffer.reset()
    readPos = 0
  }

  override def read(buf: Array[Byte], off: Int, len: Int): Int = {
    val readSize = len min (buffer.len() - readPos)
    if (readSize > 0) {
      System.arraycopy(buffer.get(), readPos, buf, off, readSize)
      readPos += readSize
    }
    readSize
  }

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    buffer.write(buf, off, len)

}