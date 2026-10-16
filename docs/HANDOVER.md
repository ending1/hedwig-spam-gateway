# Hedwig 스팸 게이트웨이 인수인계

Claude Code로 이 프로젝트를 이어받는 사람을 위한 문서. 코드 구조는 저장소를 보면 알 수 있으니, 코드에 드러나지 않는 **운영 현황·결정 배경·주의점** 위주로 적었다.

## 1. 프로젝트 개요
- Hedwig(그룹웨어 메일서버) 앞단 스팸 게이트웨이. Maven / Spring Boot 2.7.18 / Netty / Spring JDBC. **Java 8 바이트코드**(List.of 등 9+ API 금지).
- 저장소: `ending1/hedwig-spam-gateway` (로컬 `D:\works\groupware\hedwig\hedwig-spam-gateway`). 커밋 말미에 `Co-Authored-By` 줄 필요.
- 설계 원칙: **fail-open**(검사 오류 시 정상 메일로 통과), 스팸 판정은 **차단하지 않고 헤더만 부착**(`X-Spam-Flag/Score/Provider`).
- SQL은 코드에 쓰지 않고 `gateway-sql.properties`의 `<dialect>.<domain>.<method>` 키로 외부화(없으면 `ansi.*`로 대체).

## 2. 메일 처리 파이프라인
RBL → 그레이리스팅(운영 5분) → 화이트/블랙리스트 → 룰기반(`hw_spam_rule`, 합계 ≥ 5.0이면 스팸) → (룰로 스팸 확정이 아니면) Gemini 분류.
- 사내 도메인 사칭 룰(`internal-domain-spoof`): `internal-domains`와 `trusted-networks` 설정 기반. 사내 스팸 2개월치(32,563건)의 64%가 이 유형이었음.
- **위조 방지**: 외부에서 들어온 메일에 이미 있는 `X-Spam-Flag/Score/Provider` 헤더는 **판정 전에 제거**한다(`InboundFilterFrontHandler.stripSpamHeaders`). Hedwig가 `X-Spam-Flag`로 스팸 편지함 이동을 결정하고, LLM이 위조 헤더를 보고 오판한 사례가 있었기 때문이다. 게이트웨이가 스팸으로 판정한 메일에만 자기 헤더를 붙인다.
- 처리 순서 참고: RCPT 단계에서 **화이트/블랙리스트를 그레이리스팅보다 먼저** 확인한다. 화이트리스트 매치는 그레이리스팅과 이후 스팸 판정(룰/LLM)을 모두 건너뛴다.
- RAG(문자 3-gram TF-IDF): 사내 코퍼스 평가에서 이득 없음 → 기본 비활성. 단, 사용자 신고 사례 편입에는 사용(현재 egov에서 켬).

## 3. 주요 기능과 API
| 기능 | 위치 |
|---|---|
| 대시보드(탭: 룰 관리/스팸 신고/화이트·블랙리스트) | `/admin/dashboard`, 포트 18093 |
| 룰 CRUD | `/admin/spam-rules` (+ `/stats`, `/{id}/samples`, `/{id}/advice`) |
| 룰별 적중 통계·마스킹 샘플 + LLM 가중치 조언 | `RuleStatService`, `RuleAdvisorService` (조언만, 적용은 사람이) |
| 사용자 스팸 신고 | `POST /report`(`X-Report-Key`, 선택 필드 `reason`=신고 사유) + `POST /report/{id}/eml`(메일 원본 업로드, 최대 10MB), 관리 `/admin/spam-reports/**`(원본은 `GET .../{id}/eml`로 다운로드만, 보관 경로는 `gateway.spam-report.eml-dir`) |
| 메일 리스트 | `/admin/mail-list` |
- **대시보드는 ID/PW 로그인**(`/admin/dashboard`). 로그인하면 HttpOnly·SameSite=Strict 세션 쿠키 + CSRF 토큰을 쓰고, 연속 5회 실패하면 해당 IP+ID를 5분 잠근다. 세션은 서버 메모리라 게이트웨이를 재시작하면 다시 로그인한다.
- 관리자 API는 로그인 세션 **또는** `X-Admin-Key`(스크립트/서버 간 호출용)로 인증, 신고 접수는 별도 `X-Report-Key`. 신고 API 명세는 `docs/spam-report-api.md`.
- 신고 흐름: 접수(마스킹 저장) → LLM 판정 → 스팸·확신도 ≥ 0.8이면 RAG 자동 편입 → 룰 추가는 **처리 방식 옵션**에 따름: 수동(기본, 관리자 승인 필수) / 자동(LLM 확신도 ≥ 0.9이면 승인 없이 룰 추가, 가중치 상한·일일 상한·"신고 메일에 매칭" 검증 등 안전장치 있음). 옵션은 대시보드 "스팸 신고" 탭 또는 `/admin/spam-reports/settings`에서 바꾸고 `spam-report-settings.json`에 저장. 자세한 내용은 `docs/spam-report-api.md`.
- LLM 재사용: 분류기(Gemini/Claude/Gemma)가 `TextCompleter`를 구현해 조언·신고 판정이 같은 설정을 쓴다.

