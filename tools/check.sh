#!/usr/bin/env bash
# What CI runs on every pull request (.github/workflows/check.yml). Run it before you push:
#
#   tools/check.sh           # everything
#   tools/check.sh android   # only the Google TV build and its JVM tests (needs what build.sh needs)
#   tools/check.sh web       # translations and ES5 in web/ (needs Python 3 and Node)
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and
# JDK 17, as the README says. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android|web) ;;
  *) echo "Usage: tools/check.sh [all|android|web]" >&2; exit 2 ;;
esac

if [ "$what" = all ] || [ "$what" = android ]; then
  ./build.sh
  tools/test.sh
fi

if [ "$what" = all ] || [ "$what" = web ]; then
  python3 tools/translate.py --check

  # No LG or Samsung packages: this fork's builds dropped the crypto coins their packages need (app/res/drawable).

  # webOS 3 and Tizen 3 run a Chromium from 2015 to 2017: web/ has to be ES5, in syntax...
  for f in web/*.js; do npx --yes acorn@8.14.0 --ecma5 --silent "$f"; done
  # ...and in what it calls. These parse fine but don't exist there (wrap a feature check in try, as strings.js does).
  if grep -nE '\.(includes|startsWith|endsWith|padStart|padEnd|repeat|find|findIndex|closest)\(|Object\.(assign|entries|values)\(|Array\.from\(|\bPromise\b|\bfetch\(|new (Map|Set|WeakMap)\b' web/*.js; then
    echo "web/ calls something older TVs don't have (above)." >&2
    exit 1
  fi
fi

echo "All checks passed."
