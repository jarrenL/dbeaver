#!/bin/bash
# Test-only installation into an explicit disposable product copy.
set -euo pipefail
test_root=${1:?Usage: install-local.sh /absolute/test-directory}
test_eclipse=${2:-"$test_root/DBeaver.app/Contents/Eclipse"}
test_source=$(cd "$(dirname "$0")" && pwd)
test_java=${JAVA_HOME:?Set JAVA_HOME to a JDK 21 or newer}
test -f "$test_eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
mkdir -p "$test_root/classes"
"$test_java/bin/javac" --release 21 -cp "$test_root/lib/*:$test_eclipse/plugins/*" \
    -d "$test_root/classes" "$test_source/src/org/jkiss/dbeaver/gaussdb/acceptance/Bot.java"
"$test_java/bin/jar" cfm "$test_root/lib/org.jkiss.dbeaver.gaussdb.swtbot.acceptance.jar" \
    "$test_source/META-INF/MANIFEST.MF" -C "$test_root/classes" . -C "$test_source" plugin.xml
for test_bundle in "$test_root"/lib/*.jar; do
    test_name=$(unzip -p "$test_bundle" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Bundle-SymbolicName: \([^;]*\).*/\1/p' | head -1)
    test_version=$(unzip -p "$test_bundle" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Bundle-Version: //p' | head -1)
    test -n "$test_name"
    test -n "$test_version"
    cp "$test_bundle" "$test_eclipse/plugins/${test_name}_${test_version}.jar"
    if ! grep -q "^${test_name}," "$test_eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"; then
        printf '%s,%s,plugins/%s_%s.jar,4,false\n' "$test_name" "$test_version" "$test_name" "$test_version" \
            >> "$test_eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    fi
done
