Akka Tracing  [![Build Status](https://travis-ci.org/levkhomich/akka-tracing.png?branch=master)](https://travis-ci.org/levkhomich/akka-tracing) [![Coverage Status](https://coveralls.io/repos/levkhomich/akka-tracing/badge.png?branch=master)](https://coveralls.io/r/levkhomich/akka-tracing?branch=master)
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
