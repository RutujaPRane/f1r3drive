#!/bin/bash
# Init script: copy config from staging into the Docker volume, then start rnode
cp /opt/rnode-staging/logback.xml /var/lib/rnode/logback.xml
exec /opt/docker/bin/node --profile=docker "$@"
