-- 사용자 스팸 신고 + LLM 판정/관리자 검토 상태. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_spam_report (
  id                 NUMBER(19) NOT NULL,
  reporter           varchar2(100 CHAR),
  from_domain        varchar2(255 CHAR),
  subject            varchar2(200 CHAR),
  snippet            varchar2(500 CHAR),
  status             varchar2(20) NOT NULL,
  llm_verdict        varchar2(10),
  llm_score          NUMBER(5,2) DEFAULT 0,
  llm_reason         varchar2(1000 CHAR),
  suggested_pattern  varchar2(500 CHAR),
  suggested_weight   NUMBER(5,2) DEFAULT 0,
  rag_added          NUMBER(1) DEFAULT 0 NOT NULL,
  created_at         timestamp NOT NULL,
  CONSTRAINT pk_hw_spam_report PRIMARY KEY (id)
);

CREATE SEQUENCE hw_spam_report_seq START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE TRIGGER hw_spam_report_bir
BEFORE INSERT ON hw_spam_report
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_spam_report_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

COMMENT ON TABLE hw_spam_report IS '사용자 스팸 신고와 LLM 판정 결과';
COMMENT ON COLUMN hw_spam_report.status IS 'PENDING|ANALYZED|RULE_APPROVED|DISMISSED';
COMMENT ON COLUMN hw_spam_report.rag_added IS '1이면 RAG 사례로 사용 중';
