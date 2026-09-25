-- 룰별 적중 통계/마스킹 샘플. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE HW_SPAM_RULE_STAT (
  rule_id         NUMBER(19) NOT NULL PRIMARY KEY,
  hit_count       NUMBER(19) DEFAULT 0 NOT NULL,
  spam_hit_count  NUMBER(19) DEFAULT 0 NOT NULL,
  last_hit_at     TIMESTAMP
);

CREATE TABLE HW_SPAM_RULE_SAMPLE (
  id            NUMBER(19) GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_id       NUMBER(19) NOT NULL,
  subject       VARCHAR2(200),
  snippet       VARCHAR2(500),
  from_domain   VARCHAR2(255),
  spam_verdict  NUMBER(1) NOT NULL,
  created_at    TIMESTAMP NOT NULL
);
CREATE INDEX IX_HW_SPAM_RULE_SAMPLE_RULE ON HW_SPAM_RULE_SAMPLE (rule_id);
