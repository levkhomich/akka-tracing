Akka Tracing
============

A tracing for Akka cluster deployments made easy. Library includes Akka extension which allow traces
to be collected and sent to Scribe collector and viewed using [Zipkin](http://twitter.github.io/zipkin/).

Building
--------

To build and test library run
`sbt test`

Using
-----

1. Setup [Zipkin](http://twitter.github.io/zipkin/install.html) infrastructure.
2. Provide `akka.tracing.host` parameter in your config.
3. Mix request-processing actors with `AkkaTracing` and traceable messages with `TracingSupport`.
4. Use `trace.*` methods to record traces.

To start tracing correctly `trace.recordServerReceive` must be called before other tracing methods.
To register server response, use `yourMessage.responseTo(request)`.
Sampling rate can be changed using `akka.tracing.sample-rate` config parameter.

Documentation
-------------

Will be available in project's wiki.

