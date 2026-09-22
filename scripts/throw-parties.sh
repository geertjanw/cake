#!/usr/bin/env bash
# Generates realistic traffic: mostly happy parties, a few 90-year-olds (oven timeouts),
# some bad e-mail addresses (partial failures), and a run on lemon cake (out of stock).
# Usage: ./scripts/throw-parties.sh [count] [delay-seconds]
set -u
URL="${PARTY_SERVICE_URL:-http://localhost:8080}"
COUNT="${1:-50}"
DELAY="${2:-2}"

names=(Ada Grace Linus Margaret Alan Barbara Dennis Ken Radia Tim)
flavors=(chocolate vanilla strawberry lemon)
domains=(example.com example.org mail.test)

for i in $(seq 1 "$COUNT"); do
  name=${names[$RANDOM % ${#names[@]}]}
  flavor=${flavors[$RANDOM % ${#flavors[@]}]}

  # Age distribution: mostly 1-70, occasionally 81-100 to trip the oven.
  if (( RANDOM % 10 == 0 )); then age=$((81 + RANDOM % 20)); else age=$((1 + RANDOM % 70)); fi
  year=$(( $(date +%Y) - age ))
  month=$(printf "%02d" $((1 + RANDOM % 12)))
  day=$(printf "%02d" $((1 + RANDOM % 28)))

  guests=""
  n=$((2 + RANDOM % 6))
  for g in $(seq 1 "$n"); do
    if (( RANDOM % 8 == 0 )); then
      email="not-an-email-$g"                          # bad address -> partial failure
    else
      email="guest$g@${domains[$RANDOM % ${#domains[@]}]}"
    fi
    guests="$guests\"$email\","
  done
  guests="[${guests%,}]"

  body=$(printf '{"name":"%s","birthDate":"%s-%s-%s","flavor":"%s","guests":%s}' \
         "$name" "$year" "$month" "$day" "$flavor" "$guests")
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
         -X POST "$URL/parties" -d "$body")
  echo "[$i/$COUNT] $name turning $age, $flavor cake, $n guests -> HTTP $code"
  sleep "$DELAY"
done
