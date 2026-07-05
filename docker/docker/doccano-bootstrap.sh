#!/bin/sh
set -eu

# doccano-bootstrap.sh — idempotent bootstrap of a Doccano SequenceLabeling project + span-types.
# Reads project + labels definition from /bootstrap/doccano-labels.json.
# Re-runs are safe: skips creation if the project or label already exists.

LABELS_FILE="${LABELS_FILE:-/bootstrap/doccano-labels.json}"
DOCCANO_URL="${DOCCANO_URL:-http://doccano-backend:8000}"
DOCCANO_USERNAME="${DOCCANO_USERNAME:?DOCCANO_USERNAME is required}"
DOCCANO_PASSWORD="${DOCCANO_PASSWORD:?DOCCANO_PASSWORD is required}"
PROJECT_NAME_OVERRIDE="${DOCCANO_PROJECT_NAME:-}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-90}"
WAIT_INTERVAL_SECONDS="${WAIT_INTERVAL_SECONDS:-2}"

COOKIE_JAR="/tmp/doccano-cookies.txt"

log() {
  echo "[doccano-bootstrap] $*"
}

die() {
  log "ERROR: $*"
  exit 1
}

ensure_tools() {
  if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    log "Installing curl and jq..."
    apk add --no-cache curl jq >/dev/null
  fi
}

read_csrf_token() {
  awk '$6 == "csrftoken" { print $7 }' "$COOKIE_JAR" | tail -n 1
}

# Wait until Doccano backend answers /v1/me with 200/401/403 (any of these means
# the Django stack is up; only network errors / 5xx mean it's not ready yet).
wait_for_backend() {
  log "Waiting for Doccano backend at $DOCCANO_URL ..."
  i=0
  while [ "$i" -lt "$WAIT_MAX_ATTEMPTS" ]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$DOCCANO_URL/v1/me" || true)"
    case "$code" in
      200|401|403)
        log "Backend ready (HTTP $code on /v1/me)"
        return 0
        ;;
    esac
    i=$((i + 1))
    sleep "$WAIT_INTERVAL_SECONDS"
  done
  die "Backend did not become ready after $((WAIT_MAX_ATTEMPTS * WAIT_INTERVAL_SECONDS))s (last code=$code)"
}

login() {
  log "Logging in as $DOCCANO_USERNAME ..."
  rm -f "$COOKIE_JAR"

  # Step 1: prime the csrftoken cookie. Doccano's /v1/* endpoints don't set
  # csrftoken on GET, but Django's /admin/login/ does (the form needs it).
  curl -s -o /dev/null \
    -c "$COOKIE_JAR" \
    -H "Referer: $DOCCANO_URL" \
    "$DOCCANO_URL/admin/login/" || true

  csrf="$(read_csrf_token)"
  if [ -z "$csrf" ]; then
    die "Could not obtain initial csrftoken from /admin/login/"
  fi

  # Step 2: POST credentials with the csrf header echoed back.
  body=$(jq -n --arg u "$DOCCANO_USERNAME" --arg p "$DOCCANO_PASSWORD" '{username:$u, password:$p}')
  http_code=$(curl -s -o /tmp/login.out -w '%{http_code}' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "Content-Type: application/json" \
    -H "X-CSRFToken: $csrf" \
    -H "Referer: $DOCCANO_URL" \
    -X POST "$DOCCANO_URL/v1/auth/login/" \
    --data "$body")
  if [ "$http_code" != "200" ]; then
    log "Login response body:"
    cat /tmp/login.out || true
    die "Login failed (HTTP $http_code)"
  fi

  csrf="$(read_csrf_token)"
  if [ -z "$csrf" ]; then
    die "No csrftoken cookie present after /v1/auth/login/"
  fi
  log "Login OK"
}

# api_get <path> -> writes body to stdout, fails on non-2xx
api_get() {
  path="$1"
  http_code=$(curl -s -o /tmp/api.out -w '%{http_code}' \
    -b "$COOKIE_JAR" \
    -H "Referer: $DOCCANO_URL" \
    "$DOCCANO_URL$path")
  if [ "$http_code" != "200" ]; then
    log "GET $path failed (HTTP $http_code)"
    cat /tmp/api.out || true
    return 1
  fi
  cat /tmp/api.out
}

