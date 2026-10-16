-- 메일 처리 이벤트 로그. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_mail_event (
  id           BIGINT IDENTITY(1,1) PRIMARY KEY,
  event_at     DATETIME2 NOT NULL,
  event_day    VARCHAR(10) NOT NULL,
  action       VARCHAR(12) NOT NULL,
  filter_name  VARCHAR(12) NOT NULL,
  client_ip    VARCHAR(45),
  from_addr    NVARCHAR(255),
  from_domain  NVARCHAR(255),
  rcpt_domain  NVARCHAR(255),
  rcpt_count   INT DEFAULT 0,
  subject      NVARCHAR(200),
  score        FLOAT DEFAULT 0,
  reason       NVARCHAR(500)
);
CREATE INDEX idx_hw_mail_event_day ON hw_mail_event (event_day, action);
