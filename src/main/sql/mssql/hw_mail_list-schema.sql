-- Hedwig mssql 배포 대상용 "안" 단계 스크립트. DBA 검토 전 초안.

CREATE TABLE HW_MAIL_LIST (
  list_type   VARCHAR(5) NOT NULL,
  pattern     VARCHAR(255) NOT NULL,
  recipient   VARCHAR(255) NOT NULL DEFAULT '',
  reason      VARCHAR(255) NULL,
  CONSTRAINT pk_hw_mail_list PRIMARY KEY (list_type, pattern, recipient)
);

EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'스팸 게이트웨이 화이트/블랙리스트',
  @level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'HW_MAIL_LIST';
