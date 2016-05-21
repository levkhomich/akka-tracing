package com.github.levkhomich.akka.tracing

sealed abstract class TracingAnnotation(val text: String)

object TracingAnnotations {

  /**
   * States that the server received a request.
   * There should be only one such annotation per span, so each span should represent
   * a single request.
   *
   * In case of chunking, each chunk should be marked by a separate ServerReceivedFragment
   * annotation.
   */
  case object ServerReceived extends TracingAnnotation(thrift.zipkinConstants.SERVER_RECV)

  /**
   * States that the server received a fragment of request.
   */
  case object ServerReceivedFragment extends TracingAnnotation(thrift.zipkinConstants.SERVER_RECV_FRAGMENT)

  /**
   * States that the server sent a response.
   * There should be only one such annotation per span, so each span should represent
   * a single request.
   *
   * In case of chunking, each chunk should be marked by a separate ServerSendFragment
   * annotation.
   */
  case object ServerSend extends TracingAnnotation(thrift.zipkinConstants.SERVER_SEND)

  /**
   * States that the server sent a fragment of request.
   */
  case object ServerSendFragment extends TracingAnnotation(thrift.zipkinConstants.SERVER_SEND_FRAGMENT)

  /**
   * States that the client received a request.
   * There should be only one such annotation per span, so each span should represent
   * a single request.
   *
   * In case of chunking, each chunk should be marked by a separate ClientReceivedFragment
   * annotation.
   */
  case object ClientReceived extends TracingAnnotation(thrift.zipkinConstants.CLIENT_RECV)

  /**
   * States that the client received a fragment of request.
   */
  case object ClientReceivedFragment extends TracingAnnotation(thrift.zipkinConstants.CLIENT_RECV_FRAGMENT)

  /**
   * States that the client sent a response.
   * There should be only one such annotation per span, so each span should represent
   * a single request.
   *
   * In case of chunking, each chunk should be marked by a separate ClientSendFragment
   * annotation.
   */
  case object ClientSend extends TracingAnnotation(thrift.zipkinConstants.CLIENT_SEND)

  /**
   * States that the client sent a fragment of request.
   */
  case object ClientSendFragment extends TracingAnnotation(thrift.zipkinConstants.CLIENT_SEND_FRAGMENT)

  /**
   * Use this class to provide custom annotations to framework.
   */
  final case class Custom(override val text: String) extends TracingAnnotation(text)
}