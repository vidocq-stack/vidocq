#!/usr/bin/env bash
# build-images.sh — builds the images of the runtime examples: jlink, jpackage app-image and Dockerfile.
#
# The reactor builds no image: the examples' `dist` profile is off unless asked for, in CI and locally, since an
# image takes a few seconds and 40 to 60 MB per example and no test reads it. This script turns it on for the
# examples and builds what they need with them, from the root of the monorepo.
#
# Usage: vidocq-runtime/vidocq-runtime-examples/build-images.sh [Maven arguments]
#   e.g. build-images.sh -Dmaven.test.skip -T 1C
# One example only: ./mvnw -Pdist package -am -pl vidocq-runtime/vidocq-runtime-examples/<example>
#
# The images land in each example's target/dist (jlink) and target/installer (jpackage).

set -euo pipefail
cd "$(dirname "$0")/../.."

EXAMPLES=vidocq-runtime/vidocq-runtime-examples

exec ./mvnw -Pdist -Dgib.disable=true package -am \
    -pl "$EXAMPLES/vidocq-runtime-cassini-rest-example,$EXAMPLES/vidocq-runtime-cyrano-rest-client-example,$EXAMPLES/vidocq-runtime-cervantes-jwt-example,$EXAMPLES/vidocq-runtime-knock-health-example,$EXAMPLES/vidocq-runtime-petstore-example,$EXAMPLES/vidocq-runtime-noether-persistence-example" \
    "$@"
