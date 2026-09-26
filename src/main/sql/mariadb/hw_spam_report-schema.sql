-- 사용자 스팸 신고 + LLM 판정/관리자 검토 상태. DBA 검토 전 "안" 단계 스크립트(소문자 테이블명).
CREATE TABLE hw_spam_report (
  id                 BIGINT NOT NULL AUTO_INCREMENT,
  reporter           VARCHAR(100),
  from_domain        VARCHAR(255),
  subject            VARCHAR(200),
  snippet            VARCHAR(500),
  status             VARCHAR(20) NOT NULL COMMENT 'PENDING|ANALYZED|RULE_APPROVED|DISMISSED',
  llm_verdict        VARCHAR(10),
  llm_score          DOUBLE DEFAULT 0,
  llm_reason         VARCHAR(1000),
  suggested_pattern  VARCHAR(500),
  suggested_weight   DOUBLE DEFAULT 0,
  rag_added          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 RAG 사례로 사용 중',
  created_at         DATETIME NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '사용자 스팸 신고와 LLM 판정 결과';
