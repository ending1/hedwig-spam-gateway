-- Hedwig mssql 배포 대상용 "안" 단계 스크립트. DBA 검토 전 초안.

CREATE TABLE HW_GREYLIST (
  triplet_hash   VARCHAR(64) NOT NULL PRIMARY KEY,
  first_seen_at  DATETIME2 NOT NULL,
  passed_at      DATETIME2 NULL
);

EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'스팸 게이트웨이 그레이리스팅 삼중항 상태',
  @level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'HW_GREYLIST';
