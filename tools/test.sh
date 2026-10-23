#!/usr/bin/env bash
# Plain JVM tests for the parts of the app that don't touch Android (tools/test/). tools/check.sh runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf out/test && mkdir -p out/test
javac -encoding UTF-8 -source 8 -target 8 -nowarn -d out/test app/src/io/freemytv/FootballRules.java tools/test/FootballRulesTest.java
java -cp out/test io.freemytv.FootballRulesTest
