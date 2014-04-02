Akka Tracing
============

Distributed tracing Akka extension based on Twitter's [Zipkin](http://twitter.github.io/zipkin/).
Extension can be used in production environment as performance diagnostic tool or in development environment for
debugging purposes. Sampled traces can contain not only timing info, but custom annotations and key-value pairs.
Furthermore, such annotations can be used as filtering parameters in Zipkin's Web UI.

[![Build Status](https://travis-ci.org/levkhomich/akka-tracing.png?branch=master)](https://travis-ci.org/levkhomich/akka-tracing)

Building
--------

To build and test library run
`sbt test`

Using
-----

- [setup](http://twitter.github.io/zipkin/install.html) Zipkin infrastructure;
- include akka-tracing-core dependency to your build (as project API still not stabilized, you should use snapshot)

```scala
resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies += "com.github.levkhomich.akka.tracing" %% "akka-tracing-core" % "0.1.0-SNAPSHOT" changing()
```

- provide `akka.tracing.host` in application's config;
- mix request-processing actors with `AkkaTracing` and traceable messages with `TracingSupport`;
- use `trace.*` methods to record traces.

To start tracing correctly `trace.sample` must be called before other tracing methods
(sampling rate can be changed using `akka.tracing.sample-rate` config parameter).
To register server response, use `yourMessage.asResponseTo(request)`.

Examples
--------

See `examples` dir:
- [Trace hierarchy and timeout handling](https://github.com/levkhomich/akka-tracing/tree/master/examples/src/main/scala/org/example/TraceHierarchy.scala)


Documentation
-------------

Work in progress. Will be available in project's wiki.

Roadmap
-------

[0.1 Release](https://github.com/levkhomich/akka-tracing/issues?milestone=1)
