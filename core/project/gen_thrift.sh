#!/bin/bash

THRIFT_SRC=$1
MANAGED_DIR=$2

mkdir -p $MANAGED_DIR
thrift -r --gen java:nocamel -out $MANAGED_DIR $THRIFT_SRC
sed -i.bak 's/extends org.apache.thrift.async.TAsyncMethodCall /&<Log_call> /' `find $MANAGED_DIR -type f -name Scribe.java`
find $MANAGED_DIR -type f -name Scribe.java.bak -exec rm {} \;
