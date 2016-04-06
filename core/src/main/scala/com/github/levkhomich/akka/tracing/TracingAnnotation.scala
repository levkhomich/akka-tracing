package com.github.levkhomich.akka.tracing

sealed abstract class TracingAnnotation(val text: String)

object TracingAnnotations {
  case object ServerReceived extends TracingAnnotation(thrift.zipkinConstants.SERVER_RECV)
  case object ServerReceivedFragment extends TracingAnnotation(thrift.zipkinConstants.SERVER_RECV_FRAGMENT)
  case object ServerSend extends TracingAnnotation(thrift.zipkinConstants.SERVER_SEND)
  case object ClientReceived extends TracingAnnotation(thrift.zipkinConstants.CLIENT_RECV)
  case object ClientReceivedFragment extends TracingAnnotation(thrift.zipkinConstants.CLIENT_RECV_FRAGMENT)
  case object ClientSend extends TracingAnnotation(thrift.zipkinConstants.CLIENT_SEND)

  final case class Custom(override val text: String) extends TracingAnnotation(text)
}