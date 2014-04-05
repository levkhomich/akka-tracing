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

import org.apache.thrift.protocol.{TMessageType, TMessage, TProtocol}
import org.apache.thrift.{TApplicationException, TServiceClient}

/**
 * Internal API
 */
private[tracing] class ScribeClient(prot: TProtocol) extends TServiceClient(prot, prot) with thrift.Scribe[Option] {

  def log(messages: Seq[thrift.LogEntry]): Option[thrift.ResultCode] = {
    // send
    val args = thrift.Scribe.log$args(messages)
    seqid_ += 1
    oprot_.writeMessageBegin(new TMessage("Log", TMessageType.CALL, seqid_))
    args.write(oprot_)
    oprot_.writeMessageEnd()
    oprot_.getTransport.flush()

    // receive
    val msg = iprot_.readMessageBegin()
    if (msg.`type` == TMessageType.EXCEPTION) {
      val x = TApplicationException.read(iprot_)
      iprot_.readMessageEnd()
      throw x
    }
    if (msg.seqid != seqid_) {
      throw new TApplicationException(TApplicationException.BAD_SEQUENCE_ID, "Log failed: out of sequence response")
    }
    val result = thrift.Scribe.log$result.decode(iprot_)
    iprot_.readMessageEnd()
    result.success
  }

}
