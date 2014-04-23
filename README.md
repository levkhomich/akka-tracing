Akka Tracing  [![Build Status](https://travis-ci.org/levkhomich/akka-tracing.png?branch=master)](https://travis-ci.org/levkhomich/akka-tracing)
============

A distributed tracing Akka extension based on Twitter's [Zipkin](http://twitter.github.io/zipkin/).
Extension can be used as performance diagnostics or debugging tool for distributed applications.

See [project's wiki](https://github.com/levkhomich/akka-tracing/wiki) for more information.

![trace example](https://raw.githubusercontent.com/levkhomich/akka-tracing/gh-pages/screenshots/timeline.png)

Usage
-----

1. [Setup Zipkin infrastructure and your project](https://github.com/levkhomich/akka-tracing/wiki/Setup);
1. mix request-processing actors with [`AkkaTracing`](https://github.com/levkhomich/akka-tracing/blob/master/core/src/main/scala/com/github/levkhomich/akka/tracing/ActorTracing.scala) and
  traceable messages with [`TracingSupport`](https://github.com/levkhomich/akka-tracing/blob/master/core/src/main/scala/com/github/levkhomich/akka/tracing/TracingSupport.scala);
1. sample messages you want to trace using `trace.sample(message)`;
1. use [`trace.record*`](https://github.com/levkhomich/akka-tracing/blob/master/core/src/main/scala/com/github/levkhomich/akka/tracing/TracingExtension.scala#L58) methods to annotate traces.
1. to register server response, mark it with `responseMessage.asResponseTo(request)`.

See more detailed guide [here](https://github.com/levkhomich/akka-tracing/wiki/Overview).
Also, you can take a look at [examples](https://github.com/levkhomich/akka-tracing/tree/master/examples/src/main).

Roadmap
-------

- [0.3 release](https://github.com/levkhomich/akka-tracing/issues?milestone=3) - WIP.
