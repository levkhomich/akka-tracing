Akka Tracing  [![Build Status](https://travis-ci.org/levkhomich/akka-tracing.png?branch=master)](https://travis-ci.org/levkhomich/akka-tracing)
============

A distributed tracing Akka extension based on Twitter's [Zipkin](http://twitter.github.io/zipkin/).

It allows you to:
- trace distributed hierarchy calls (akka-remote, akka-cluster, Finagle services, etc.);
- find slow requests in your system;
- debug request processing pipeline (you can log to trace, annotate it with custom key-value pairs).

Extension is designed to run on production systems (reactive, minimal overhead, adjustable sample rate).

See [wiki](https://github.com/levkhomich/akka-tracing/wiki) for more information.

Getting started
---------------

The best way is to use [project's activator template](https://typesafe.com/activator/template/activator-akka-tracing).
Also, you can read [tracing overview page](https://github.com/levkhomich/akka-tracing/wiki/Overview).
