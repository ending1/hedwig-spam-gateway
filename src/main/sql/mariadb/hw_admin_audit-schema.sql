-- 관리자 변경 이력. DBA 검토 전 "안" 단계 스크립트(소문자 테이블명).
CREATE TABLE hw_admin_audit (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  event_at    DATETIME NOT NULL,
  actor       VARCHAR(100),
  source      VARCHAR(20) NOT NULL COMMENT 'DASHBOARD|CHAT',
  action      VARCHAR(30) NOT NULL,
  target      VARCHAR(200),
  before_val  VARCHAR(500),
  after_val   VARCHAR(500),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '관리자 변경 이력';
