-- 룰별 적중 통계/마스킹 샘플. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE HW_SPAM_RULE_STAT (
  rule_id         BIGINT NOT NULL PRIMARY KEY,
  hit_count       BIGINT NOT NULL DEFAULT 0,
  spam_hit_count  BIGINT NOT NULL DEFAULT 0,
  last_hit_at     DATETIME2 NULL
);

CREATE TABLE HW_SPAM_RULE_SAMPLE (
  id            BIGINT IDENTITY(1,1) PRIMARY KEY,
  rule_id       BIGINT NOT NULL,
  subject       NVARCHAR(200),
  snippet       NVARCHAR(500),
  from_domain   NVARCHAR(255),
  spam_verdict  BIT NOT NULL,
  created_at    DATETIME2 NOT NULL
);
CREATE INDEX IX_HW_SPAM_RULE_SAMPLE_RULE ON HW_SPAM_RULE_SAMPLE (rule_id);
