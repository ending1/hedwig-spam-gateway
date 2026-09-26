-- Hedwig mariadb 스키마 스타일(대문자 테이블/컬럼, 인라인 COMMENT) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE hw_mail_list (
  list_type   VARCHAR(5) NOT NULL COMMENT 'WHITE 또는 BLACK',
  pattern     VARCHAR(255) NOT NULL COMMENT '이메일 또는 @도메인 와일드카드',
  recipient   VARCHAR(255) NOT NULL DEFAULT '*' COMMENT '*=전역(Oracle 호환을 위해 빈 문자열 대신 센티널 사용), 아니면 특정 수신자 전용',
  reason      VARCHAR(255) COMMENT '등록 사유',
  PRIMARY KEY (list_type, pattern, recipient)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '스팸 게이트웨이 화이트/블랙리스트';
