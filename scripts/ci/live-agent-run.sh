#!/usr/bin/env bash
# Runs the live agent tests (app/src/androidTest/.../live) on a booted emulator; started by
# .github/workflows/live-agent-tests.yml. Each provider key arrives in this script's environment
# and the run's gradle.properties (see below), and goes on to the instrumentation as an argument,
# never in a process's arguments on the runner (only in `am instrument` on the throwaway emulator),
# so it shows up in no process listing or Gradle log here. Never add
# `set -x` here, and never echo a key.
#
# A test whose key is missing skips itself. A key that is set but whose test still skipped means
# the arguments never reached the device, which fails the run rather than passing it silently.
set -uo pipefail

status=0
# The keys go in the checkout's gradle.properties for this run only, which Gradle reads exactly as
# it reads -P. Not ORG_GRADLE_PROJECT_ variables: their names would hold dots, and gradlew's
# /bin/sh (dash on Ubuntu) drops such variables before Java starts. The file is restored on exit
# or when the step's timeout stops the script, and is never uploaded.
cp gradle.properties gradle.properties.orig
trap 'mv -f gradle.properties.orig gradle.properties' EXIT
trap 'exit 143' INT TERM
python3 -I - <<'PY'
import os

prefix = "android.testInstrumentationRunnerArguments."


def escape(value):
    """A java.util.Properties value: backslashes, separators and leading spaces escaped."""
    out = value.replace("\\", "\\\\").replace("\n", "\\n").replace(":", "\\:").replace("=", "\\=")
    return "\\ " + out[1:] if out.startswith(" ") else out


with open("gradle.properties", "a", encoding="utf-8") as properties:
    properties.write("\n")
    for source, argument in (("ZAI_API_KEY", "zaiKey"), ("ANTHROPIC_API_KEY", "anthropicKey"), ("OPENAI_API_KEY", "openaiKey")):
        value = os.environ.get(source, "").strip()
        if value:
            properties.write(f"{prefix}{argument}={escape(value)}\n")
PY

args=(
  -Pandroid.testInstrumentationRunnerArguments.package=com.yugahashimoto.andcode.live
  -Pandroid.testInstrumentationRunnerArguments.liveAgentTests=true
)
# No model means auto: the test picks a -flash model the plan offers, else the first.
[ -n "${ZAI_MODEL:-}" ] && args+=("-Pandroid.testInstrumentationRunnerArguments.zaiModel=$ZAI_MODEL")

echo "::group::Live agent tests"
./gradlew :app:connectedGithubDebugAndroidTest "${args[@]}" || status=1
echo "::endgroup::"

results=app/build/outputs/androidTest-results/connected
# Prints how many test cases of class $1 ran (were not skipped) in the JUnit reports under
# $results. The report names hold the device name, spaces included, so Python finds them itself.
ran_count() {
  python3 -I - "$1" "$results" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

wanted, ran = "com.yugahashimoto.andcode.live." + sys.argv[1], 0
reports = glob.glob(os.path.join(sys.argv[2], "**", "TEST-*.xml"), recursive=True)
if not reports:
    sys.exit("no JUnit reports were written")
for path in reports:
    for case in ET.parse(path).iter("testcase"):
        if case.get("classname") == wanted and case.find("skipped") is None:
            ran += 1
print(ran)
PY
}
skipped_with_key() {
  # $1: the key's variable, $2: the test class that needs it.
  [ -n "$(printenv "$1" || true)" ] || return 0
  local count
  if ! count=$(ran_count "$2") || ! [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "::error::Could not read the test reports to check that $2 ran"
    status=1
  elif [ "$count" = "0" ]; then
    echo "::error::$1 is set but $2 did not run: the key did not reach the device"
    status=1
  fi
}
skipped_with_key ZAI_API_KEY LiveOpenCodeZaiTest
skipped_with_key ANTHROPIC_API_KEY LiveClaudeCodeTest
skipped_with_key OPENAI_API_KEY LiveCodexTest

exit "$status"
