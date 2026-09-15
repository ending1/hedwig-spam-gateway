# Hedwig 스팸 게이트웨이 — 개발서버(egov) 실증 데모 시나리오

이 문서는 egov 개발서버(`10.30.9.146`, SSH alias `egov`)에 배포한 `hedwig-spam-gateway`를 대상으로,
RBL, 그레이리스팅, 룰기반 콘텐츠 필터(피싱 탐지), 화이트/블랙리스트, 관리자 대시보드 기능을
실제로 재현 가능한 순서로 시연하는 스크립트다.

## 0. 배포 구성

- 게이트웨이 재시(SMTP): `127.0.0.1:12535`
- 관리자/Actuator HTTP: `http://127.0.0.1:18091`
- backend: **FakeHedwig**(`deploy/FakeHedwig.java`, DATA 본문/주입된 헤더를 그대로 콘솔에 출력) `127.0.0.1:12570`
  - 실제 Hedwig node1(`127.0.0.1:2560`)은 데모용 수신자 계정이 실존하지 않아 DATA(354) 응답 직후
    커넥션을 즉시 끊어버리는 것을 확인했다(게이트웨이를 거치지 않고 node1에 직접 접속해도 동일하게
    재현됨 — 게이트웨이 버그가 아니라 node1 자체의 동작). 헤더 태깅까지 눈으로 확인해야 하는 시연
    목적에는 부적합하므로, DATA 본문을 그대로 에코하는 FakeHedwig를 backend로 사용한다.
    RBL/그레이리스팅은 backend 연결 이전(RBL) 또는 backend 미개입(그레이리스팅 DEFER) 단계에서
    판정되므로 이 대체와 무관하게 그대로 유효하다.
- 설정 파일: `deploy/egov-demo-application.yml` → 서버의 `/home/egov/hedwig_spam/config/application.yml`
- 데모 도구: `deploy/PhishingDemoClient.java`, `deploy/RblTestClient.java`, `deploy/BlacklistDemoClient.java`
  (모두 서버에 `javac`로 컴파일해서 사용)
- H2 인메모리 DB를 사용하므로 **게이트웨이를 재시작하면 그레이리스팅/화이트-블랙리스트 데이터가 초기화**된다.

## 1. 서버 기동

```bash
ssh egov
cd /home/egov/hedwig_spam
nohup java FakeHedwig 12570 > logs/fake-hedwig.log 2>&1 < /dev/null &
disown
nohup java -Xms256m -Xmx512m -jar hedwig-spam-gateway.jar \
  --spring.config.location=config/application.yml > logs/gateway.log 2>&1 < /dev/null &
disown
```

`logs/gateway.log`에서 다음 라인으로 정상 기동을 확인한다.

```
SpamGatewayServer - 스팸 게이트웨이(gateway-demo) 리슨 시작: port=12535, reusePort=false, backend=127.0.0.1:12570
```

## 2. RBL(DNSBL) 차단/통과

RFC 5782 표준 테스트 IP `127.0.0.2`는 `zen.spamhaus.org` 등 대부분의 DNSBL 테스트존에 항상 등재되어 있다.

```bash
java RblTestClient 12535 127.0.0.2   # 차단 확인용
java RblTestClient 12535 127.0.0.1   # 정상 통과 확인용
```

**결과**
- `127.0.0.2` → `connection closed immediately (no banner)` — RBL 등재 IP, 배너 전송 전 즉시 연결 종료
- `127.0.0.1` → `220 fake-hedwig.local ESMTP ready` — 정상 통과, backend 배너까지 중계됨

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
RCPT TO << 250 2.1.5 OK
DATA << 354 Start mail input; end with <CRLF>.<CRLF>
FINAL << 250 2.6.0 Queued
```

## 4. 룰기반 피싱 탐지 + 헤더 태깅 (실측 사례 재현)

`PhishingDemoClient`가 보내는 본문은 실제 캡처된 피싱 메일(가짜 Slack 알림, `D:/slack.eml`) 패턴을
재현한 것이다 — 발신자는 `slack.com`인데 본문에 `outlook.com`(무료 메일) 주소가 노출되어 있다.
이 신호(`embedded-email-domain-mismatch`)는 실측 비교에서 Gemini만 잡아냈던 패턴을 규칙으로
재구현한 것으로, 단독으로 스팸 임계치를 넘도록 가중치가 설정되어 있다(`RuleBasedSpamChecker`).

2차 시도(그레이리스팅 통과 후) 결과, `logs/fake-hedwig.log`에 다음과 같이 게이트웨이가 주입한
헤더가 DATA 본문 맨 앞에 붙어 backend로 전달된 것을 확인:

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

`logs/gateway.log`:
```
InboundFilterFrontHandler - 스팸 판정: score=0.5, reason=embedded-email-domain-mismatch:outlook.com
```

룰기반 필터가 이미 확신하는 스팸이므로 LLM 호출 없이(비용 절감) 즉시 태깅해 전달했다.

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

## 알려진 이슈(게이트웨이 범위 밖)

실제 Hedwig node1(`127.0.0.1:2560`)에 존재하지 않는 수신자로 DATA까지 진행하면, 게이트웨이를
거치지 않고 node1에 직접 접속해도 `354` 응답 직후 커넥션이 끊긴다. 게이트웨이 버그가 아니라
node1 자체의 동작이므로, 실계정 기준으로 재현/조사가 필요하면 별도 조사 필요.
