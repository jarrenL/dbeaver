#!/usr/bin/env bash
# Assemble a clean, architecture-specific product. No acceptance plugins or credentials.
set -euo pipefail
product_source=${1:?Usage: assemble.sh PRODUCT_DIRECTORY NATIVE_OUTPUT JRE_TAR_GZ NEW_OUTPUT_DIRECTORY ARCH}
native_source=${2:?Native output directory required}
jre_archive=${3:?JRE archive required}
delivery_output=${4:?A new output directory required}
delivery_arch=${5:?Use aarch64 or x86_64}
case "$delivery_arch" in aarch64|x86_64) ;; *) exit 1;; esac
test ! -e "$delivery_output"
test -f "$product_source/dbeaver.ini"
test -f "$native_source/lib/eclipse_11916.so"
test -f "$jre_archive"
case "$delivery_arch" in
    aarch64) native_machine='ARM aarch64'; java_arch='aarch64';;
    x86_64) native_machine='x86-64'; java_arch='x86_64';;
esac
for native_binary in "$native_source/dbeaver" "$native_source/lib/"*.so; do
    if ! LC_ALL=C file "$native_binary" | grep -Fq "$native_machine"; then
        echo "Wrong ELF architecture: $native_binary (expected $delivery_arch)" >&2; exit 1
    fi
done
if find "$product_source/plugins" -iname '*swtbot*' -o -iname '*acceptance*' | grep -q .; then
    echo 'Refusing to assemble from a test-instrumented product' >&2; exit 1
fi
mkdir -p "$delivery_output"
cp -a "$product_source" "$delivery_output/dbeaver"
delivery_app="$delivery_output/dbeaver"
node "$(dirname "$0")/../configure-launcher.mjs" "$delivery_app"
node "$(dirname "$0")/../install-zh-resources.mjs" "$delivery_app"
test ! -e "$delivery_app/jre"
mkdir "$delivery_app/jre"
tar -xzf "$jre_archive" --strip-components=1 -C "$delivery_app/jre"
grep -Fxq "OS_ARCH=\"$java_arch\"" "$delivery_app/jre/release"
grep -Fxq 'OS_NAME="Linux"' "$delivery_app/jre/release"
test -d "$delivery_app/jre/legal"
cp "$native_source/dbeaver" "$delivery_app/dbeaver"
cp "$native_source/lib/eclipse_11916.so" \
    "$delivery_app/plugins/org.eclipse.equinox.launcher.gtk.linux.${delivery_arch}_1.2.1500.v20250801-0854/eclipse_11916.so"
swt_bundle="$delivery_app/plugins/org.eclipse.swt.gtk.linux.${delivery_arch}_3.134.0.v20260515-1429.jar"
test -f "$swt_bundle"
for component in swt swt-atk swt-awt swt-cairo swt-glx swt-pi3 swt-webkit; do
    jar uf "$swt_bundle" -C "$native_source/lib" "lib${component}-gtk-4973r12.so"
done
# The original Eclipse signature cannot authenticate our rebuilt JNI libraries.
# Do not ship an invalid/stale upstream signature or imply upstream certification.
zip -dq "$swt_bundle" META-INF/ECLIPSE_.SF META-INF/ECLIPSE_.RSA
chmod 755 "$delivery_app/dbeaver"
cp "$native_source/build-environment.txt" "$native_source/elf-versions.txt" "$delivery_output/"
cp "$native_source/SHA256SUMS" "$delivery_output/NATIVE-SHA256SUMS"
printf '%s\n' "architecture=$delivery_arch" 'swt_source=v4973r12' 'launcher_source=R4_37' \
    'native_libraries=locally-rebuilt-unsigned' 'jdbc_driver=customer-supplied' > "$delivery_output/BUILD-INFO.txt"
echo "Assembled $delivery_output; add release documentation/licenses and run clean-product acceptance before archiving."
