#!/usr/bin/env bash
# CSV and XML .jtl; the percentiles are checked against a series whose answer is obvious.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. CSV: 10 samples 100..1000 ms, one failure
dir="$(mktemp -d)"
{
  echo "timeStamp,elapsed,label,responseCode,success"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    ok=true; [ "$i" = "10" ] && ok=false
    echo "$((1700000000000 + i)),$((i * 100)),GET /jars,$([ "$i" = "10" ] && echo 500 || echo 200),$ok"
  done
} >"$dir/run.jtl"
printf '{"maxErrorPercent": "0"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" samples)" = "10" ] || fail "samples: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "1" ] || fail "errors: $(cat "$dir/output.json")"
[ "$(output "$dir" avgMs)" = "550" ] || fail "average: $(cat "$dir/output.json")"
[ "$(output "$dir" p90Ms)" = "900" ] || fail "p90: $(cat "$dir/output.json")"
[ -f "$dir/out/jtl/index.html" ] || fail "no report"

# 2. a limit on the error rate fails the build
dir="$(mktemp -d)"
printf 'timeStamp,elapsed,label,success\n1,10,a,false\n2,20,a,false\n3,30,a,true\n' >"$dir/x.jtl"
printf '{"maxErrorPercent": "10"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "66%% errors over a 10%% limit must fail"
grep -q "the limit is 10.00%" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# 3. XML flavour
dir="$(mktemp -d)"
cat >"$dir/run.jtl" <<'XML'
<testResults><httpSample t="200" s="true" lb="GET /health"/><httpSample t="400" s="false" lb="GET /health"/></testResults>
XML
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "xml run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" samples)" = "2" ] || fail "xml samples: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "1" ] || fail "xml errors: $(cat "$dir/output.json")"

# 4. no sample is a clear error
dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no jtl must fail"
grep -q "no .jtl file matched" "$dir/output.json" || fail "message not readable"

echo "smoke ok: CSV stats, error-rate gate, XML flavour, no report"
