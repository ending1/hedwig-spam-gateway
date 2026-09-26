-- Hedwig mariadb 스키마 스타일(대문자 테이블/컬럼, 인라인 COMMENT) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE hw_greylist (
  triplet_hash   VARCHAR(64) NOT NULL COMMENT 'SHA-256(발신IP|MAIL FROM|RCPT TO)',
  first_seen_at  DATETIME NOT NULL COMMENT '최초 관측 시각',
  passed_at      DATETIME COMMENT '재시도 통과 시각, NULL이면 대기중',
  PRIMARY KEY (triplet_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '스팸 게이트웨이 그레이리스팅 삼중항 상태';
