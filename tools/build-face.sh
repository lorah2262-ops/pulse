#!/bin/sh
# Minify watch/Pulse.js -> watch/Pulse.min.js, shorten names, check ES5, copy into the app.
set -e
cd "$(dirname "$0")/.."
ESBUILD="npx --yes esbuild@0.24.0"
$ESBUILD watch/Pulse.js --minify --supported:template-literal=false \
  --supported:arrow=false --supported:object-extensions=false --log-level=warning > /tmp/flat.min.js
python3 tools/shrink.py /tmp/flat.min.js watch/Pulse.min.js
sed 's/^const subscribe/var subscribe/' watch/Pulse.min.js > /tmp/es5check.js
$ESBUILD /tmp/es5check.js --target=es5 --log-level=warning > /dev/null && echo "ES5 ok"
cp watch/Pulse.min.js app/src/main/assets/Pulse.min.js
wc -c watch/Pulse.min.js

# the Day Pack face (Pulse fills in the events when it installs it)
$ESBUILD watch/DayPack.js --minify --supported:template-literal=false --supported:arrow=false --supported:object-extensions=false --log-level=warning > /tmp/dp.js
python3 tools/shrink.py /tmp/dp.js watch/DayPack.min.js
cp watch/DayPack.min.js app/src/main/assets/DayPack.min.js
