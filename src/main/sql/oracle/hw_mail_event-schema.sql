-- 메일 처리 이벤트 로그(통계/로그 검색/챗봇 질의 원천). DBA 검토 전 "안" 단계 스크립트.
-- 보관 기간은 게이트웨이 환경변수 GATEWAY_EVENTLOG_RETENTIONDAYS(기본 90일, 0이면 삭제 안 함)로 게이트웨이가 정리한다.
CREATE TABLE hw_mail_event (
  id           NUMBER(19) NOT NULL,
  event_at     timestamp NOT NULL,
  event_day    varchar2(10) NOT NULL,
  action       varchar2(12) NOT NULL,
  filter_name  varchar2(12) NOT NULL,
  client_ip    varchar2(45),
  from_addr    varchar2(255 CHAR),
  from_domain  varchar2(255 CHAR),
  rcpt_domain  varchar2(255 CHAR),
  rcpt_count   NUMBER(5) DEFAULT 0,
  subject      varchar2(200 CHAR),
  score        NUMBER(7,3) DEFAULT 0,
  reason       varchar2(500 CHAR),
  CONSTRAINT pk_hw_mail_event PRIMARY KEY (id)
);

CREATE SEQUENCE hw_mail_event_seq START WITH 1 INCREMENT BY 1 CACHE 100;

CREATE OR REPLACE TRIGGER hw_mail_event_bir
BEFORE INSERT ON hw_mail_event
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_mail_event_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

CREATE INDEX idx_hw_mail_event_day ON hw_mail_event (event_day, action);

COMMENT ON TABLE hw_mail_event IS '메일 처리 이벤트 로그';
COMMENT ON COLUMN hw_mail_event.action IS 'HAM|SPAM|REJECTED|DEFERRED';
COMMENT ON COLUMN hw_mail_event.filter_name IS 'RBL|BLACKLIST|WHITELIST|GREYLIST|RULE|LLM|NONE';
