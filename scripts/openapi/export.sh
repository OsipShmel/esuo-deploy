#!/usr/bin/env bash
# Выгрузка OpenAPI с запущенного стенда в docs/openapi/{node,center}.yaml.
#   ./scripts/openapi/export.sh                       # all-in-one: нода b01 :8080, центр :8081
#   NODE_URL=http://host:8080 CENTER_URL=http://host:8081 ./scripts/openapi/export.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
NODE_URL=${NODE_URL:-http://localhost:${NODE_PORT:-8080}}
CENTER_URL=${CENTER_URL:-http://localhost:${CENTER_PORT:-8081}}
OUT=docs/openapi
mkdir -p "$OUT"

fetch() {
  local name=$1 url=$2 tmp
  tmp=$(mktemp)
  curl -fsS --max-time 20 "$url/v3/api-docs.yaml" -o "$tmp" || { echo "FAIL $name: $url недоступен"; rm -f "$tmp"; return 1; }
  grep -q '^openapi: 3\.1' "$tmp" || { echo "FAIL $name: не OpenAPI 3.1"; rm -f "$tmp"; return 1; }
  # camelCase в свойствах схем означает, что ModelResolver со snake_case не подхватился.
  if grep -En '^ {8}[a-z]+[A-Z][A-Za-z]*:$' "$tmp" | grep -v -E 'additionalProperties|oneOf|anyOf|allOf|readOnly|writeOnly|minLength|maxLength|minItems|maxItems|uniqueItems|exclusiveMinimum|exclusiveMaximum|multipleOf|minProperties|maxProperties|contentMediaType|contentEncoding|patternProperties|unevaluatedProperties|propertyNames|dependentRequired|dependentSchemas|prefixItems|const|examples|externalDocs' | head -3 | grep -q .; then
    echo "WARN $name: в схемах есть camelCase-поля — проверьте OpenApiConfig"
  fi
  mv "$tmp" "$OUT/$name.yaml"
  echo "OK   $name → $OUT/$name.yaml ($(wc -l < "$OUT/$name.yaml") строк)"
}

rc=0
fetch node "$NODE_URL" || rc=1
fetch center "$CENTER_URL" || rc=1
exit $rc
