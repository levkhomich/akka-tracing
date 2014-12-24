Akka Tracing [![Maven Central](https://img.shields.io/maven-central/v/com.github.levkhomich/akka-tracing-core_2.11.svg?style=flat-square)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.levkhomich%22%20akka-tracing)
============

A distributed tracing Akka extension based on Twitter's [Zipkin](http://twitter.github.io/zipkin/).

It allows you to:
- trace call hierarchies inside an actor system;
- trace distributed calls (akka-remote, akka-cluster, finagle services, etc.);
- debug request processing pipelines (you can log to trace, annotate it with custom key-value pairs).
- find slow requests in your system;

Extension is designed to run on production systems (reactive, minimal overhead, adjustable sample rate) and
provides integration with Play framework and Spray.

See [wiki](https://github.com/levkhomich/akka-tracing/wiki) for more information.

Getting started
---------------

The best way is to use project's activator templates:
[general features, Scala and Java API, Spray integration](https://typesafe.com/activator/template/activator-akka-tracing),
[Play integration](https://typesafe.com/activator/template/activator-play-tracing).
Also, you can read [tracing overview page](https://github.com/levkhomich/akka-tracing/wiki/Overview).

Development
-----------

[![Build Status](https://img.shields.io/travis/levkhomich/akka-tracing/master.svg?style=flat-square)](https://travis-ci.org/levkhomich/akka-tracing) [![Coverage Status](https://img.shields.io/coveralls/levkhomich/akka-tracing.svg?style=flat-square)](https://coveralls.io/r/levkhomich/akka-tracing?branch=master) [![Issues](https://img.shields.io/github/issues/levkhomich/akka-tracing.svg?style=flat-square)](https://github.com/levkhomich/akka-tracing/issues)

