#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
lib=${KOTLIN_ABYSS_LIB_DIR:-"$work/lib"}
mkdir -p "$lib"
fetch() {
  [ -f "$lib/$2" ] || curl -fLsS --max-time 120 "https://repo.maven.apache.org/maven2/$1" -o "$lib/$2"
}
fetch org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.0/kotlin-compiler-embeddable-2.3.0.jar compiler.jar
fetch org/jetbrains/kotlin/kotlin-stdlib/2.3.0/kotlin-stdlib-2.3.0.jar stdlib.jar
fetch org/jetbrains/kotlin/kotlin-reflect/2.3.0/kotlin-reflect-2.3.0.jar reflect.jar
fetch org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar coroutines.jar
fetch org/json/json/20251224/json-20251224.jar json.jar
fetch org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar annotations.jar
java -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$lib/stdlib.jar:$lib/coroutines.jar:$lib/json.jar:$lib/annotations.jar" \
  -d "$work/tests.jar" "$root"/validation/msm22/*.kt \
  "$root/MSM21/src/main/kotlin/com/msm21/MsmAbyssApi.kt" \
  "$root/MSM21/src/main/kotlin/com/msm21/MsmMediaPolicy.kt" \
  "$root/MSM21/src/main/kotlin/com/msm21/MsmServerLabels.kt"
java -cp "$work/tests.jar:$lib/*" com.msm21.TestAbyssKt
