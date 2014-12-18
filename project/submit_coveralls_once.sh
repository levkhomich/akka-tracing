#!/bin/bash

DONE="./coveralls_done"

if [ ! -f $DONE ]; then
  sbt clean coverage test coverageReport
  sbt coverageAggregate 'project akka-tracing-root' coveralls
  if [ $? -eq 0 ]; then
    touch $DONE
  fi
fi