package com.github.levkhomich.akka.tracing.play

import scala.concurrent.Future

import play.api.http.{ HttpErrorHandler, LazyHttpErrorHandler }
import play.api.mvc.{ RequestHeader, Result }

trait TracingErrorHandler extends HttpErrorHandler with PlayControllerTracing {
  abstract override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    trace.record(request, exception)
    super.onServerError(request, exception)
  }
}
