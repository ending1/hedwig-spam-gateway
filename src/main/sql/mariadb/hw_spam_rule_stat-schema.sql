-- 룰별 적중 통계/마스킹 샘플. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE HW_SPAM_RULE_STAT (
  rule_id         BIGINT NOT NULL,
  hit_count       BIGINT NOT NULL DEFAULT 0,
  spam_hit_count  BIGINT NOT NULL DEFAULT 0,
  last_hit_at     DATETIME NULL,
  PRIMARY KEY (rule_id)
) COMMENT = '스팸 룰별 누적 적중 수';

CREATE TABLE HW_SPAM_RULE_SAMPLE (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  rule_id       BIGINT NOT NULL,
  subject       VARCHAR(200),
  snippet       VARCHAR(500),
  from_domain   VARCHAR(255),
  spam_verdict  TINYINT(1) NOT NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY IX_HW_SPAM_RULE_SAMPLE_RULE (rule_id)
) COMMENT = '룰 적중 메일의 마스킹 샘플(룰당 최근 10건)';
