#!/usr/bin/env bash
# Real WKWebView + JNI regression; requires a previously built Java runtime.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=debug/jdk25_select.sh
. "$repo/debug/jdk25_select.sh"
JAVA_HOME="$(macapk_jdk_home)"
framework="$repo/runtime/build/lib/android-framework.jar"
loader="$repo/runtime/build/lib/macapk-loader.jar"
if [[ ! -f "$framework" || ! -f "$loader" ]]; then
  echo "Build the Java runtime first: debug/build_java_only.sh" >&2
  exit 2
fi
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/macapk-webview.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/classes"
"$JAVA_HOME/bin/javac" -cp "$framework:$loader" -d "$test_dir/classes" "$repo/debug/WebViewInterceptSmoke.java"
clang++ -std=c++17 -fobjc-arc -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  "$repo/debug/webview_intercept_smoke.mm" -L"$JAVA_HOME/lib/server" \
  -Wl,-rpath,"$JAVA_HOME/lib/server" -ljvm -framework AppKit -framework MetalKit \
  -framework QuartzCore -framework WebKit -framework CFNetwork -o "$test_dir/smoke"
export MACAPK_ANDROID_ROOT="$test_dir/android"
classpath="$test_dir/classes:$framework:$loader"
MACAPK_SMOKE_API=1 "$test_dir/smoke" "$classpath"
if MACAPK_WEBVIEW_INTERCEPT=0 "$test_dir/smoke" "$classpath" > "$test_dir/baseline.log" 2>&1; then
  echo "FAIL: gate-off baseline unexpectedly intercepted HTTPS" >&2
  exit 1
fi
if ! rg -q 'FAIL WebViewInterceptSmoke: load failed:' "$test_dir/baseline.log"; then
  cat "$test_dir/baseline.log" >&2
  exit 1
fi
echo 'PASS gate-off baseline: HTTPS interception remains disabled'
# A disposable, untrusted local certificate proves fallback never accepts a
# certificate merely because the request came through an Android interceptor.
MACAPK_SMOKE_CERT_PASSWORD="$(uuidgen)"
export MACAPK_SMOKE_CERT_PASSWORD
export MACAPK_SMOKE_KEYSTORE="$test_dir/untrusted.p12"
"$JAVA_HOME/bin/keytool" -genkeypair -alias smoke -keyalg RSA -keysize 2048 \
  -validity 1 -dname CN=127.0.0.1 -ext SAN=ip:127.0.0.1,DNS:localhost \
  -keystore "$MACAPK_SMOKE_KEYSTORE" -storetype PKCS12 \
  -storepass:env MACAPK_SMOKE_CERT_PASSWORD -keypass:env MACAPK_SMOKE_CERT_PASSWORD \
  > "$test_dir/certificate.log" 2>&1
MACAPK_WEBVIEW_INTERCEPT=1 "$test_dir/smoke" "$classpath"
MACAPK_WEBVIEW_INTERCEPT=1 MACAPK_SMOKE_HTTP=1 "$test_dir/smoke" "$classpath"
MACAPK_WEBVIEW_INTERCEPT=1 MACAPK_SMOKE_CANCEL=1 "$test_dir/smoke" "$classpath"
