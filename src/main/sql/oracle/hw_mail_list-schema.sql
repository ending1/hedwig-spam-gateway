-- Hedwig oracle 스키마 스타일(소문자 테이블/컬럼, COMMENT ON) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE hw_mail_list (
  list_type   varchar2(5 CHAR) NOT NULL,
  pattern     varchar2(255 CHAR) NOT NULL,
  recipient   varchar2(255 CHAR) DEFAULT '*' NOT NULL,
  reason      varchar2(255 CHAR),
  CONSTRAINT pk_hw_mail_list PRIMARY KEY (list_type, pattern, recipient)
);

COMMENT ON TABLE hw_mail_list IS '스팸 게이트웨이 화이트/블랙리스트';
COMMENT ON COLUMN hw_mail_list.list_type IS 'WHITE 또는 BLACK';
COMMENT ON COLUMN hw_mail_list.pattern IS '이메일 또는 @도메인 와일드카드';
COMMENT ON COLUMN hw_mail_list.recipient IS ''*'이면 전역(Oracle은 ''가 NULL이라 센티널 사용), 아니면 특정 수신자 전용 규칙';
COMMENT ON COLUMN hw_mail_list.reason IS '등록 사유';
