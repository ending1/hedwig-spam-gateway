# Hedwig 스팸 게이트웨이 — 개발서버(egov) 실증 데모 시나리오

이 문서는 egov 개발서버(`10.30.9.146`, SSH alias `egov`)에 배포한 `hedwig-spam-gateway`를 대상으로,
RBL, 그레이리스팅, 룰기반 콘텐츠 필터(피싱 탐지), 화이트/블랙리스트, 관리자 대시보드 기능을
실제로 재현 가능한 순서로 시연하는 스크립트다.

## 0. 배포 구성

- 게이트웨이 리슨(SMTP): `127.0.0.1:12535`
- 관리자/Actuator HTTP: `http://127.0.0.1:18091`
- backend: **실 Hedwig node1** `127.0.0.1:2560`
- 설정 파일: `deploy/egov-demo-application.yml` → 서버의 `/home/egov/hedwig_spam/config/application.yml`
- 데모 도구: `deploy/PhishingDemoClient.java`, `deploy/RblTestClient.java`, `deploy/BlacklistDemoClient.java`
  (모두 서버에 `javac`로 컴파일해서 사용)
- H2 인메모리 DB를 사용하므로 **게이트웨이를 재시작하면 그레이리스팅/화이트-블랙리스트 데이터가 초기화**된다.

### node1 DATA 크래시 버그 수정 (2026-09-15)

최초 시연 시 실 node1으로 DATA까지 진행하면 `354` 응답 직후 커넥션이 즉시 끊기는 문제를 발견했다
(게이트웨이 우회 직접 접속으로도 재현되어 게이트웨이 버그가 아님을 확인). `/home/egov/hedwig-node1/bin/app.console`의
스택트레이스로 근본 원인을 특정:

```
Exception in thread "smtp.taskExecutor-51" java.lang.NoClassDefFoundError:
    com/maxmind/geoip2/exception/AddressNotFoundException
    at com.hs.mail.smtp.processor.DataProcessor.doProcess(DataProcessor.java:74)
Caused by: java.lang.ClassNotFoundException: com.maxmind.geoip2.exception.AddressNotFoundException
```

`DataProcessor.doStartData()`가 `354` 응답 직후 `CountryResolverUtil.resolveByIp()`(발신 국가코드 판별,
`X-Sender-Country` 헤더용)를 호출하는데, `hedwig-server`의 `pom.xml`에는 `com.maxmind.geoip2:geoip2:2.16.1`
의존성이 선언되어 있지만 실제 node1/node2 배포판의 `lib/`에는 이 jar와 전이 의존성이 빠져 있었다.
`NoClassDefFoundError`는 `Error`라서 코드의 `catch (Exception e)`에 잡히지 않고 그대로 전파되어
DATA 처리 스레드가 죽으면서 클라이언트에 응답 없이 커넥션만 끊긴 것.

**조치**: 임시 pom으로 `mvn dependency:copy-dependencies`를 이용해 `geoip2` 런타임 의존성 전체
(geoip2-2.16.1, maxmind-db-2.0.0, httpclient-4.5.13, httpcore-4.4.13,
jackson-{annotations,core,databind}-2.13.0, 총 7개 jar)를 해석한 뒤 `/home/egov/hedwig-node1/lib/`와
`/home/egov/hedwig-node2/lib/`에 배포(두 노드의 `run.sh`가 `../lib/*.jar`를 자동으로 classpath에 포함하므로
스크립트 수정 불필요), `bin/run.sh restart`로 재기동. 재기동 후 두 노드 모두 직접 DATA 트랜잭션으로
`250 2.6.0 OK` 정상 응답 확인, `app.console`에 더 이상 관련 예외가 발생하지 않음을 확인했다.

이 수정 덕분에 이 데모의 backend를 임시로 썼던 FakeHedwig(`deploy/FakeHedwig.java`, 여전히 별도
격리 테스트용으로 유용해 저장소에는 남겨둠)에서 다시 **실 node1**으로 되돌렸다.

### GeoLite2-Country.mmdb 데이터 파일 배포 (2026-09-15)

