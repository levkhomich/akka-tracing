#!/bin/bash

if [[ "$TRAVIS_JOB_NUMBER" == *.1 ]]; then
  sbt clean coverage test coverageReport
  sbt coverageAggregate 'project akka-tracing-root' coveralls
fi