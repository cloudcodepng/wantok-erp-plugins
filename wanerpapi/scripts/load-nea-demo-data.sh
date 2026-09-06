#!/usr/bin/env bash
set -euo pipefail
TENANT_ID="${TENANT_ID:-CLOUDCODE}"
FRAMEWORK_ROOT="${FRAMEWORK_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
[[ "$TENANT_ID" == "CLOUDCODE" ]] || { echo "This demo loader is locked to CLOUDCODE" >&2; exit 2; }
cd "$FRAMEWORK_ROOT"
./gradlew "ofbiz --load-data delegator=default#$TENANT_ID readers=ext-demo component=wanerpapi"
