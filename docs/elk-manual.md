# ELK(Filebeat + Logstash + Elasticsearch + Kibana) 설치 및 SBB 연동 가이드

이 문서는 SBB 프로젝트의 로그 수집/조회 목적의 ELK 연동을 “로컬/단일 노드” 기준으로 정리한 매뉴얼입니다.

## 1) 구성 개요(데이터 흐름)

권장 기본 흐름(운영/검증 공통):

1. **SBB**가 파일로 로그를 남김
	- 애플리케이션 로그: `logging.file.name` (기본 `./logs/sbb-app.log`)
	- (선택) HTTP 요청 구조화 로그(JSONL): `sbb.http-log.enabled=true`, `sbb.http-log.file` (기본 `./logs/http-log.jsonl`)
2. **Filebeat**가 로그 파일을 tail 하여 전송
3. **Logstash**가 수신(beats input 5044) 후 가공/필터링하여 Elasticsearch에 적재
4. **Elasticsearch**에 인덱싱(예: `logs-web-2026.03.03`)
5. **Kibana**에서 검색/대시보드 조회

참고: SBB는 Elasticsearch 클라이언트를 포함하고 있어(검색/적재용), “애플리케이션 → Elasticsearch 직접 적재”도 가능합니다. 다만 **Filebeat→Logstash 파이프라인과 중복 적재**가 되지 않도록 한 가지 방식으로 통일하세요.

## 2) 전제 조건

- OS: Linux(Ubuntu) / WSL2(Ubuntu) 기준(Windows 네이티브 설치는 별도)
- 포트:
	- Elasticsearch: `9200`
	- Kibana: `5601`
	- Logstash(beats input): `5044`
- SBB 로그 파일이 생성되는 위치가 Filebeat가 읽을 수 있는 권한/경로인지 확인

## 3) 설치(권장: 패키지/서비스 설치)

### 3-1. Elasticsearch 설치(단일 노드)

Elasticsearch 8.x는 기본적으로 보안 기능(xpack security)을 포함합니다.

#### A) tar.gz(레포에 파일이 있는 경우)

레포 루트에 `elasticsearch-8.14.1-linux-x86_64.tar.gz`가 존재하면 다음처럼 설치할 수 있습니다.

```bash
tar -xzf elasticsearch-8.14.1-linux-x86_64.tar.gz
cd elasticsearch-8.14.1
```

단일 노드(개발용) 최소 설정 예시(`config/elasticsearch.yml`):

```yaml
cluster.name: sbb-local
node.name: sbb-node-1
network.host: 0.0.0.0
http.port: 9200
discovery.type: single-node
```

실행:

```bash
./bin/elasticsearch
```

#### B) apt/systemd(일반 서버 권장)

배포판에 맞춰 Elastic 공식 가이드를 통해 설치한 뒤, `/etc/elasticsearch/elasticsearch.yml`을 수정하고 systemd로 기동합니다.

```bash
sudo systemctl enable --now elasticsearch
sudo systemctl status elasticsearch
```

### 3-2. Kibana 설치

Kibana 설정 파일(일반적으로 `/etc/kibana/kibana.yml`)에 Elasticsearch 접속 정보를 넣습니다.

```yaml
server.port: 5601
server.host: "0.0.0.0"
elasticsearch.hosts: ["http://localhost:9200"]
```

기동:

```bash
sudo systemctl enable --now kibana
```

브라우저 접속: `http://localhost:5601`

### 3-3. Logstash 설치 및 파이프라인 구성

레포 루트에 `logstash-8.14.1-amd64.deb`가 있으면(우분투/데비안) 다음과 같이 설치할 수 있습니다.

```bash
sudo dpkg -i ./logstash-8.14.1-amd64.deb
sudo apt-get -f install -y
```

파이프라인 예시(`/etc/logstash/conf.d/sbb-filebeat.conf`):

```conf
input {
  beats {
    port => 5044
  }
}

filter {
  # Filebeat가 JSON을 파싱하지 않고 raw message로 보낼 때를 대비해,
  # message가 JSON 형태면 파싱을 시도합니다(실패 시 원문 유지).
  json {
    source => "message"
    target => "event"
    skip_on_invalid_json => true
  }
}

output {
  elasticsearch {
    hosts => ["http://localhost:9200"]

    # 인덱스 네이밍(일 단위 롤링 예시)
    index => "logs-web-%{+YYYY.MM.dd}"

    # 보안 활성화 시 인증 필요(아래 중 1개만 사용)
    user => "${ELASTIC_USERNAME}"
    password => "${ELASTIC_PASSWORD}"
  }
}
```

systemd 환경변수(예: `/etc/default/logstash` 또는 `/etc/sysconfig/logstash`)에 `ELASTIC_USERNAME`, `ELASTIC_PASSWORD`를 설정한 뒤 기동:

```bash
sudo systemctl enable --now logstash
sudo systemctl status logstash
```

### 3-4. Filebeat 설치 및 입력 설정

레포 루트에 `filebeat-8.14.1-amd64.deb`가 있으면:

```bash
sudo dpkg -i ./filebeat-8.14.1-amd64.deb
sudo apt-get -f install -y
```

Filebeat 설정(`/etc/filebeat/filebeat.yml`) 핵심 예시:

