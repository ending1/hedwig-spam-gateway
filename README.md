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

스팸필터·그레이리스팅이 모두 꺼져 있으면 인바운드는 SMTP를 파싱하지 않는 순수 바이트 릴레이로 동작합니다. 둘 중 하나라도 켜지면 SMTP를 종단하는 프록시로 전환됩니다 (DATA 본문/RCPT TO를 봐야 하기 때문).

## 빌드

```bash
mvn clean package
```

Java 8 호환 바이트코드로 빌드되지만, 개발 편의를 위해 JDK 17/21 등 최신 JDK로도 빌드/테스트 가능합니다.

## 실행

```bash
java -jar target/hedwig-spam-gateway.jar
```

기본 설정(`src/main/resources/application.yml`)은 인바운드 2525, 아웃바운드 2526, Actuator 8090 포트를 사용하며 스팸필터/RBL/그레이리스팅은 모두 기본 비활성입니다. 운영 배포 시에는 `src/main/bin/run.sh`(Hedwig `run.sh`와 동일한 인터페이스), `hedwig-gateway.service`(systemd), `aix-srsetup.sh`(AIX SRC) 예시를 참고하세요.

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

## 모니터링

```bash
curl http://localhost:8090/actuator/gateway
```

인스턴스별 현재 커넥션 수, 밴 처리 건수, 아웃바운드 스풀 상태, 스팸 판정 건수, RBL/그레이리스팅 통계를 JSON으로 노출합니다. K8s 없이도 외부 크론/스크립트가 주기적으로 폴링해 이상을 탐지할 수 있습니다.

## 테스트

```bash
mvn test
```

단위 테스트는 Mockito/H2로 외부 의존성 없이 동작합니다. 실제 로컬 Ollama(Gemma), RBL 실측(DNSBL 표준 테스트 IP `127.0.0.2`), 그레이리스팅 재시도 흐름은 개발 서버 환경에서 수동으로 검증했습니다.

## 하지 않는 것 (Out of Scope)

- Kubernetes 기반 오케스트레이션, Redis 등 별도 인프라
- 완전 무중단(zero-downtime) failover — 짧은 재기동 공백은 발신측 SMTP 재시도에 의존
- 첨부파일 스캔(바이러스/매크로) — 추후 별도 작업
