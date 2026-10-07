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
- RAG(문자 3-gram TF-IDF): 사내 코퍼스 평가에서 이득 없음 → 기본 비활성. 단, 사용자 신고 사례 편입에는 사용(현재 egov에서 켬).

## 3. 주요 기능과 API
| 기능 | 위치 |
|---|---|
| 대시보드(탭: 룰 관리/스팸 신고/화이트·블랙리스트) | `/admin/dashboard`, 포트 18093 |
| 룰 CRUD | `/admin/spam-rules` (+ `/stats`, `/{id}/samples`, `/{id}/advice`) |
| 룰별 적중 통계·마스킹 샘플 + LLM 가중치 조언 | `RuleStatService`, `RuleAdvisorService` (조언만, 적용은 사람이) |
| 사용자 스팸 신고 | `POST /report`(`X-Report-Key`), 관리 `/admin/spam-reports/**` |
| 메일 리스트 | `/admin/mail-list` |
- 관리자 API는 `X-Admin-Key`, 신고 접수는 별도 `X-Report-Key`. 신고 API 명세는 `docs/spam-report-api.md`.
- 신고 흐름: 접수(마스킹 저장) → LLM 판정 → 스팸·확신도 ≥ 0.8이면 RAG 자동 편입 → **룰 추가는 관리자 승인 필수**.
- LLM 재사용: 분류기(Gemini/Claude/Gemma)가 `TextCompleter`를 구현해 조언·신고 판정이 같은 설정을 쓴다.

## 4. 운영 환경 (egov 개발서버)
- ssh 별칭 `egov` (10.30.9.146). 게이트웨이 디렉터리 `/home/egov/hedwig_spam_prod`, 설정 `conf/application.yml`(root 600; **Gemini 키·관리자 키·신고 키·DB 비밀번호 포함, 출력·커밋 금지**).
- 기동/중지(root 필요):
  ```
  cd /home/egov/hedwig_spam_prod
  sudo GATEWAY_PNAME=HEDWIG_SPAM_GATEWAY_PROD JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./bin/stop.sh   # 또는 start.sh
  ```
- 배포: `mvn -q package` → jar를 scp → stop → `hedwig-spam-gateway.jar` 교체 → start. 포트: 25(게이트웨이), 2599/143(Hedwig), 18093(관리).
- 운영 Hedwig는 `sudo /home/egov/hedwig/bin/run.sh start`. Hedwig 뒤쪽 포트는 2599.
- **DB는 Oracle 19c로 전환 완료**(`10.30.9.143:1521:ora19c`, 계정 `egov`, Hedwig와 같은 계정). 게이트웨이 테이블 7개(`hw_ban_list, hw_greylist, hw_mail_list, hw_spam_rule, hw_spam_rule_stat, hw_spam_rule_sample, hw_spam_report`)+시퀀스·트리거 3쌍. DDL은 `src/main/sql/oracle/`.
- 롤백용 설정: `conf/application.yml.bak-h2`(이전 H2 파일 DB). H2 데이터 파일은 남아 있음.
- Oracle 설정 포인트: `spring.sql.init.mode: never`(내장 schema.sql은 H2용), `gateway.db-dialect: oracle`. 예시는 `deploy/conf/application-prod-oracle.example.yml`.

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
