-- 격리함 메타데이터(소문자 테이블명). DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_quarantine (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  received_at   DATETIME NOT NULL,
  received_day  VARCHAR(10) NOT NULL,
  client_ip     VARCHAR(45),
  mail_from     VARCHAR(255),
  rcpt_list     VARCHAR(500),
  subject       VARCHAR(300),
  score         DOUBLE DEFAULT 0,
  filter_name   VARCHAR(12),
  reason        VARCHAR(500),
  auth_summary  VARCHAR(40),
  status        VARCHAR(12) NOT NULL COMMENT 'QUARANTINED|RELEASED',
  size_bytes    BIGINT DEFAULT 0,
  eml_file      VARCHAR(100),
  PRIMARY KEY (id),
  KEY idx_hw_quarantine_day (received_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '격리함 메타데이터';
