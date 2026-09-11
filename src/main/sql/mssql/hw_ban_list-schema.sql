-- Hedwig mssql 배포 대상용 "안" 단계 스크립트. DBA 검토 전 초안.

CREATE TABLE HW_BAN_LIST (
  ip           VARCHAR(45) NOT NULL PRIMARY KEY,
  banned_at    DATETIME2 NOT NULL,
  reason       VARCHAR(255) NULL,
  expires_at   DATETIME2 NOT NULL
);

CREATE INDEX idx_hw_ban_list_expires ON HW_BAN_LIST (expires_at);

EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'스팸 게이트웨이 공유 밴 목록',
  @level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'HW_BAN_LIST';
