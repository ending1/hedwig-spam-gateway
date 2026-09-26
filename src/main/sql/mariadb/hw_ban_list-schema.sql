-- Hedwig mariadb 스키마 스타일(대문자 테이블/컬럼, 인라인 COMMENT) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE hw_ban_list (
  ip           VARCHAR(45) NOT NULL COMMENT '차단 대상 IP',
  banned_at    DATETIME NOT NULL COMMENT '차단 등록 시각',
  reason       VARCHAR(255) COMMENT '차단 사유',
  expires_at   DATETIME NOT NULL COMMENT '차단 만료 시각',
  PRIMARY KEY (ip),
  KEY idx_hw_ban_list_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '스팸 게이트웨이 공유 밴 목록';