## 4. 운영 환경 (egov 개발서버)
- ssh 별칭 `egov` (10.30.9.146). 게이트웨이 디렉터리 `/home/egov/hedwig_spam_prod`(**운영은 이 한 곳뿐**. 시험용 `hedwig_spam`, `hedwig_spam_node2`는 2026-10-11에 삭제함, 설정 아카이브만 `/home/egov/hedwig_spam*_removed_20261011.tar.gz`), 설정 `conf/application.yml`(root 600; **Gemini 키·관리자 키·신고 키·DB 비밀번호 포함, 출력·커밋 금지**).
- 기동/중지(root 필요):
  ```
  cd /home/egov/hedwig_spam_prod
  sudo GATEWAY_PNAME=HEDWIG_SPAM_GATEWAY_PROD JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./bin/stop.sh   # 또는 start.sh
  ```
- 배포: `mvn -q package` → jar를 scp → stop → `hedwig-spam-gateway.jar` 교체 → start. 포트: 25(게이트웨이), 2599/143(Hedwig), 18093(관리).
- 운영 Hedwig는 `sudo /home/egov/hedwig/bin/run.sh start`. Hedwig 뒤쪽 포트는 2599.
- **DB는 Oracle 19c로 전환 완료**(`10.30.9.143:1521:ora19c`, 계정 `egov`, Hedwig와 같은 계정). 게이트웨이 테이블 7개(`hw_ban_list, hw_greylist, hw_mail_list, hw_spam_rule, hw_spam_rule_stat, hw_spam_rule_sample, hw_spam_report`)+시퀀스·트리거 3쌍. DDL은 `src/main/sql/oracle/`.
- **운영은 `hedwig_spam_prod` 단일 인스턴스**다(egov 한 대, 계정 `egov`). 메일 경로는 `외부 → 25(게이트웨이) → 2599(Hedwig SMTP)`이고 IMAP은 Hedwig가 143에서 직접 서비스한다. 메일 경로에 로드밸런서는 없다.
- 같은 서버의 `hedwig_spam`(node1용, 수신 12535/관리 18091, 백엔드 2560)과 `hedwig_spam_node2`(수신 12545/관리 18092, 백엔드 2561)는 초기 **시험용 데모**(H2 메모리 DB, 그레이리스트 1분)이며 **중지 상태**, 운영과 무관하다. `hedwig_spam`에는 시험용 `FakeHedwig`(12570)가 계속 떠 있을 수 있다.
- `hedwig-smtp-frontend`/`hedwig-imap-frontend`(Java LB, 백엔드 127.0.0.1:2560·2561)는 failover 시험용으로 **구동하지 않으며 운영 경로에 없다**. egov의 nginx(`/home/egov/nginx`, SSL 16443)는 그룹웨어 웹 접속용이라 메일과 무관하다.
- 그룹웨어 연동은 게이트웨이 관리 포트로 직접 호출한다: `POST http://10.30.9.146:18093/report` (`X-Report-Key`). 노드·LB 구성과 무관.
- 롤백용 설정: `conf/application.yml.bak-h2`(이전 H2 파일 DB). H2 데이터 파일은 남아 있음.
- Oracle 설정 포인트: `spring.sql.init.mode: never`(내장 schema.sql은 H2용), `gateway.db-dialect: oracle`. 예시는 `deploy/conf/application-prod-oracle.example.yml`.