# api_post <path> <json_body> -> writes body to stdout, fails on non-2xx
api_post() {
  path="$1"
  payload="$2"
  csrf="$(read_csrf_token)"
  http_code=$(curl -s -o /tmp/api.out -w '%{http_code}' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "Content-Type: application/json" \
    -H "X-CSRFToken: $csrf" \
    -H "Referer: $DOCCANO_URL" \
    -X POST "$DOCCANO_URL$path" \
    --data "$payload")
  case "$http_code" in
    2*) cat /tmp/api.out ;;
    *)
      log "POST $path failed (HTTP $http_code)"
      log "Payload: $payload"
      cat /tmp/api.out || true
      return 1
      ;;
  esac
}

main() {
  ensure_tools

  [ -f "$LABELS_FILE" ] || die "Labels file not found: $LABELS_FILE"

  project_name="$PROJECT_NAME_OVERRIDE"
  if [ -z "$project_name" ]; then
    project_name="$(jq -r '.project.name' "$LABELS_FILE")"
  fi
  project_description="$(jq -r '.project.description' "$LABELS_FILE")"

  log "Target project: $project_name"

  wait_for_backend
  login

  # --- Project (create or reuse) ---
  log "Looking up existing project '$project_name' ..."
  encoded_name="$(printf '%s' "$project_name" | jq -sRr @uri)"
  existing="$(api_get "/v1/projects?q=$encoded_name" || echo '{"results":[]}')"
  project_id="$(echo "$existing" | jq -r --arg n "$project_name" '.results[]? | select(.name == $n) | .id' | head -n 1)"

  if [ -n "$project_id" ] && [ "$project_id" != "null" ]; then
    log "Project already exists (id=$project_id)"
  else
    log "Creating project ..."
    project_payload=$(jq -n \
      --arg name "$project_name" \
      --arg desc "$project_description" \
      '{
        name: $name,
        description: $desc,
        project_type: "SequenceLabeling",
        resourcetype: "SequenceLabelingProject",
        random_order: false,
        collaborative_annotation: true,
        allow_overlapping: false,
        grapheme_mode: false,
        use_relation: false,
        tags: []
      }')
    created="$(api_post "/v1/projects" "$project_payload")" || die "Project creation failed"
    project_id="$(echo "$created" | jq -r '.id')"
    [ -n "$project_id" ] && [ "$project_id" != "null" ] || die "Project creation returned no id"
    log "Project created (id=$project_id)"
  fi

  # --- Span-types (create missing) ---
  log "Fetching existing span-types ..."
  existing_types="$(api_get "/v1/projects/$project_id/span-types" || echo '[]')"

  total=0
  created=0
  skipped=0

  # Iterate labels via jq; emit "text\tcategory" lines.
  jq -r '.labels[] | [.text, .category] | @tsv' "$LABELS_FILE" | while IFS=$(printf '\t') read -r text category; do
    total=$((total + 1))

    color="$(jq -r --arg c "$category" '.category_colors[$c] // "#6B7280"' "$LABELS_FILE")"
    already="$(echo "$existing_types" | jq -r --arg t "$text" '.[]? | select(.text == $t) | .id' | head -n 1)"

    if [ -n "$already" ] && [ "$already" != "null" ]; then
      log "  [skip] $text (already exists, id=$already)"
      skipped=$((skipped + 1))
      continue
    fi

    payload=$(jq -n \
      --arg text "$text" \
      --arg bg "$color" \
      '{
        text: $text,
        background_color: $bg,
        text_color: "#ffffff",
        prefix_key: null,
        suffix_key: null
      }')
    if api_post "/v1/projects/$project_id/span-types" "$payload" >/dev/null; then
      log "  [created] $text ($category, $color)"
      created=$((created + 1))
    else
      die "Failed to create span-type '$text'"
    fi
  done

  # The loop above runs in a subshell because of the pipe; counters won't survive.
  # Recompute totals from the API for the final summary.
  final_types="$(api_get "/v1/projects/$project_id/span-types" || echo '[]')"
  final_count="$(echo "$final_types" | jq 'length')"
  expected_count="$(jq '.labels | length' "$LABELS_FILE")"

  log "Done. project_id=$project_id span_types=$final_count expected=$expected_count"
  if [ "$final_count" -lt "$expected_count" ]; then
    die "Expected $expected_count span-types but project has $final_count"
  fi
}

main "$@"