```yaml
filebeat.inputs:
  - type: filestream
    id: sbb-app-log
    enabled: true
    paths:
      - /var/log/sbb/sbb-app.log

  - type: filestream
    id: sbb-http-jsonl
    enabled: true
    paths:
      - /var/log/sbb/http-log.jsonl
    parsers:
      - ndjson:
          target: ""
          add_error_key: true

output.logstash:
  hosts: ["localhost:5044"]
```

설정 검증/기동:

```bash
sudo filebeat test config
sudo filebeat test output
sudo systemctl enable --now filebeat
sudo systemctl status filebeat
```

## 4) SBB 설정(로그 파일 + Elasticsearch 접속)

SBB의 기본 설정 키는 `src/main/resources/application.properties`에 정리돼 있습니다.

### 4-1. 로그 파일 경로(권장: /var/log 아래로 고정)

운영/WSL에서 Filebeat가 수집하기 쉬운 경로로 지정합니다.

예시(환경변수로 오버라이드):

- `SBB_LOG_FILE=/var/log/sbb/sbb-app.log`
- `SBB_HTTP_LOG_ENABLED=true`
- `SBB_HTTP_LOG_FILE=/var/log/sbb/http-log.jsonl`

### 4-2. Elasticsearch 접속(검색/직접 적재용)

SBB는 아래 키를 사용합니다.

- `sbb.elasticsearch.hosts` (예: `http://localhost:9200`)
- `sbb.elasticsearch.username`
- `sbb.elasticsearch.password`
- `sbb.elasticsearch.api-key` (설정 시 BasicAuth보다 우선)
- `sbb.elasticsearch.index-prefix` (예: `logs-web`)
- `sbb.kibana.url` (예: `http://localhost:5601`)

환경변수 예시:

```bash
export ELASTIC_HOSTS="http://localhost:9200"
export ELASTIC_USERNAME="elastic"
export ELASTIC_PASSWORD="PUT_YOUR_PASSWORD_HERE"
export ELASTIC_API_KEY=""
export ELASTIC_INDEX_PREFIX="logs-web"
export KIBANA_URL="http://localhost:5601"
```

## 5) 기동 순서(권장)

1. Elasticsearch 기동(9200 확인)
2. Kibana 기동(5601 확인)
3. Logstash 기동(5044 listen 확인)
4. Filebeat 기동(Logstash로 이벤트 전송 확인)
5. SBB 기동(로그 파일 생성 확인)

포트 확인:

```bash
ss -lntp | egrep ':(9200|5601|5044)\b' || true
```

## 6) 동작 확인(체크리스트)

### 6-1. Elasticsearch 상태

```bash
curl -sS http://localhost:9200
curl -sS http://localhost:9200/_cluster/health?pretty
```

보안 활성화로 인증이 필요하면:

```bash
curl -sS -u elastic:PUT_YOUR_PASSWORD_HERE http://localhost:9200/_cluster/health?pretty
```

### 6-2. 인덱스 생성 확인

```bash
curl -sS -u elastic:PUT_YOUR_PASSWORD_HERE "http://localhost:9200/_cat/indices?v"
```

### 6-3. Filebeat/Logstash 로그 확인

```bash
sudo journalctl -u filebeat -n 200 --no-pager
sudo journalctl -u logstash -n 200 --no-pager
```

### 6-4. Kibana에서 조회

1. Kibana 접속: `http://localhost:5601`
2. Index Pattern(또는 Data View) 생성: `logs-web-*`
3. Discover에서 검색 확인

## 7) 자주 발생하는 이슈/해결

### 7-1. `localhost`/`127.0.0.1` 접속 불안정(WSL/IPv6)

환경에 따라 `localhost`가 IPv6(::1)로 해석되거나 WSL 포트 포워딩 방식 차이로 접속이 흔들릴 수 있습니다.

- SBB 설정은 기본적으로 `http://localhost:9200`을 사용하고, 필요 시 `ELASTIC_HOSTS`로 명시 오버라이드합니다.
- 동일하게 Filebeat/Logstash/Kibana도 `localhost` vs `127.0.0.1`을 바꿔가며 점검하세요.

### 7-2. 보안(xpack security) 활성화로 인증 실패

Elasticsearch 8.x에서 보안이 켜져 있으면, 다음 중 하나가 필요합니다.

- BasicAuth: `ELASTIC_USERNAME`/`ELASTIC_PASSWORD`
- ApiKey: `ELASTIC_API_KEY`

SBB는 ApiKey가 설정되면 BasicAuth보다 우선합니다.

### 7-3. ES가 HTTPS/TLS로 떠 있는 경우

클러스터가 HTTPS로 구성돼 있으면 `http://` 대신 `https://`로 맞추고, CA 인증서/핑거프린트를 신뢰하도록 설정해야 합니다.

- (간단 점검) `curl -vk https://localhost:9200`
- 인증서 오류가 나면 ES 설치 시 생성된 CA를 기준으로 각 구성 요소가 신뢰하도록 설정하세요.

### 7-4. Filebeat가 로그 파일을 못 읽는 경우

- 경로가 실제로 존재하는지 확인(`/var/log/sbb/...`)
- 권한 확인(서비스 실행 사용자)
- SELinux/AppArmor 정책 확인(해당 시)