### 관리자 로그인 계정 관리
계정은 `conf/application.yml`의 `gateway.admin.users`(ID + BCrypt 해시)이며, **추가·변경 후 게이트웨이 재시작**이 필요하다. 비밀번호는 8자 이상.
```
# 해시 만들기 (비밀번호는 입력받아 셸 기록에 남지 않음). 출력된 $2a$... 값을 password-hash에 넣는다.
cd /home/egov/hedwig_spam_prod
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -Dloader.main=com.hs.mail.gateway.admin.PasswordHashTool   -cp hedwig-spam-gateway.jar org.springframework.boot.loader.PropertiesLauncher
```
```yaml
gateway:
  admin:
    users:
      - username: admin
        password-hash: "$2a$10$..."   # 따옴표 필수($ 때문)
      - username: operator2
        password-hash: "$2a$10$..."
```
- 초기 계정 `admin`의 임시 비밀번호는 `/home/egov/hedwig_spam_admin_login.txt`(egov 전용 600)에 있다. **확인 후 파일을 지우고 비밀번호를 바꿀 것.**
- `api-key`(X-Admin-Key)는 스크립트·그룹웨어 서버 호출용으로 남겨 두었다. 웹 화면에서는 입력하지 않는다. 노출됐다고 의심되면 교체 후 재시작(2026-10-11에 한 번 교체함).

## 5. DB 호환 메모 (Oracle/MariaDB)
- Oracle은 `''`가 NULL → `hw_mail_list.recipient`의 "전체 수신자"를 `*` 센티널로 저장(`MailListDao`).
- 생성 키는 `prepareStatement(sql, new String[]{"ID"})`로 받는다(ROWID 방지). LIMIT 대신 앞쪽 N건만 읽는 방식 사용.
- Oracle 스키마 `varchar2(n CHAR)`(한글). MariaDB 스키마는 소문자 `hw_*` + utf8mb4. **MariaDB는 실제 DB에서 검증하지 않음**(그룹웨어는 Oracle 위주라 생략하기로 함).
- H2 파일 DB로 쓸 땐 `spring.sql.init.mode: always` 필수, 단일 프로세스만 가능.

## 6. 개발 시 주의(반복된 함정)
- 생성자가 2개인 Spring 빈은 운영용 생성자에 `@Autowired` 필수.
- 대시보드는 `AdminDashboardController`의 거대한 Java 문자열 하나. 문자열 안에서 `\n` 이스케이프가 깨지기 쉬워 **스크립트 패치 후 반드시 `mvn compile`** 확인. 배포 후 서빙된 HTML에 새 코드가 들어갔는지 grep으로 검증.
- 서버 설정 파일의 키를 다룰 때 값 출력 금지(마스킹해서 확인).
- `D:/Spam`(사내 스팸 EML)과 `D:/Spam_analysis` 산출물은 **절대 커밋 금지**. 도구(`tools/`)만 커밋됨.
- 테스트: `mvn test` 현재 약 120개 통과(일부 skip).

## 7. 미결 사항 / 권장 후속
1. **키 교체 필요**: Gemini 키와 관리자 키가 과거 채팅에 노출됨. 교체 후 설정 반영.
2. Hedwig가 `X-Spam-Flag` 헤더를 보고 스팸함으로 옮기는지(sieve) 미확인.
3. hso10 Hedwig가 25번 포트를 다시 가져갈 가능성 확인 필요.
4. 룰 적중 통계는 실제 메일이 쌓여야 의미가 생김 → 며칠 뒤 대시보드 "적중" 열로 확인, LLM 조언은 적중이 쌓인 뒤 사용.
5. 신고 API 키(`gateway.admin.report-api-key`)를 그룹웨어 담당자에게 안전하게 전달(채팅에 출력한 적 없음). 현재 HTTP 평문이라 사내망 한정.
6. 다중 인스턴스 운영 시 Oracle 공유는 가능하나 실제 다중 구동은 미검증.
7. Oracle 시험 중 남은 데이터: 기각된 테스트 신고 1건, 그레이리스트 시험 행 1건, 서버 `/tmp`의 시험 스크립트·`h2-rules.json`(비밀 없음).
8. 언어 포팅(Python 등)은 하지 않기로 함: 병목이 외부 호출(Gemini/DNS/DB)이라 속도 이득이 없고, 현장 엔지니어가 Java에 익숙함.

