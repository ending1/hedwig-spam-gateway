-- Hedwig oracle 스키마 스타일(소문자 테이블/컬럼) 답습. DBA 검토 전 "안" 단계 스크립트.
-- 룰별 적중 통계 / 적중 메일 마스킹 샘플. 한글이 들어가므로 varchar2는 CHAR 단위로 잡는다.

CREATE TABLE hw_spam_rule_stat (
  rule_id         NUMBER(19) NOT NULL,
  hit_count       NUMBER(19) DEFAULT 0 NOT NULL,
  spam_hit_count  NUMBER(19) DEFAULT 0 NOT NULL,
  last_hit_at     timestamp,
  CONSTRAINT pk_hw_spam_rule_stat PRIMARY KEY (rule_id)
);

CREATE TABLE hw_spam_rule_sample (
  id            NUMBER(19) NOT NULL,
  rule_id       NUMBER(19) NOT NULL,
  subject       varchar2(200 CHAR),
  snippet       varchar2(500 CHAR),
  from_domain   varchar2(255 CHAR),
  spam_verdict  NUMBER(1) NOT NULL,
  created_at    timestamp NOT NULL,
  CONSTRAINT pk_hw_spam_rule_sample PRIMARY KEY (id)
);

CREATE SEQUENCE hw_spam_rule_sample_seq START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE TRIGGER hw_spam_rule_sample_bir
BEFORE INSERT ON hw_spam_rule_sample
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_spam_rule_sample_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

CREATE INDEX idx_hw_spam_rule_sample_rule ON hw_spam_rule_sample (rule_id, id);

COMMENT ON TABLE hw_spam_rule_stat IS '스팸 룰별 누적 적중 수';
COMMENT ON TABLE hw_spam_rule_sample IS '룰 적중 메일의 마스킹 샘플(룰당 최근 10건)';
