-- 사용자 스팸 신고 + LLM 판정/관리자 검토 상태. DBA 검토 전 "안" 단계 스크립트.
CREATE TABLE hw_spam_report (
  id                 BIGINT IDENTITY(1,1) PRIMARY KEY,
  reporter           NVARCHAR(100),
  from_domain        NVARCHAR(255),
  subject            NVARCHAR(200),
  snippet            NVARCHAR(500),
  status             VARCHAR(20) NOT NULL,
  llm_verdict        VARCHAR(10),
  llm_score          FLOAT DEFAULT 0,
  llm_reason         NVARCHAR(1000),
  suggested_pattern  NVARCHAR(500),
  suggested_weight   FLOAT DEFAULT 0,
  rag_added          BIT NOT NULL DEFAULT 0,
  created_at         DATETIME2 NOT NULL
);
