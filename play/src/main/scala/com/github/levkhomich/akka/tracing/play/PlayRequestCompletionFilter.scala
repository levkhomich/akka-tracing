package com.github.levkhomich.akka.tracing.play

import javax.inject.Inject

import akka.stream.Materializer
import com.github.levkhomich.akka.tracing.TracingAnnotations
import play.api.mvc.{ Filter, RequestHeader, Result }

import scala.concurrent.{ ExecutionContext, Future }

//class PlayRequestCompletionFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext)
//    extends Filter with PlayControllerTracing {
//
//  override def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
//   //nextFilter(requestHeader).map { //result =>
//      //trace.record(requestHeader, TracingAnnotations.ServerSend)
//    //}
//  }
//}
