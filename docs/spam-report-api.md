# 스팸 신고 REST API 명세

그룹웨어에서 사용자가 "스팸 신고"를 눌렀을 때 Hedwig 스팸 게이트웨이로 신고를 전달하는 API.
게이트웨이는 신고를 접수하고 LLM이 비동기로 판정한 뒤, 관리자가 대시보드에서 검토한다.

## 1. 개요

| 항목 | 값 |
|---|---|
| 엔드포인트 | `POST http://<게이트웨이 호스트>:18093/report` (egov 개발: `10.30.9.146:18093`) |
| 인증 | 요청 헤더 `X-Report-Key: <신고 키>` |
| 요청 형식 | `Content-Type: application/json; charset=UTF-8` |
| 응답 형식 | JSON |
| 처리 방식 | 접수 즉시 `202 Accepted`. LLM 판정은 서버에서 비동기로 수행(수 초) |

- 신고 키는 게이트웨이 서버 설정 `gateway.admin.report-api-key`에 있다. 관리자 키와 별개이며, 그룹웨어 서버 설정에만 보관하고 브라우저(클라이언트 JS)에 노출하지 않는다. 그룹웨어 **서버가** 호출해야 한다.
- 현재 HTTP(평문)이다. 사내망 구간에서만 사용하고, 외부 노출이 필요하면 리버스 프록시(TLS)를 앞단에 둔다.

## 2. 요청

`POST /report`

### 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `X-Report-Key` | 예 | 신고 키 |
| `Content-Type` | 예 | `application/json` |

### 본문 (JSON)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `reporter` | string | 아니오 | 신고자 식별자(메일 주소 또는 사번). 최대 100자, 초과분은 잘림 |
| `from` | string | 아니오 | 신고 대상 메일의 발신자 주소. `홍길동 <a@b.com>`처럼 보내도 되며 도메인만 추출해 저장 |
| `subject` | string | 조건부 | 메일 제목 |
| `body` | string | 조건부 | 메일 본문(텍스트 또는 HTML 모두 가능). 20,000자 초과분은 잘림 |

- `subject`와 `body` 중 **최소 하나는 비어 있지 않아야** 한다.
- 원본 EML 전체, 첨부파일, 수신자 목록은 보내지 않는다(받지도 않는다).
- 서버는 저장 전에 이메일 주소와 긴 숫자열(전화번호·주민번호 등)을 `<email>`, `<num>`으로 가리고, 제목 200자·본문 500자까지만 남긴다. 그래도 불필요한 개인정보는 가능하면 보내지 않는 편이 좋다.

### 예시

```bash
curl -X POST http://10.30.9.146:18093/report \
  -H "X-Report-Key: <신고 키>" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{
        "reporter": "hong@company.co.kr",
        "from": "promo@cheap-pills.example",
        "subject": "[광고] 최저가 할인 오늘만",
        "body": "지금 바로 http://cheap-pills.example/buy 에서 구매하세요."
      }'
```

## 3. 응답

### 202 Accepted — 접수 성공

```json
{ "id": 12, "status": "PENDING" }
```

| 필드 | 설명 |
|---|---|
| `id` | 신고 번호. 관리자 대시보드의 신고 ID와 같다 |
| `status` | 접수 직후에는 항상 `PENDING`(LLM 분석 중) |

- 판정 결과(스팸/정상/불확실)는 이 응답에 담기지 않는다. 신고자에게는 "신고가 접수되었습니다" 정도로 안내하면 된다.
- LLM이 설정되어 있지 않거나 실패해도 신고 접수 자체는 성공(202)한다. 이 경우 관리자 화면에 "불확실"로 표시된다.

### 오류

| 상태 | 본문 예 | 의미 / 조치 |
|---|---|---|
| 400 | `{"error":"subject 또는 body가 필요합니다"}` | 제목과 본문이 모두 비어 있음 |
| 400 | (Spring 기본 오류 페이지/JSON) | JSON 형식 오류, Content-Type 누락 |
| 401 | `{"error":"invalid report key"}` | `X-Report-Key`가 없거나 틀림 |
| 5xx / 연결 실패 | — | 게이트웨이 장애. 재시도하거나 신고를 큐에 보관 |

## 4. 서버 측 처리 (참고)

```
접수(마스킹 저장) → LLM 판정: 스팸/정상/불확실 + 의견 + 룰 후보
  → 스팸이고 확신도 80% 이상: RAG 사례로 자동 편입(이후 판정 참고자료)
  → 룰(키워드) 추가는 관리자가 대시보드 "스팸 신고" 탭에서 승인해야만 반영
```

신고자가 판정 결과를 조회하는 API는 제공하지 않는다(결과는 관리자 전용).

## 5. 연동 권장 사항

1. **서버 간 호출:** 그룹웨어 서버(백엔드)가 사용자의 신고 요청을 받아 게이트웨이를 호출한다. 신고 키를 브라우저에 내려보내지 않는다.
2. **중복 방지:** 게이트웨이는 같은 메일의 중복 신고를 걸러내지 않는다. 같은 사용자가 같은 메일을 다시 신고하지 못하게 그룹웨어에서 막는다.
3. **타임아웃/재시도:** 연결 3초, 응답 5초 정도로 두고, 실패 시 사용자 흐름은 막지 않되 백그라운드 재시도를 권장한다(재시도는 중복 접수가 될 수 있음).
4. **신고 후 처리:** 사용자 메일함에서 해당 메일을 스팸함으로 옮기는 동작은 그룹웨어/Hedwig 쪽 기존 기능이며, 이 API와는 별개다.
5. **인코딩:** 본문은 반드시 UTF-8로 전송한다.

## 6. 호출 예시 (Java 8, 표준 라이브러리)

```java
URL url = new URL("http://10.30.9.146:18093/report");
HttpURLConnection c = (HttpURLConnection) url.openConnection();
c.setRequestMethod("POST");
c.setConnectTimeout(3000);
c.setReadTimeout(5000);
c.setDoOutput(true);
c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
c.setRequestProperty("X-Report-Key", reportKey);   // 서버 설정에서 읽은 값

String json = "{\"reporter\":\"" + esc(reporter) + "\",\"from\":\"" + esc(from) + "\","
        + "\"subject\":\"" + esc(subject) + "\",\"body\":\"" + esc(body) + "\"}";
try (OutputStream os = c.getOutputStream()) {
    os.write(json.getBytes(StandardCharsets.UTF_8));
}
int status = c.getResponseCode();   // 202면 접수 성공
```

`esc()`는 `"`, `\`, 개행을 JSON 이스케이프하는 함수(또는 Jackson/Gson 사용을 권장).
