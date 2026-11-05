#!/usr/bin/env bash
# Apply the ggml-vulkan Vulkan 1.1 / Android compatibility patch to the
# llama.cpp submodule. Idempotent: safe to run more than once.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUB="$ROOT/app/src/main/cpp/llama.cpp"
PATCH="$ROOT/patches/ggml-vulkan-android-vulkan11.patch"
EXPECTED="3de52ac38818978d6da8f386eda43a856e53714a"

if [ ! -e "$SUB/.git" ]; then
    echo "llama.cpp submodule is not initialized. Run: git submodule update --init --recursive" >&2
    exit 1
fi
if [ ! -f "$PATCH" ]; then
    echo "Patch file not found: $PATCH" >&2
    exit 1
fi

cd "$SUB"
HEAD="$(git rev-parse HEAD)"
if [ "$HEAD" != "$EXPECTED" ]; then
    echo "warning: submodule HEAD is $HEAD, expected $EXPECTED - patch may not apply cleanly." >&2
fi

if git apply --reverse --check "$PATCH" 2>/dev/null; then
    echo "Patch already applied, nothing to do."
    exit 0
fi

git apply --check "$PATCH"
git apply "$PATCH"
echo "Patch applied to llama.cpp submodule."
