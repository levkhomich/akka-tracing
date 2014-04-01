Akka Tracing
============

A tracing for Akka cluster deployments made easy. Library includes Akka extension which allow traces
to be collected and sent to Scribe collector and viewed using [Zipkin](http://twitter.github.io/zipkin/).
You can add custom annotations and key-value pairs to traces and use them for filtering via Zipkin Web UI.
Extension can easily handle sampling thousands of traces per second.

[![Build Status](https://travis-ci.org/levkhomich/akka-tracing.png?branch=master)](https://travis-ci.org/levkhomich/akka-tracing)

Building
--------

To build and test library run
`sbt test`

Using
-----

1. Setup [Zipkin](http://twitter.github.io/zipkin/install.html) infrastructure.
2. Include akka-tracing-core dependency to your build.
3. Provide `akka.tracing.host` parameter in application config.
4. Mix request-processing actors with `AkkaTracing` and traceable messages with `TracingSupport`.
5. Use `trace.*` methods to record traces.

To start tracing correctly `trace.recordServerReceive` must be called before other tracing methods.
To register server response, use `yourMessage.asResponseTo(request)`.
Sampling rate can be changed using `akka.tracing.sample-rate` config parameter.

Examples
--------

See `examples` dir:
- [Trace hierarchy and timeout handling](https://github.com/levkhomich/akka-tracing/tree/master/examples/src/main/scala/org/example/TraceHierarchy.scala)


Documentation
-------------

Work in progress. Will be available in project's wiki.

