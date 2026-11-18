#!/usr/bin/env bash
set -euo pipefail
out="${1:?Set the evidence directory}"
expected="${2:-15}"
mkdir -p "$out"
report=app/build/test-results/testReleaseUnitTest/TEST-io.theficos.ereader.ui.settings.SettingsViewModelTest.xml
rm -f "$report"
if ! timeout 600s ./gradlew --max-workers=3 :app:testReleaseUnitTest --tests '*.SettingsViewModelTest' > "$out/warmup.log" 2>&1; then
  if [[ -s "$report" ]]; then cp "$report" "$out/warmup-failed.xml"; fi
  tail -40 "$out/warmup.log"
  exit 1
fi
if [[ "${Q1_BUSY:-0}" == 1 ]]; then
  yes > /dev/null &
  pressure_pid=$!
  trap 'kill "$pressure_pid" 2>/dev/null || true; wait "$pressure_pid" 2>/dev/null || true' EXIT
fi

for run in $(seq -w 1 20); do
  rm -f "$report"
  status=0
  timeout 300s ./gradlew --max-workers=3 :app:testReleaseUnitTest --tests '*.SettingsViewModelTest' --rerun > "$out/${run}.log" 2>&1 || status=$?
  if [[ -s "$report" ]]; then cp "$report" "$out/${run}.xml"; fi
  if (( status != 0 )); then tail -40 "$out/${run}.log"; exit "$status"; fi
  python3 - "$out/${run}.xml" "$expected" <<'PY'
import sys
from xml.etree import ElementTree as ET
suite = ET.parse(sys.argv[1]).getroot()
assert int(suite.attrib["tests"]) == int(sys.argv[2]), suite.attrib
assert all(int(suite.attrib[label]) == 0 for label in ("failures", "errors", "skipped")), suite.attrib
print(sys.argv[1], suite.attrib["time"])
PY
done
