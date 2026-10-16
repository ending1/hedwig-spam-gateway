-- 메일 처리 이벤트 로그. DBA 검토 전 "안" 단계 스크립트(소문자 테이블명).
CREATE TABLE hw_mail_event (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  event_at     DATETIME NOT NULL,
  event_day    VARCHAR(10) NOT NULL,
  action       VARCHAR(12) NOT NULL COMMENT 'HAM|SPAM|REJECTED|DEFERRED',
  filter_name  VARCHAR(12) NOT NULL COMMENT 'RBL|BLACKLIST|WHITELIST|GREYLIST|RULE|LLM|NONE',
  client_ip    VARCHAR(45),
  from_addr    VARCHAR(255),
  from_domain  VARCHAR(255),
  rcpt_domain  VARCHAR(255),
  rcpt_count   INT DEFAULT 0,
  subject      VARCHAR(200),
  score        DOUBLE DEFAULT 0,
  reason       VARCHAR(500),
  PRIMARY KEY (id),
  KEY idx_hw_mail_event_day (event_day, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '메일 처리 이벤트 로그';