위 jar 수정만으로는 크래시는 사라지지만, mmdb 데이터 파일 자체가 없어 `X-Sender-Country` 헤더는
TLD 기반 fallback으로만 채워지는 상태였다. MaxMind 라이선스 키를 발급받아
`https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-Country&license_key=...&suffix=tar.gz`
에서 최신 GeoLite2-Country DB(`GeoLite2-Country_20260911.tar.gz`)를 내려받아 압축 해제 후
`/opt/hedwig/geoip/GeoLite2-Country.mmdb`(코드 기본 경로, `sudo mkdir -p` + `chown egov:egov`)에 배포하고
두 노드를 재기동했다(`geoIpLoadAttempted` 플래그가 JVM 생명주기 동안 캐시되므로 파일을 나중에 놓으면
재기동해야 반영됨). MaxMind `DatabaseReader`를 직접 호출하는 별도 테스트로 `8.8.8.8 -> US` 정상 판별을
확인했다. 라이선스 키는 저장소/설정 파일에 남기지 않았으므로, DB 갱신이 필요하면 같은 방식으로
재다운로드해서 교체하면 된다(MaxMind는 주기적 갱신을 권장).

## 1. 서버 기동

```bash
ssh egov
cd /home/egov/hedwig_spam
nohup java -Xms256m -Xmx512m -jar hedwig-spam-gateway.jar \
  --spring.config.location=config/application.yml > logs/gateway.log 2>&1 < /dev/null &
disown
```

`logs/gateway.log`에서 다음 라인으로 정상 기동을 확인한다.

```
SpamGatewayServer - 스팸 게이트웨이(gateway-demo) 리슨 시작: port=12535, reusePort=false, backend=127.0.0.1:2560
```

## 2. RBL(DNSBL) 차단/통과

RFC 5782 표준 테스트 IP `127.0.0.2`는 `zen.spamhaus.org` 등 대부분의 DNSBL 테스트존에 항상 등재되어 있다.

```bash
java RblTestClient 12535 127.0.0.2   # 차단 확인용
java RblTestClient 12535 127.0.0.1   # 정상 통과 확인용
```

**결과**
- `127.0.0.2` → `connection closed immediately (no banner)` — RBL 등재 IP, 배너 전송 전 즉시 연결 종료
- `127.0.0.1` → `220 egov.handysoft.co.kr Service ready` — 정상 통과, backend 배너까지 중계됨

`logs/gateway.log`:
```
RblChecker - RBL 차단: ip=127.0.0.2, zone=zen.spamhaus.org
RblCheckHandler - RBL 등재 IP 연결 즉시 종료: ip=127.0.0.2
```

## 3. 그레이리스팅 DEFER → 재시도 통과

`(발신IP, MAIL FROM, RCPT TO)` 삼중항 기준으로 최초 시도는 항상 `450 4.2.1`로 지연시키고,
`min-retry-delay-minutes`(데모 설정: 1분) 경과 후 재시도하면 통과시킨다.

```bash
java PhishingDemoClient 12535   # 1차: RCPT TO에서 450 DEFER, DATA 진행 안 함
sleep 65
java PhishingDemoClient 12535   # 2차: RCPT TO 250, DATA까지 정상 진행
```

**1차 결과**
```
RCPT TO << 450 4.2.1 Please try again later
```
`logs/gateway.log`: `그레이리스팅 DEFER: ip=127.0.0.1, from=no-reply@slack.com, to=demo-target@handysoft.co.kr`

**2차 결과(65초 후)**
```
RCPT TO << 250 2.1.5 Recipient <demo-target@handysoft.co.kr> OK
DATA << 354 Start mail input; end with <CRLF>.<CRLF>
FINAL << 250 2.6.0 OK
```

## 4. 룰기반 피싱 탐지 + 헤더 태깅 (실측 사례 재현)

`PhishingDemoClient`가 보내는 본문은 실제 캡처된 피싱 메일(가짜 Slack 알림, `D:/slack.eml`) 패턴을
재현한 것이다 — 발신자는 `slack.com`인데 본문에 `outlook.com`(무료 메일) 주소가 노출되어 있다.
이 신호(`embedded-email-domain-mismatch`)는 실측 비교에서 Gemini만 잡아냈던 패턴을 규칙으로
재구현한 것으로, 단독으로 스팸 임계치를 넘도록 가중치가 설정되어 있다(`RuleBasedSpamChecker`).

