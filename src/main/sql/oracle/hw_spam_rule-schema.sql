-- Hedwig oracle 스키마 스타일(소문자 테이블/컬럼, COMMENT ON) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE hw_spam_rule (
  id          NUMBER(19) NOT NULL,
  rule_type   varchar2(20 CHAR) NOT NULL,
  pattern     varchar2(500 CHAR) NOT NULL,
  weight      NUMBER(10,2) DEFAULT 1.0 NOT NULL,
  enabled     NUMBER(1) DEFAULT 1 NOT NULL,
  reason      varchar2(255 CHAR),
  created_at  TIMESTAMP NOT NULL,
  CONSTRAINT pk_hw_spam_rule PRIMARY KEY (id)
);

CREATE SEQUENCE hw_spam_rule_seq START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE TRIGGER hw_spam_rule_bir
BEFORE INSERT ON hw_spam_rule
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_spam_rule_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

COMMENT ON TABLE hw_spam_rule IS '룰기반 스팸 필터 - 키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치 전체 외부화';
COMMENT ON COLUMN hw_spam_rule.rule_type IS 'KEYWORD|BRAND|FREE_MAIL_DOMAIN|URL_SHORTENER|STRUCTURAL';
COMMENT ON COLUMN hw_spam_rule.pattern IS 'KEYWORD는 정규식, 나머지는 키워드/도메인/구조체크 식별자';
COMMENT ON COLUMN hw_spam_rule.weight IS '매치 시 가산할 점수';
COMMENT ON COLUMN hw_spam_rule.reason IS '룰 설명/등록 사유';

-- 초기 룰셋(코드 내장 기본값과 동일)은 hw_spam_rule-seed-data.sql을 별도로 1회 적용한다.
