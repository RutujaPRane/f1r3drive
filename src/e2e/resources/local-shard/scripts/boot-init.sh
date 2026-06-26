#!/bin/bash
# Init script: copy config from staging into the Docker volume, then start rnode
cp /opt/rnode-staging/rnode.conf /var/lib/rnode/rnode.conf
mkdir -p /var/lib/rnode/genesis
cp /opt/rnode-staging/genesis/wallets.txt /var/lib/rnode/genesis/wallets.txt
cp /opt/rnode-staging/genesis/bonds.txt /var/lib/rnode/genesis/bonds.txt
cp /opt/rnode-staging/logback.xml /var/lib/rnode/logback.xml
cp /opt/rnode-staging/node.certificate.pem /var/lib/rnode/node.certificate.pem
cp /opt/rnode-staging/node.key.pem /var/lib/rnode/node.key.pem
exec /opt/docker/bin/node --profile=docker "$@"