## 8. 사용자와의 작업 방식
- 사용자는 한국어로 소통. 외부 서비스에 키/데이터를 보내거나 운영 설정을 바꾸는 일은 사전 확인을 받는 편이 안전(과거 Gemini 실서버 활성화 때 확인을 거침).
- 결과 보고는 간결하게, 확인한 것과 못 한 것을 구분해서 보고.

## 이벤트 로그 / AI 챗 (2026-10-16 추가)
- `hw_mail_event`(Oracle 생성 완료) + `MailEventService`가 메일 처리 결과를 기록. 보관 기간 `GATEWAY_EVENTLOG_RETENTIONDAYS`. 훅 위치: `InboundFilterFrontHandler`(블랙리스트 거부, 그레이리스팅 지연, 최종 판정), `RblCheckHandler`.
- AI 챗은 `ChatService`(의도 분류만 LLM, 조회·답변은 고정 코드) + `OllamaIntentModel`. egov 설정 `gateway.chat.base-url: http://10.30.7.68:11434`(kube Ollama, 모델 `qwen3-coder:30b`). kube Ollama는 인증 없이 열려 있으므로 운영에서는 접속 주소를 제한할 것.
- 챗 룰 변경은 `ChangeService`(제안 10분 유효·1회용·본인만, 적용 시 상태 재확인) + `AuditService`(hw_admin_audit, Oracle 생성 완료)로 구현됨.
- 다음 단계(미구현): SPF/DKIM/DMARC 수신 검증, 메일 격리/원문 열람, kube에 `qwen3:30b` 등 추가 모델 설치 후 비교(다운로드는 사용자 승인 필요).
- 알려진 한계: 룰 조회 키워드는 패턴·사유에 대한 단순 문자열 매칭이라 의미 검색이 아니다(예: "협박"으로 영문 패턴 룰은 못 찾음).

## 발신 인증 SPF/DKIM/DMARC (2026-10-16 추가)
- 패키지 `com.hs.mail.gateway.auth`: `SpfChecker`, `DkimVerifier`, `DmarcChecker`, `AuthChecker`(조합, 메일당 시간 상한), `DnsJavaLookup`(dnsjava). 연결 지점은 `InboundFilterFrontHandler.classifyAndForward`(화이트/블랙리스트 처리 뒤, 룰 평가 전, DNS가 블로킹이라 classifierExecutor에서 수행) → `AuthResult`를 `SpamCheckRequest.withAuth`로 룰기반 필터에 전달 → `Authentication-Results` 헤더 부착.
- 점수는 `hw_spam_rule` STRUCTURAL `auth-*` 6개 행(기동 시 없으면 자동 추가). 이벤트 로그 `hw_mail_event`에 `spf_result/dkim_result/dmarc_result` 컬럼(기존 테이블은 기동 시 ALTER로 추가, egov Oracle 적용 완료).
- `tools/AuthCorpusCheck.java`: EML 폴더로 검증기를 점검하는 개발용 도구. 2개월 된 사내 스팸 표본 120건에서 DKIM 서명 19건 중 12건이 pass였고, 나머지는 서명 만료(보관 메일이라 정상), 공개키 없음, 본문 변형(보관 과정에서 끝에 제어문자가 붙은 경우는 직접 확인) 등이었다. 이 표본의 SPF/DMARC 수치는 접속 IP를 Received 헤더로 근사해 신뢰할 수 없다.
- 주의(시험 사고 기록): 인증 시험에서 실제 브랜드 도메인(paypal.com)을 발신자로 쓰고 수신자를 우리 도메인이 아닌 곳(nobody@handysoft.co.kr)으로 했더니, Hedwig가 그 수신자를 **릴레이**하려다 상대 서버가 거부해 반송 메일을 만들었고 그 반송이 위조 발신자(paypal.com)에게 실제로 나갔다. 시험에는 예약 도메인(`*.example`, `*.test`)과 우리 도메인 수신자를 쓸 것. (Hedwig는 우리 도메인의 없는 사용자는 RCPT에서 550으로 거절한다 - 반송의 원인은 없는 사용자가 아니라 외부 도메인 릴레이였다.)
- 알려진 위험: 메일링 리스트/전달 메일은 정당해도 DMARC가 실패해 스팸 태그가 붙을 수 있다. 통계로 확인하며 `auth-dmarc-fail-reject` 등의 가중치를 조정한다.

