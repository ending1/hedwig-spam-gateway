-- Hedwig mssql 배포 대상용 "안" 단계 스크립트. DBA 검토 전 초안.

CREATE TABLE HW_SPAM_RULE (
  id          BIGINT IDENTITY(1,1) NOT NULL,
  rule_type   VARCHAR(20) NOT NULL,
  pattern     VARCHAR(500) NOT NULL,
  weight      FLOAT NOT NULL DEFAULT 1.0,
  enabled     BIT NOT NULL DEFAULT 1,
  reason      VARCHAR(255) NULL,
  created_at  DATETIME2 NOT NULL,
  CONSTRAINT pk_hw_spam_rule PRIMARY KEY (id)
);

EXEC sys.sp_addextendedproperty @name=N'MS_Description',
  @value=N'룰기반 스팸 필터 - 키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치 전체 외부화',
  @level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'HW_SPAM_RULE';

-- 초기 룰셋(코드 내장 기본값과 동일)은 hw_spam_rule-seed-data.sql을 별도로 1회 적용한다.
