# hedwig-spam-gateway

[Hedwig](https://github.com/hs-cheol/hedwig) SMTP 앞단에서 동작하는 독립 스팸/공격 완충 게이트웨이입니다. Hedwig 본체와는 완전히 분리된 별도 프로세스(별도 jar)로 동작하며, Hedwig 코드는 전혀 수정하지 않습니다.

## 왜 필요한가

- 소규모 사이트가 별도 상용 스팸 솔루션 없이도 Hedwig 자체에서 기본적인 스팸/공격 완충 기능을 갖게 한다.
- AIX/SunOS를 포함한 레거시 환경에서도 jar 배포만으로 동일한 설치 경험을 유지한다 (Java 8 호환, K8s/Docker/Redis 등 특정 인프라에 의존하지 않음).
- 게이트웨이가 죽어도 Hedwig 본체는 영향받지 않는다 (프로세스 격리, fail-open 기본).

## 기능

### 1. 인바운드 IP 방어
- 슬라이딩 윈도우 기반 IP별 요청 레이트리밋, 초과 시 자동 밴
- 공유 DB(`hw_ban_list`) 기반 밴 목록 동기화 — 여러 인스턴스가 밴 정보를 공유
- Linux에서는 `SO_REUSEPORT`로 여러 인스턴스가 같은 포트를 공유해 커널이 트래픽을 분산 (로드밸런서 불필요)
- 기본은 순수 바이트 릴레이(SMTP 프로토콜 비파싱) — 스팸필터/그레이리스팅을 켜지 않으면 오버헤드 zero

### 2. 아웃바운드 발신 완충
- Hedwig의 `smtp_gateway` 설정으로 연동 (Hedwig 코드/설정 파일 외 변경 없음)
- 제출받은 메일은 즉시 로컬 파일 스풀에 저장 후 빠르게 응답 — Hedwig(IMAP도 처리하는) 프로세스가 느린 발신 상대 때문에 블로킹되지 않음
- 별도 스레드풀에서 실제 DNS MX 조회(명시적 타임아웃 적용) + JavaMail 발송, 재시도/백오프, 느린 도메인은 별도 delay 큐로 격리
- 재시도 소진/영구 실패 메일은 dead-letter 디렉터리에 보관 (자동 삭제 없음)

### 3. LLM 기반 인바운드 콘텐츠 스팸 필터
- 헤더/제목/본문/수신자를 LLM에 보내 스팸 여부 판정 (첨부파일은 범위 밖)
- 판정 결과는 하드 거절이 아니라 `X-Spam-Flag`/`X-Spam-Score`/`X-Spam-Provider` 헤더 태그만 주입 — 최종 판단은 Hedwig sieve/사용자 몫
- 분류기 교체 가능: 로컬 Ollama(Gemma 등), Gemini, Claude
- 분류기 장애/타임아웃은 fail-open(정상 메일로 간주)

### 4. RBL(DNSBL) 조회
- 발신 IP를 `zen.spamhaus.org`, `bl.spamcop.net` 등 공개 블랙리스트에 조회해 커넥션 단계에서 차단
- DNS 조회 실패/타임아웃은 fail-open

### 5. 그레이리스팅
- (발신 IP, MAIL FROM, RCPT TO) 삼중항을 처음 보면 `450`으로 일시 거부, 표준 재시도 창 안에서 다시 오면 통과
- 스팸봇 대부분은 재시도하지 않는다는 점을 이용한 저비용 필터
- 여러 인스턴스가 SO_REUSEPORT로 트래픽을 나눠 받으므로 상태는 공유 DB(`hw_greylist`)에 저장

### 6. 규칙기반(SpamAssassin류) 스팸 점수 필터
- 영문/국문 스팸·피싱·BEC 상투어 키워드(고전 스팸부터 대출/도박/성인/택배-정부기관 사칭 스미싱까지) + 제목 전체대문자·느낌표 과다·빈 본문 같은 휴리스틱을 점수로 합산
- **언어무관 구조/URL 체크**(SpamAssassin의 비-콘텐츠 룰에 대응, 네트워크 조회 없이 텍스트만으로 판정):
  - URL 호스트가 raw IP(`http://1.2.3.4/...`)이거나 퓨니코드(`xn--...`) 도메인이면 가중치
  - `bit.ly`류 URL 단축 서비스 사용 시 가중치
  - From 표시명에 유명 브랜드/은행/기관명이 있는데 실제 발신 도메인이 무관하면 "브랜드 사칭"으로 가중치(예: `"국민은행 고객센터" <no-reply@totally-different.example>`)
  - `Date`/`Message-ID` 헤더 누락, RCPT TO가 To/Cc 헤더 어디에도 없는 봉투-헤더 불일치(대량 BCC 스팸의 전형적 패턴)
  - 본문이 링크 위주 HTML(텍스트 비중 극히 낮음)이면 가중치
- 발신 도메인과 다른 무료메일 주소가 본문에 노출되면(발신자 사칭 피싱의 강한 신호, 실측 사례 기반) 단독으로 임계치 도달
- 관리자가 `extra-keywords`로 사내 전용 금칙어 추가 가능
- LLM보다 먼저 실행되어, 이미 확신하는 스팸이면 느리고 비용이 드는 LLM 호출 자체를 건너뜀
- **범위 밖(후속 과제)**: SPF/DKIM/DMARC 검증은 DNS 조회+암호 검증이 필요해 `RblChecker`급 별도 컴포넌트가 필요하므로 미포함

**모든 룰(키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치)은 DB(`hw_spam_rule`)로
완전 외부화**돼 있어 재빌드·재배포 없이 `/admin/spam-rules` REST API로 추가/수정/비활성화할 수 있다
(`/admin/mail-list`와 동일하게 `X-Admin-Key` 헤더로 접근 제어). H2(개발용)는 재시작 시마다
`schema.sql`의 기본 룰셋으로 재시딩되고, 실제 운영 DB(오라클/마리아DB/MSSQL)는
`src/main/sql/hw_spam_rule-seed-data.sql`을 테이블 생성 후 1회 적용해 같은 기본값으로 시작할 수
있다. 정규식(KEYWORD)은 등록 시 컴파일 검증을 거치고, 캐시 갱신 시점에도 실패한 행은 로그만 남기고
건너뛰어 잘못된 정규식 하나가 전체 필터를 멈추지 않는다.

```bash
# 목록 조회
curl http://localhost:8090/admin/spam-rules

# 키워드 룰 추가
curl -X POST http://localhost:8090/admin/spam-rules \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"KEYWORD","pattern":"사내\\s*금칙어","weight":3.0,"reason":"사내 전용"}'

# 가중치 수정/비활성화 (id는 목록 조회로 확인)
curl -X PUT http://localhost:8090/admin/spam-rules/42 \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"KEYWORD","pattern":"사내\\s*금칙어","weight":5.0,"enabled":false}'

# 삭제
curl -X DELETE http://localhost:8090/admin/spam-rules/42
```

### 7. 관리자/사용자 화이트-블랙리스트
- 이메일 정확 매치(`user@domain.com`) 또는 도메인 와일드카드(`@domain.com`) 지원, 전역 규칙과 특정 수신자 전용 규칙 모두 가능
- 화이트리스트는 그레이리스팅·룰기반·LLM 스팸판정을 모두 건너뜀 (명시적 허용이 최우선)
- 블랙리스트는 `blacklist-action` 설정에 따라 SMTP 단계에서 즉시 거절(`REJECT`, 550) 하거나 헤더 태그만 강제(`TAG`)
- `/admin/mail-list` REST API(GET/POST/DELETE)로 관리, `X-Admin-Key` 헤더로 접근 제어 가능

### 8. 관리자 통계 대시보드
- `GET /admin/dashboard`에서 커넥션/밴/스팸판정/RBL/그레이리스팅/아웃바운드 지표를 5초 주기로 갱신되는 카드로 표시
- 같은 화면에서 화이트/블랙리스트 추가·삭제도 가능 (별도 프론트엔드 빌드 없이 순수 HTML/JS)

### 9. 아웃바운드 DKIM 서명
- 이 게이트웨이를 거쳐 나가는 발신 메일에 `DKIM-Signature` 헤더를 추가 (RFC 6376, relaxed/relaxed 정규화, rsa-sha256)
- 외부 라이브러리 없이 표준 Java 암호화 API만으로 직접 구현
- 아웃바운드 스풀 접수 시점에 한 번 서명해 재시도 시에도 동일한 서명을 재사용
- 개인키/도메인/셀렉터가 없으면 `enabled: false`로 두면 그만이며, 나중에 키를 발급받으면 설정만 채우면 바로 동작

## 아키텍처

```
                    ┌─────────────────────────┐
외부 SMTP 클라이언트 →│  hedwig-spam-gateway     │→ Hedwig SMTP (backend)
                    │  - 레이트리밋/밴/RBL      │
                    │  - (옵션) 그레이리스팅    │
                    │  - (옵션) LLM 스팸 태깅   │
                    └─────────────────────────┘

Hedwig(smtp_gateway=) →│  hedwig-spam-gateway     │→ 실제 인터넷(DNS MX + JavaMail 발송)
                    │  아웃바운드 스풀+재시도    │
                    └─────────────────────────┘
```

스팸필터·그레이리스팅·룰기반 필터·화이트-블랙리스트가 모두 꺼져 있으면 인바운드는 SMTP를 파싱하지 않는 순수 바이트 릴레이로 동작합니다. 넷 중 하나라도 켜지면 SMTP를 종단하는 프록시로 전환됩니다 (DATA 본문/RCPT TO를 봐야 하기 때문).

## 빌드

```bash
mvn clean package
```

Java 8 호환 바이트코드로 빌드되지만, 개발 편의를 위해 JDK 17/21 등 최신 JDK로도 빌드/테스트 가능합니다.

## 실행

```bash
java -jar target/hedwig-spam-gateway.jar
```

기본 설정(`src/main/resources/application.yml`)은 인바운드 2525, 아웃바운드 2526, Actuator 8090 포트를 사용하며 스팸필터/RBL/그레이리스팅은 모두 기본 비활성입니다. 운영 배포 시에는 `src/main/bin/start.sh`/`stop.sh`(Hedwig `run.sh`와 동일한 프로세스 식별 방식을 시작/종료로 분리), `hedwig-gateway.service`(systemd), `aix-srsetup.sh`(AIX SRC) 예시를 참고하세요.

배포 레이아웃은 Hedwig(`{bin,conf,lib,logs}`)과 동일하게 `<GW_HOME>/{bin,conf,logs}` + 루트의 jar로 구성합니다.

```
<GW_HOME>/
  hedwig-spam-gateway.jar
  bin/
    start.sh
    stop.sh
  conf/
    application.yml
    log4j2.xml       # 선택 - 없으면 jar 내장 기본 로그 설정 사용
  logs/
```

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 bin/start.sh
bin/stop.sh
```

`conf/log4j2.xml`을 두면(`src/main/conf/log4j2.xml`을 복사해 시작점으로 사용) Hedwig의 `conf/log4j2.xml`과 동일한 방식으로 jar 재빌드 없이 로그 파일 위치/롤링(10MB, 10개 보관)/레벨을 바꿀 수 있습니다. 기본 제공 설정은 Hedwig처럼 기능별로 로그를 분리합니다: `logs/{general,server,spamfilter,rbl,greylist,maillist,ban,outbound,jdbc}.log`. (스팸 판정은 RCPT/DATA를 처리하는 인바운드 프록시 핸들러가 그레이리스팅·화이트/블랙리스트 판정까지 함께 수행하므로 `spamfilter.log`에 모입니다.) 파일을 두지 않으면 jar에 내장된 기본 설정(콘솔 + `logs/gateway.log` 단일 파일)이 사용됩니다.

### 주요 설정 (`application.yml`)

| 항목 | 설명 | 기본값 |
|---|---|---|
| `gateway.listen-port` | 인바운드 리슨 포트 | 2525 |
| `gateway.reuse-port` | Linux SO_REUSEPORT 사용 여부 | true |
| `gateway.backend.host/port` | Hedwig 실제 SMTP 주소 | 127.0.0.1:25 |
| `gateway.rate-limit.*` | IP별 요청 임계치 | window 10s / threshold 100 |
| `gateway.ban.*` | 밴 유지시간/폴링 주기 | 60분 / 5초 |
| `gateway.failover.policy` | fail-open / fail-closed | fail-open |
| `gateway.outbound.*` | 아웃바운드 완충(스풀/재시도/DNS) | enabled: true |
| `gateway.spam-filter.*` | LLM 스팸 판정 | enabled: false |
| `gateway.rbl.*` | DNSBL 조회 | enabled: false |
| `gateway.greylist.*` | 그레이리스팅 | enabled: false |
| `gateway.rule-filter.*` | 규칙기반 스팸 점수 필터 | enabled: false |
| `gateway.mail-list.*` | 화이트/블랙리스트 | enabled: false, blacklist-action: reject |
| `gateway.admin.api-key` | `/admin/*` API 접근 제어 | (비어있음 = 인증 없음) |
| `gateway.outbound.dkim.*` | 발신 메일 DKIM 서명 | enabled: false |

## 모니터링 / 관리

```bash
curl http://localhost:8090/actuator/gateway
```

인스턴스별 현재 커넥션 수, 밴 처리 건수, 아웃바운드 스풀 상태, 스팸 판정 건수, RBL/그레이리스팅 통계를 JSON으로 노출합니다. K8s 없이도 외부 크론/스크립트가 주기적으로 폴링해 이상을 탐지할 수 있습니다.

사람이 보기 편한 화면은 `http://localhost:8090/admin/dashboard`에서 확인할 수 있고, 같은 곳에서 화이트/블랙리스트도 관리할 수 있습니다. 화이트/블랙리스트 API만 직접 쓰려면:

```bash
# 등록
curl -X POST http://localhost:8090/admin/mail-list \
  -H 'Content-Type: application/json' \
  -d '{"listType":"BLACK","pattern":"@spam-domain.com","reason":"known spammer"}'

# 조회
curl http://localhost:8090/admin/mail-list

# 삭제
curl -X DELETE 'http://localhost:8090/admin/mail-list?listType=BLACK&pattern=%40spam-domain.com&recipient='
```

`gateway.admin.api-key`를 설정했다면 위 요청에 `-H 'X-Admin-Key: <값>'`을 추가해야 합니다. `gateway.mail-list.enabled=true`가 아니면 등록은 되지만 실제 필터링/조회 캐시에는 반영되지 않습니다.

## 테스트

```bash
mvn test
```

단위 테스트는 Mockito/H2로 외부 의존성 없이 동작합니다. 실제 로컬 Ollama(Gemma), RBL 실측(DNSBL 표준 테스트 IP `127.0.0.2`), 그레이리스팅 재시도 흐름, 규칙기반 필터의 실제 헤더 주입은 개발 서버 환경에서 수동으로 검증했습니다 (`deploy/` 디렉터리의 `FakeHedwig.java` 등이 그때 쓴 테스트용 가짜 백엔드/클라이언트입니다). DKIM 서명은 실제 개인키가 없어 RFC 구현 자체만 유닛테스트로 검증했고, 실제 메일 서비스(Gmail 등) 대상 검증 통과 여부는 확인하지 못했습니다.

## 하지 않는 것 (Out of Scope)

- Kubernetes 기반 오케스트레이션, Redis 등 별도 인프라
- 완전 무중단(zero-downtime) failover — 짧은 재기동 공백은 발신측 SMTP 재시도에 의존
- 첨부파일 스캔(바이러스/매크로) — 추후 별도 작업
