# Login to the center API for scripts: everything but the public client gateway needs a NETWORK_ADMIN token.
# Source it after .env is loaded and CENTER is set:   . "$ROOT/scripts/center-auth.sh"
#   center_curl GET /api/v1/network/overview      — curl -s to the center with the admin token
#   "$CENTER_TOKEN"                                 — the token itself (empty if login failed)
# Credentials: CENTER_ADMIN_LOGIN (default network), CENTER_ADMIN_PASSWORD from .env.
# shellcheck shell=bash

CENTER=${CENTER:-http://localhost:8081}
CENTER_ADMIN_LOGIN=${CENTER_ADMIN_LOGIN:-network}
CENTER_ADMIN_PASSWORD=${CENTER_ADMIN_PASSWORD:-}

center_login() { # → sets CENTER_TOKEN; returns 1 if the center did not issue a token
  CENTER_TOKEN=$(curl -s --max-time 5 -X POST "$CENTER/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg l "$CENTER_ADMIN_LOGIN" --arg p "$CENTER_ADMIN_PASSWORD" '{login:$l,password:$p}')" \
    | jq -r '.access_token // empty' 2>/dev/null)
  [ -n "$CENTER_TOKEN" ]
}

center_curl() { # center_curl METHOD PATH [curl-args...]
  local method=$1 path=$2; shift 2
  [ -n "${CENTER_TOKEN:-}" ] || center_login || true
  curl -s -X "$method" "$CENTER$path" -H "Authorization: Bearer ${CENTER_TOKEN:-}" "$@"
}

center_login || true