## RCPT 단계 릴레이 차단 (2026-10-16 추가)
- **발견한 문제**: Hedwig의 `mynetworks=127.0.0.1/8`(릴레이 허용)은 게이트웨이가 앞에 서면서 모든 외부 접속을 127.0.0.1로 보게 되어 외부 발신자에게 무력화돼 있었다. 실제로 외부 취급 IP(kube)에서 `RCPT TO:<x@example.org>`가 `250`으로 수락됐다(DATA는 보내지 않아 메일은 나가지 않음). 즉 게이트웨이를 거치면 오픈 릴레이였다.
- **조치**: `RecipientPolicy` + `InboundFilterFrontHandler.handleRcptTo` 맨 앞에서 검사. `gateway.recipient.local-domains`에 없는 도메인은 `gateway.recipient.relay-networks` 밖의 접속에 `554 5.7.1 Relay access denied`. 거부는 이벤트 로그에 `REJECTED/RELAY`로 남는다. 목록이 비면 정책이 꺼진 것과 같다(기존 동작).
- **egov 설정**: `local-domains: [.handysoft.co.kr]`(egov/mars2/lucy3 등 하위 도메인, 정확히 `handysoft.co.kr`은 제외 - 이 서버가 받는 도메인이 아니고 실제 회사 MX는 다른 서버), `relay-networks: [127.0.0.0/8]`(Hedwig mynetworks와 동일, 같은 서버의 그룹웨어 앱이 localhost:25로 외부에 보내는 흐름 유지). 운영 반영 시 실제 수신 도메인 목록과 내부 앱/장비의 발송 대역을 반드시 확인할 것(빠지면 정상 메일이 554로 거절된다).
- **한계**: 없는 사용자 검증은 Hedwig가 한다(550). 게이트웨이는 그레이리스팅을 RCPT 검증보다 먼저 하므로 없는 사용자에게도 첫 접촉은 450이 나간다.

## 격리함 (2026-10-16 추가)
- `QuarantineService`(보관·미리보기·복구·삭제·정리), `QuarantineController`(`/admin/quarantine/**`), 훅은 `InboundFilterFrontHandler.deliverBufferedData`(스팸 판정 시 사본 비동기 저장). 테이블 `hw_quarantine`(Oracle 생성 완료), 원문은 `gateway.quarantine.dir`(egov: `/home/egov/hedwig_spam_prod/data/quarantine`, 권한 700).
- **전달은 바꾸지 않는 "사본" 방식**이다. 받는 사람에게 전달하지 않고 붙잡는 보류(hold)는 구현하지 않았다. 이유: 게이트웨이가 DATA를 붙잡은 뒤 backend(Hedwig)는 이미 DATA 대기 상태라, 거절(550)하거나 폐기하려면 backend 연결을 정리하고 같은 클라이언트 연결의 이후 명령을 위해 backend를 다시 연결해 상태를 맞추는 처리가 필요하다. 오탐 시 메일 손실 위험도 있어 정책 결정(어떤 점수부터 보류할지)이 먼저다.
- 복구는 Hedwig 백엔드(`gateway.backend`)로 SMTP 직접 전달이라 평소 검사/스팸 태그를 거치지 않고, 이미 스팸함으로 간 원본과 별개로 한 통 더 도착한다. 시험 시 수신자는 실제 존재하는 계정이어야 한다(없으면 Hedwig가 거절).
- 시험으로 `t11111t@egov.handysoft.co.kr`에 `[격리함 시험]` 제목의 가상 메일 2통(스팸 태그 1, 복구 1)을 보냈다.

