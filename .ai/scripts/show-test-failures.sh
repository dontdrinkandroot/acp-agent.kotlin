#!/bin/bash
# Prints the failure messages from the latest JUnit XML test reports.
# Self-describing: reports what it saw even when everything is green, so a
# silent run is distinguishable from an empty/stale report directory.
shopt -s nullglob
reports=(build/test-results/test/TEST-*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "No test reports found in build/test-results/test - the test task has not run."
  exit 0
fi
newest=$(stat -c '%y' "${reports[@]}" | sort -r | head -1 | cut -d. -f1)
any_failures=0
for xml in "${reports[@]}"; do
  failures=$(grep -c "<failure" "$xml" 2>/dev/null || true)
  [ "$failures" -gt 0 ] || continue
  any_failures=1
  echo "=== $xml ($(stat -c '%y' "$xml" | cut -d. -f1)) ($failures failures)"
  sed -n 's/.*message="\([^"]*\)".*/FAILURE: \1/p' "$xml" | head -10
  # The full message body lives in the <failure> element text.
  awk '/<failure/{flag=1} flag{print} /<\/failure>/{flag=0}' "$xml" | sed 's/&#10;/\n/g; s/&#9;/  /g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/</g; s/&amp;/\&/g' | head -40
    # Debug prints from the test live in <system-out>; echo them so a fault
    # loop does not have to re-drill the XML by hand.
    awk '/<system-out>/{flag=1} flag{print} /<\/system-out>/{flag=0}' "$xml" | sed 's/&#10;/\n/g; s/&#9;/  /g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/</g; s/&amp;/\&/g' | head -30
done
if [ "$any_failures" -eq 0 ]; then
  echo "No failing tests in ${#reports[@]} report files (newest: $newest)"
fi
