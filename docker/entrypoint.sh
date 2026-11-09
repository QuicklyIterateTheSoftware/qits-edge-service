#!/bin/sh
# The qits-edge image's entrypoint. It starts as root only so the private edge can bring up wg0, and
# in EVERY mode hands over to qits-edge-init, which drops to uid 1001 (gid 0, no supplementary
# groups) and execs the edge — so the proxy never runs as root, public or private.
#
# QITS_VARIANT is set by qits-deployments on a variant's container and on nothing else: `private` is
# the private edge (.config/qits/deployments.private.yml), which also needs wg0. Any other value, or
# none, is the public edge, which runs exactly as it did under `USER 1001`.
#
# The two -D flags are the image's historical ENTRYPOINT, verbatim; see the Dockerfile for why each.
set -eu

if [ "${QITS_VARIANT:-}" = private ]; then
  exec /usr/local/bin/qits-edge-init --wireguard -- \
    /work/qits-platform-edge -Dquarkus.http.host=0.0.0.0 -Dnetworkaddress.cache.negative.ttl=0 "$@"
else
  exec /usr/local/bin/qits-edge-init -- \
    /work/qits-platform-edge -Dquarkus.http.host=0.0.0.0 -Dnetworkaddress.cache.negative.ttl=0 "$@"
fi
