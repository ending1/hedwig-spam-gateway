-- 룰별 적중 통계/마스킹 샘플. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_spam_rule_stat (
  rule_id         BIGINT NOT NULL,
  hit_count       BIGINT NOT NULL DEFAULT 0,
  spam_hit_count  BIGINT NOT NULL DEFAULT 0,
  last_hit_at     DATETIME NULL,
  PRIMARY KEY (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '스팸 룰별 누적 적중 수';

CREATE TABLE hw_spam_rule_sample (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  rule_id       BIGINT NOT NULL,
  subject       VARCHAR(200),
  snippet       VARCHAR(500),
  from_domain   VARCHAR(255),
  spam_verdict  TINYINT(1) NOT NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_hw_spam_rule_sample_rule (rule_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '룰 적중 메일의 마스킹 샘플(룰당 최근 10건)';
