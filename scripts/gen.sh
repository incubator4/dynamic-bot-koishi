#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$root/proto" && buf lint && buf generate)
"$root/jvm/gradlew" -p "$root/jvm" generateProtos
