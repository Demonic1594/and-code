#!/usr/bin/env bash
# Regenerates THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt from the
# actual resolved Gradle dependency graph, so the list backing THIRD_PARTY_NOTICES.md's Gradle
# dependency summary is generated and verifiable rather than hand-maintained. Run this after any
# dependency change and re-check THIRD_PARTY_NOTICES.md against the diff.
set -euo pipefail

cd "$(dirname "$0")/.."

OUTPUT="THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt"

# The github flavor is the fdroid one plus Firebase, so it covers both. Gradle prints
# "group:name:asked -> resolved" (or "group:name -> resolved" for BOM-managed entries); only the
# resolved version is recorded, and constraint-only lines "(c)" are dropped.
./gradlew :app:dependencies --configuration githubReleaseRuntimeClasspath --console=plain \
  | grep -v ' (c)$' \
  | sed -E \
    -e 's/([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+):[^ ]+ -> ([0-9][a-zA-Z0-9_.-]*)/\1:\2/' \
    -e 's/([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+) -> ([0-9][a-zA-Z0-9_.-]*)/\1:\2/' \
  | grep -oE '[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*' \
  | sort -u \
  > "$OUTPUT"

echo "Wrote $(wc -l < "$OUTPUT" | tr -d ' ') resolved dependency coordinates to $OUTPUT"
