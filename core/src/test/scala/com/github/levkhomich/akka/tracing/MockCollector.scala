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

import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import javax.xml.bind.DatatypeConverter

import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TFramedTransport, TServerSocket, TMemoryBuffer}
import org.apache.thrift.server.{TThreadPoolServer, TServer}

import com.github.levkhomich.akka.tracing.thrift.{ResultCode, LogEntry}


trait MockCollector {

  var collector: TServer = startCollector()
  val results = new ConcurrentLinkedQueue[thrift.LogEntry]()

  def startCollector(): TServer = {

    val handler = new thrift.Scribe.Iface {
      override def Log(messages: util.List[LogEntry]): ResultCode = {
        println(s"collector: received ${messages.size} messages")
        results.addAll(messages)
        thrift.ResultCode.OK
      }
    }
    val processor = new thrift.Scribe.Processor(handler)

    val transport = new TServerSocket(9410)
    val collector = new TThreadPoolServer(
      new TThreadPoolServer.Args(transport).processor(processor).
        transportFactory(new TFramedTransport.Factory).protocolFactory(new TBinaryProtocol.Factory).minWorkerThreads(3)
    )
    new Thread(new Runnable() {
      override def run(): Unit = {
        println("collector: started")
        collector.serve()
        println("collector: stopped")
      }
    }).start()
    Thread.sleep(3000)
    collector
  }

  def decodeSpan(logEntryMessage: String): thrift.Span = {
    val protocolFactory = new TBinaryProtocol.Factory()
    val thriftBytes = DatatypeConverter.parseBase64Binary(logEntryMessage.dropRight(1))
    val buffer = new TMemoryBuffer(1024)
    buffer.write(thriftBytes, 0, thriftBytes.length)
    val span = new thrift.Span
    span.read(protocolFactory.getProtocol(buffer))
    span
  }

}
