#!/bin/bash

DONE="./coveralls_done"

if [ ! -f $DONE ]; then
  sbt 'project akka-tracing-core' 'coveralls'
  if [ $? -eq 0 ]; then
    touch $DONE
  fi
fi