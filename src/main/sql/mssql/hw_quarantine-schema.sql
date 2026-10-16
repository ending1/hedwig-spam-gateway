-- 격리함 메타데이터. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_quarantine (
  id            BIGINT IDENTITY(1,1) PRIMARY KEY,
  received_at   DATETIME2 NOT NULL,
  received_day  VARCHAR(10) NOT NULL,
  client_ip     VARCHAR(45),
  mail_from     NVARCHAR(255),
  rcpt_list     NVARCHAR(500),
  subject       NVARCHAR(300),
  score         FLOAT DEFAULT 0,
  filter_name   VARCHAR(12),
  reason        NVARCHAR(500),
  auth_summary  VARCHAR(40),
  status        VARCHAR(12) NOT NULL,
  size_bytes    BIGINT DEFAULT 0,
  eml_file      VARCHAR(100)
);
CREATE INDEX idx_hw_quarantine_day ON hw_quarantine (received_day);