## 스팸 보류 hold (2026-10-16 추가)
- 위 격리함 절의 "보류는 구현하지 않았다"는 이후 구현으로 바뀌었다. `gateway.quarantine.hold-enabled: true`이면 `InboundFilterFrontHandler.deliverBufferedData`가 점수 ≥ `hold-min-score`인 스팸을 `holdOrDeliver`로 보낸다: classifierExecutor에서 `QuarantineService.storeHeld`(원문 파일까지 저장 확인) → 성공 시 `holdMessage`.
- **프로토콜 처리**: 게이트웨이가 DATA를 붙잡은 시점에 backend(Hedwig)는 DATA 대기 상태다. 보류하면 발신자에게 `250 2.0.0 Ok: queued`를 보내고 backend 연결을 닫아(Hedwig는 마침표 없는 DATA를 버린다) 새 연결로 교체한다. 같은 client 연결의 이후 명령을 위해 새 연결에 `EHLO <client 이름>`을 다시 보내며, 그 응답(배너 + EHLO 응답 2건)은 `ProxySession.suppressReplies`로 client에 전달하지 않는다. 옛 연결의 늦은 응답/종료는 `backendGeneration`으로 무시한다.
- **안전 원칙**: 저장이 확인되지 않으면 절대 보류하지 않고 평소대로 스팸 태그로 전달한다(메일 손실 방지). 보류 개수 한도(`hold-max-count`)를 넘어도 전달한다.
- 보류 메일은 `hold-retention-days`(기본 30일)가 지나면 **영구 삭제**된다. 운영에서는 관리자가 주기적으로 확인하는 절차(또는 알림)를 정해야 한다. 상태: HELD(보류 중, 미전달) / QUARANTINED(사본, 이미 전달됨) / RELEASED.
- egov 설정: `hold-enabled: true`, `hold-min-score: 0.8`. 끄려면 `hold-enabled: false` 후 재기동.
- 통합 시험은 연결을 여러 번 받는 fake Hedwig(`MultiHedwigServer`, InboundFilterFrontHandlerTest 안)로 한다.

## 보류 요약 메일 (2026-10-16 추가)
- `DigestService`(`quarantine` 패키지): `@Scheduled(cron = "${gateway.digest.cron:0 0 9 * * *}")`, 내용은 `QuarantineService`(보류 목록·개수·임박 개수)와 `MailEventService`(전날 현황)로 구성, 발송은 `QuarantineService.deliver`(Hedwig 백엔드 SMTP 직접). 수동: `/admin/quarantine/digest/{preview,send}`.
- egov: `gateway.digest.enabled: false`(수신자는 시험 계정 `t11111t@egov.handysoft.co.kr`). **실제 관리자 주소로 바꾸고 `enabled: true`로 켜야 매일 발송된다.** 수신자가 Hedwig의 우리 도메인 주소가 아니면 Hedwig가 거절/릴레이하므로 외부 주소(gmail 등)는 이 방식으로 보낼 수 없다.

## AI 챗 격리함 질의 (2026-10-16 추가)
- `ChatService`에 의도 `QUARANTINE_SUMMARY` 추가(상태별 건수 + 최근 보류 5건, 읽기 전용). 격리함 전달·삭제 같은 변경은 챗에서 하지 않고 '격리함' 탭에서 한다. egov에서 qwen3-coder:30b로 "보류된 메일 몇 건이야?", "격리함 현황 알려줘" 등 4가지 표현이 모두 올바른 의도로 분류되고 기존 질문 6종도 그대로 분류됨을 확인했다(전체 정확도 재측정은 하지 않음).

