-- 관리자 변경 이력. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_admin_audit (
  id          BIGINT IDENTITY(1,1) PRIMARY KEY,
  event_at    DATETIME2 NOT NULL,
  actor       NVARCHAR(100),
  source      VARCHAR(20) NOT NULL,
  action      VARCHAR(30) NOT NULL,
  target      NVARCHAR(200),
  before_val  NVARCHAR(500),
  after_val   NVARCHAR(500)
);