2차 시도(그레이리스팅 통과 후) `logs/gateway.log`:
```
InboundFilterFrontHandler - 스팸 판정: score=0.5, reason=embedded-email-domain-mismatch:outlook.com
```

실 node1이 `250 2.6.0 OK`로 정상 수신 처리했으므로, `X-Spam-Flag`/`X-Spam-Score`/`X-Spam-Provider`
헤더가 주입된 메일이 실제로 backend에 전달·저장된 것이다. 룰기반 필터가 이미 확신하는 스팸이므로
LLM 호출 없이(비용 절감) 즉시 태깅해 전달했다.

주입된 헤더가 DATA 본문에 정확히 어떤 순서/형태로 붙는지 콘솔에서 직접 눈으로 확인하고 싶다면,
`deploy/FakeHedwig.java`(DATA 본문을 그대로 표준출력에 에코)를 backend로 임시 사용해도 된다
(`gateway.backend.port`를 FakeHedwig 포트로 바꾸고 재시작). 실측 결과는 다음과 같았다:

```
---- DATA CONTENT ----
DATA| X-Spam-Flag: YES
DATA| X-Spam-Score: 0.5
DATA| X-Spam-Provider: rule-based
DATA| Subject: OO님이 언급했습니다
DATA| From: Slack <no-reply@slack.com>
DATA| To: demo-target@handysoft.co.kr
DATA| 
DATA| 문의사항은 NadineEmerie6061@outlook.com 으로 연락 주세요.
---- END DATA ----
```

## 5. 화이트/블랙리스트 REST API + 블랙리스트 REJECT

```bash
curl -X POST http://127.0.0.1:18091/admin/mail-list \
  -H 'Content-Type: application/json' \
  -d '{"listType":"BLACK","pattern":"@known-spammer.example","reason":"demo blacklist"}'

curl -X POST http://127.0.0.1:18091/admin/mail-list \
  -H 'Content-Type: application/json' \
  -d '{"listType":"WHITE","pattern":"@trusted-partner.example","reason":"demo whitelist"}'

curl http://127.0.0.1:18091/admin/mail-list
```

등록 후 `@known-spammer.example` 발신자로 접속하면(`deploy/BlacklistDemoClient.java`):

```bash
java BlacklistDemoClient 12535
```

**결과** (`gateway.mail-list.blacklist-action: reject` 설정 시):
```
RCPT TO << 550 5.7.1 Blocked by sender blacklist
```

`blacklist-action: tag`로 바꾸면 거절 대신 `X-Spam-Flag: YES`만 태깅하고 backend로 전달한다.

## 6. 관리자 대시보드

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18091/admin/dashboard
```

`200` 응답 확인. 브라우저에서 `http://<egov-host>:18091/admin/dashboard`로 접속하면
RBL 차단 수, 그레이리스팅 DEFER/ALLOW 수, 스팸/정상 판정 수 등 현재 상태를 HTML로 볼 수 있다.

## 요약

| 기능 | 검증 결과 |
|---|---|
| RBL(DNSBL) 차단 | `127.0.0.2` 즉시 연결 종료, `127.0.0.1` 정상 통과 |
| 그레이리스팅 | 1차 DEFER(450) → 1분 후 재시도 ALLOW(250) |
| 룰기반 피싱 탐지 | `embedded-email-domain-mismatch` 단독으로 스팸 확정, `X-Spam-*` 헤더 주입 확인 |
| 블랙리스트 REJECT | 등록한 발신자 즉시 `550` 거절 |
| 화이트/블랙리스트 REST API | 등록/조회 정상 |
| 관리자 대시보드 | HTTP 200, HTML 정상 렌더링 |

시연 과정에서 발견해 수정한 node1/node2 배포 결함은 "0. 배포 구성 > node1 DATA 크래시 버그 수정" 절 참고.
