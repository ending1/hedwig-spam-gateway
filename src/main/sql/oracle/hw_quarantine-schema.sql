-- 격리함(관리자 검토용 스팸 보관) 메타데이터. 메일 원문은 게이트웨이 서버의 파일(gateway.quarantine.dir)에 보관한다.
-- 보관 기간은 환경변수 GATEWAY_QUARANTINE_RETENTIONDAYS(기본 14일, 0이면 삭제 안 함). DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_quarantine (
  id            NUMBER(19) NOT NULL,
  received_at   timestamp NOT NULL,
  received_day  varchar2(10) NOT NULL,
  client_ip     varchar2(45),
  mail_from     varchar2(255 CHAR),
  rcpt_list     varchar2(500 CHAR),
  subject       varchar2(300 CHAR),
  score         NUMBER(7,3) DEFAULT 0,
  filter_name   varchar2(12),
  reason        varchar2(500 CHAR),
  auth_summary  varchar2(40),
  status        varchar2(12) NOT NULL,
  size_bytes    NUMBER(19) DEFAULT 0,
  eml_file      varchar2(100),
  CONSTRAINT pk_hw_quarantine PRIMARY KEY (id)
);

CREATE SEQUENCE hw_quarantine_seq START WITH 1 INCREMENT BY 1 CACHE 100;

CREATE OR REPLACE TRIGGER hw_quarantine_bir
BEFORE INSERT ON hw_quarantine
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_quarantine_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

CREATE INDEX idx_hw_quarantine_day ON hw_quarantine (received_day);

COMMENT ON TABLE hw_quarantine IS '격리함 메타데이터';
COMMENT ON COLUMN hw_quarantine.status IS 'QUARANTINED|RELEASED';
