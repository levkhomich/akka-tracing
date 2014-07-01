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