## AI 챗 정확도 재측정과 화면 확인 (2026-10-16)
- `tools/chat-bench.js`: 44문항(요약/상위/추이/인증/룰/신고/격리함/변경/무관·공격)을 서버 검증 후 값(기간·항목·개수·번호·동작)까지 채점한다. `node tools/chat-bench.js http://localhost:18099`(관리자 키가 필요하면 `ADMIN_KEY` 환경변수). 모델/프롬프트를 바꾸면 반드시 다시 돌릴 것.
- 결과(qwen3-coder:30b, kube): 처음 41문항에서 의도 40/41, 값 34/36. "가중치 N으로 **추가**해줘"가 set_weight로 오분류되는 문제를 찾아 프롬프트에 add/번호 지정 규칙과 예시를 넣은 뒤 44문항에서 **의도 43/44, 값 39/39**, 응답 중앙값 약 0.9초. 남은 1건은 "모든 룰을 삭제해줘"(제안이 만들어지지 않고 안내만 나가므로 안전). 문항은 개발자가 만든 것이라 실제 관리자 표현으로 다시 점검할 것.
- 화면은 로컬에서 같은 jar를 인증 없이 띄워(`--server.port=18099 --gateway.chat.base-url=http://10.30.7.68:11434 ...`, H2) 브라우저로 확인했다: 챗 탭(예시 버튼, 말풍선, 표), 변경 제안 카드와 [적용], 변경 이력 표, 통계·로그 탭, 격리함 탭. egov 대시보드는 ID/PW 로그인이 필요해 로그인 이후 화면은 직접 확인하지 못했다.

## AI 챗 조회 확장 (2026-10-16)
- 날짜/기간: `chat/KoreanDates`가 "10월 5일부터 7일까지", "3일 전", 지난주/지난달 등을 서버에서 해석(LLM은 날짜를 계산하지 않음). 범위 최대 366일, 미래는 오늘로 보정.
- 개별 메일 검색 `EVENT_SEARCH`: 발신자/IP/처리결과로 `hw_mail_event` 조회(최대 10건, 기본 최근 30일). 값은 서버가 정리 후 바인딩.
- 룰 의미 검색: `RuleSemanticSearch` + bge-m3 임베딩(`gateway.chat.embed-model`, `semantic-min-similarity=0.55`). 룰 변경 시 색인 재생성(첫 호출 약 6초). 임베딩 불가 시 문자열 검색으로 대체. kube의 bge-m3는 큰 배치에서 비정상 종료하므로 배치 8·200자 제한.
- `tools/chat-bench.js` 59문항: 의도 58/59, 값 64/64. 유일한 실패는 "모든 룰을 삭제해줘"(CHANGE_PROPOSAL로 분류, 무해).
- 한계: "협박" 같은 질의는 결과 없음, 낮은 유사도는 잡음. egov 룰 #203/#221은 유형+패턴이 같은 중복(점수 이중 가산 가능) → 하나 정리 권장.

## AI 챗 변경 확장 (2026-10-16)
- `ChangeService`에 동작 4가지 추가: `list_add`/`list_remove`(전역 화이트·블랙리스트), `release`/`delete_mail`(격리 메일 복구·삭제). 흐름은 룰 변경과 같다(제안 → 관리자 [적용] → 1회용·10분·본인만, 변경 이력 기록).
- 서버 검증: 주소는 `user@domain` 또는 `@domain`(도메인만 말하면 자동 변환), 중복·반대 리스트 존재 거절, 이미 복구된/없는/원문 없는 격리 메일 거절, 적용 시점에 상태 재확인. 경고: 화이트 도메인 와일드카드, 격리 삭제(복구 불가), 스팸 태그 사본 복구(중복 수신).
- 사용자별 리스트(recipient 지정)는 챗에서 다루지 않는다(전역만).
- 테스트 `ChangeServiceExtTest` 7건(전체 235건). `tools/chat-bench.js`에 11문항 추가(70문항) — **egov 배포·측정은 미완료**.
- egov 배포: 새 jar가 `/home/egov/hedwig_spam_prod/hedwig-spam-gateway.jar`에 복사됐으나 재시작은 하지 않았다(실행 중 프로세스는 이전 코드). 재시작: root로 `GATEWAY_PNAME=HEDWIG_SPAM_GATEWAY_PROD JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 bin/stop.sh && bin/start.sh`. 롤백 jar: `.bak_20261016_prelistchg`.
