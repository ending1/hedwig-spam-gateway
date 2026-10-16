-- 관리자 변경 이력(룰 변경 등). source: DASHBOARD(화면/REST) 또는 CHAT(AI 챗 제안 후 확인). DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_admin_audit (
  id          NUMBER(19) NOT NULL,
  event_at    timestamp NOT NULL,
  actor       varchar2(100 CHAR),
  source      varchar2(20) NOT NULL,
  action      varchar2(30) NOT NULL,
  target      varchar2(200 CHAR),
  before_val  varchar2(500 CHAR),
  after_val   varchar2(500 CHAR),
  CONSTRAINT pk_hw_admin_audit PRIMARY KEY (id)
);

CREATE SEQUENCE hw_admin_audit_seq START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE TRIGGER hw_admin_audit_bir
BEFORE INSERT ON hw_admin_audit
FOR EACH ROW
BEGIN
  IF :new.id IS NULL THEN
    SELECT hw_admin_audit_seq.NEXTVAL INTO :new.id FROM dual;
  END IF;
END;
/

COMMENT ON TABLE hw_admin_audit IS '관리자 변경 이력';
