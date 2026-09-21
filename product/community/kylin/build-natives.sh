#!/usr/bin/env bash
# Build matching Eclipse native components inside the target Kylin V10 userland.
# Sources: eclipse.platform.swt tag v4973r12; equinox tag R4_37 (launcher 11916).
set -euo pipefail
swt_source=${1:?Usage: build-natives.sh SWT_SOURCE EQUINOX_LIBRARY NEW_OUTPUT_DIRECTORY}
launcher_source=${2:?Equinox executable.feature/library directory required}
native_output=${3:?A new output directory is required}
native_java=${SWT_JAVA_HOME:-/usr/lib/jvm/java-11-openjdk}
test -f "$native_java/include/jni.h"
test ! -e "$native_output"
case "$(uname -m)" in aarch64|x86_64) ;; *) echo 'Unsupported architecture' >&2; exit 1;; esac
mkdir -p "$native_output/swt-build" "$native_output/launcher" "$native_output/lib"
for part in 'Eclipse SWT/common' 'Eclipse SWT PI/common' 'Eclipse SWT PI/gtk' \
    'Eclipse SWT PI/cairo' 'Eclipse SWT AWT/gtk' 'Eclipse SWT OpenGL/glx' 'Eclipse SWT WebKit/gtk'; do
    cp "$swt_source/bundles/org.eclipse.swt/$part/library/"* "$native_output/swt-build/"
done
# GCC 7.3 shipped by Kylin V10 does not recognize gnu17. This binding source
# builds as C11 with all original warning-as-error checks still enabled.
sed -i 's/-std=gnu17/-std=gnu11/' "$native_output/swt-build/make_linux.mak"
(
    cd "$native_output/swt-build"
    SWT_JAVA_HOME="$native_java" sh build.sh -gtk3
)
for component in swt swt-atk swt-awt swt-cairo swt-glx swt-pi3 swt-webkit; do
    cp "$native_output/swt-build/lib${component}-gtk-4973r12.so" "$native_output/lib/"
done
cp -a "$launcher_source/." "$native_output/launcher/"
(
    cd "$native_output/launcher/gtk"
    sh build.sh -java "$native_java"
)
cp "$native_output/launcher/gtk/eclipse" "$native_output/dbeaver"
cp "$native_output/launcher/gtk/eclipse_11916.so" "$native_output/lib/"
rpm -q glibc gtk3 gcc java-11-openjdk-devel > "$native_output/build-environment.txt"
uname -m >> "$native_output/build-environment.txt"
for binary in "$native_output/dbeaver" "$native_output/lib/"*.so; do
    readelf --version-info "$binary"
done > "$native_output/elf-versions.txt"
if grep -Eq 'Name: GLIBC_2\.(29|[3-9][0-9])\b' "$native_output/elf-versions.txt"; then
    echo 'Native output unexpectedly requires glibc newer than 2.28' >&2
    exit 1
fi
(cd "$native_output" && sha256sum dbeaver lib/*.so > SHA256SUMS)
