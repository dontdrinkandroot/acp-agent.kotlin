#!/bin/bash
# Prints the failure messages from the latest JUnit XML test reports.
for xml in build/test-results/test/TEST-*.xml; do
  [ -e "$xml" ] || continue
  failures=$(grep -c "<failure" "$xml" 2>/dev/null || true)
  [ "$failures" -gt 0 ] || continue
  echo "=== $xml ($failures failures)"
  sed -n 's/.*message="\([^"]*\)".*/FAILURE: \1/p' "$xml" | head -10
  # The full message body lives in the <failure> element text.
  awk '/<failure/{flag=1} flag{print} /<\/failure>/{flag=0}' "$xml" | sed 's/&#10;/\n/g; s/&#9;/  /g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g' | head -40
done
