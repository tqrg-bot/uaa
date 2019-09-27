#!/usr/bin/env bash
set -eu -o pipefail
set -x # TODO: remove after debugging

UAA_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"

"${UAA_DIR}/scripts/gradle" integrationTest
