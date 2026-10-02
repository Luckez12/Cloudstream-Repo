#!/bin/sh
set -eu
validation_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$validation_dir/.."
injection_fixture=$(mktemp)
trap 'rm -f "$injection_fixture"' EXIT
java validation/TestInjection.java "$injection_fixture"
INJECTED_HTML="$injection_fixture" node validation/test_hook.cjs
