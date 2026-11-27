#!/usr/bin/env bash
set -euo pipefail

context="$(mktemp -d "${RUNNER_TEMP:-/tmp}/api-image-context.XXXXXX")"
container=""
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
    rm -rf -- "$context"
}
trap cleanup EXIT

mkdir "$context/input" "$context/output"
install -m 0644 target/API-0.0.1-SNAPSHOT.jar "$context/input/app.jar"
docker build --target ci-runtime --file "$PWD/Dockerfile" \
    --tag brafurries-api:layer-check "$context/input"
test "$(docker image inspect --format '{{.Config.User}}' brafurries-api:layer-check)" = 'app:app'
test "$(docker image inspect --format '{{json .Config.Entrypoint}}' brafurries-api:layer-check)" = '["java","-Djava.io.tmpdir=/app/tmp","-jar","/app/app.jar"]'
docker run --rm --entrypoint sh brafurries-api:layer-check -c 'test "$(id -u)" = 10001 && test -w /app/tmp && test -r /app/app.jar'
container="$(docker create brafurries-api:layer-check)"
docker cp "$container:/app/." "$context/output/"
docker rm "$container" >/dev/null
container=""
python3 scripts/ci/check_layered_jar.py "$context/input/app.jar" "$context/output"

# Change only an application resource in a disposable copy of the tested archive.
# All preceding image layer content digests must stay identical.
python3 - "$context/input/app.jar" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "a") as jar:
    jar.writestr("BOOT-INF/classes/ci-layer-reuse-probe.txt", "application-only change")
PY
docker build --target ci-runtime --file "$PWD/Dockerfile" \
    --tag brafurries-api:layer-probe "$context/input"
docker image inspect --format '{{json .RootFS.Layers}}' brafurries-api:layer-check > "$context/before.json"
docker image inspect --format '{{json .RootFS.Layers}}' brafurries-api:layer-probe > "$context/after.json"
python3 - "$context/before.json" "$context/after.json" <<'PY'
import json
import sys
with open(sys.argv[1]) as f:
    before = json.load(f)
with open(sys.argv[2]) as f:
    after = json.load(f)
assert len(before) == len(after) and len(before) > 1
assert before[:-1] == after[:-1], "Application-only change invalidated earlier layers"
assert before[-1] != after[-1], "Application layer did not change"
print(f"Reused {len(before)-1} preceding content layers; only application layer changed.")
PY
docker history --no-trunc --format '{{.Size}} {{.CreatedBy}}' brafurries-api:layer-check
