Akka Tracing [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.levkhomich/akka-tracing-core_2.11/badge.svg?style=flat)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.levkhomich%22%20akka-tracing)
============

A distributed tracing Akka extension based on Twitter's [Zipkin](http://twitter.github.io/zipkin/).

It allows you to:
- debug requests in your Play, Spray and Finagle projects;
- trace distributed hierarchy calls (akka-remote, akka-cluster, finagle services, etc.);
- find slow requests in your system;
- debug request processing pipeline (you can log to trace, annotate it with custom key-value pairs).

Extension is designed to run on production systems (reactive, minimal overhead, adjustable sample rate).

See [wiki](https://github.com/levkhomich/akka-tracing/wiki) for more information.

Getting started
---------------

The best way is to use [project's activator template](https://typesafe.com/activator/template/activator-akka-tracing).
Also, you can read [tracing overview page](https://github.com/levkhomich/akka-tracing/wiki/Overview).

Development
-----------

[![Build Status](https://travis-ci.org/levkhomich/akka-tracing.svg?branch=master)](https://travis-ci.org/levkhomich/akka-tracing) [![Coverage Status](https://img.shields.io/coveralls/levkhomich/akka-tracing.svg)](https://coveralls.io/r/levkhomich/akka-tracing?branch=master)

Any contributions are welcome. [Issue tracker](https://github.com/levkhomich/akka-tracing/issues) is the place to report bugs to or take the tickets to work on.

