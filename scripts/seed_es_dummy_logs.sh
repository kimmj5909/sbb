#!/usr/bin/env bash
# Elasticsearch 개발 클러스터에 웹/WAS 로그 더미 데이터를 주입하는 스크립트.
# - ES는 `xpack.security.enabled: false` 상태로 로컬(기본 9200)에서 기동돼 있어야 합니다.
# - ILM/템플릿이 이미 적용된 상태를 전제로 하며, 기본 인덱스 접두사는 logs-web 입니다.

set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
INDEX_PREFIX="${INDEX_PREFIX:-logs-web}"

echo "[INFO] target ES: ${ES_URL}"

# 기본 헬스 체크로 ES 기동 여부 확인.
if ! curl -s -o /dev/null "${ES_URL}"; then
  echo "[ERROR] ${ES_URL} 에 연결할 수 없습니다. Elasticsearch가 기동 중인지 확인하세요." >&2
  exit 1
fi

# 날짜 기준 더미 데이터 생성(오늘/어제/그제).
day0=$(date -u +%Y-%m-%d)
day1=$(date -u -d "1 day ago" +%Y-%m-%d)
day2=$(date -u -d "2 days ago" +%Y-%m-%d)

bulk_target="${ES_URL}/${INDEX_PREFIX}/_bulk"

tmp_payload="$(mktemp)"
trap 'rm -f "${tmp_payload}"' EXIT

# 데이터 스트림 템플릿을 사용하는 경우 index op_type이 거부되므로 create 액션으로 bulk 요청을 구성한다.
cat > "${tmp_payload}" <<EOF
{ "create": {} }
{ "@timestamp":"${day2}T00:12:00Z","log_type":"web","web_conno":2001,"request_rul":"/login","user_id":"u10","user_ip":"203.0.113.1","first_write_date":"${day2}T00:12:00Z","first_write_user":"system","last_update_date":"${day2}T00:12:10Z","last_update_user":"system","message":"login page view","service":"frontend","level":"INFO" }
{ "create": {} }
{ "@timestamp":"${day2}T01:05:22Z","log_type":"app","web_conno":2002,"request_rul":"/api/v1/orders","user_id":"u11","user_ip":"198.51.100.7","first_write_date":"${day2}T01:05:22Z","first_write_user":"system","last_update_date":"${day2}T01:06:00Z","last_update_user":"system","message":"order list","service":"backend","level":"INFO" }
{ "create": {} }
{ "@timestamp":"${day1}T03:14:09Z","log_type":"web","web_conno":2003,"request_rul":"/","user_id":null,"user_ip":"192.0.2.88","first_write_date":"${day1}T03:14:09Z","first_write_user":"system","last_update_date":"${day1}T03:14:09Z","last_update_user":"system","message":"home","service":"frontend","level":"INFO" }
{ "create": {} }
{ "@timestamp":"${day1}T05:20:11Z","log_type":"access","web_conno":2004,"request_rul":"/static/js/app.js","user_id":null,"user_ip":"192.0.2.55","first_write_date":"${day1}T05:20:11Z","first_write_user":"system","last_update_date":"${day1}T05:20:11Z","last_update_user":"system","message":"static asset","service":"gateway","level":"INFO" }
{ "create": {} }
{ "@timestamp":"${day0}T08:45:00Z","log_type":"app","web_conno":2005,"request_rul":"/api/v1/payments","user_id":"u12","user_ip":"203.0.113.9","first_write_date":"${day0}T08:45:00Z","first_write_user":"system","last_update_date":"${day0}T08:45:30Z","last_update_user":"system","message":"payment list","service":"backend","level":"INFO" }
{ "create": {} }
{ "@timestamp":"${day0}T10:02:42Z","log_type":"web","web_conno":2006,"request_rul":"/logout","user_id":"u13","user_ip":"198.51.100.23","first_write_date":"${day0}T10:02:42Z","first_write_user":"system","last_update_date":"${day0}T10:03:00Z","last_update_user":"system","message":"user logout","service":"frontend","level":"INFO" }
EOF

echo "[INFO] bulk payload 준비 완료: ${tmp_payload}"

response="$(curl -s -X POST "${bulk_target}" -H 'Content-Type: application/json' --data-binary @"${tmp_payload}")"

if echo "${response}" | grep -q '"errors":false'; then
  echo "[INFO] 더미 데이터 주입 완료."
else
  echo "[ERROR] Bulk API 호출 실패. 응답을 확인하세요." >&2
  echo "${response}"
  exit 1
fi

# 주입된 최신 문서 5건 확인용.
curl -s -X POST "${ES_URL}/logs-*/_search" -H 'Content-Type: application/json' -d '{
  "size": 5,
  "sort": [ { "@timestamp": { "order": "desc" } } ]
}' | sed 's/},{/},\n{/g'
