-- Hedwig mariadb 스키마 스타일(대문자 테이블/컬럼, 인라인 COMMENT) 답습.
-- DBA 검토 전 "안" 단계 스크립트.

CREATE TABLE HW_SPAM_RULE (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  rule_type   VARCHAR(20) NOT NULL COMMENT 'KEYWORD|BRAND|FREE_MAIL_DOMAIN|URL_SHORTENER|STRUCTURAL',
  pattern     VARCHAR(500) NOT NULL COMMENT 'KEYWORD는 정규식, 나머지는 키워드/도메인/구조체크 식별자',
  weight      DOUBLE NOT NULL DEFAULT 1.0 COMMENT '매치 시 가산할 점수',
  enabled     TINYINT(1) NOT NULL DEFAULT 1,
  reason      VARCHAR(255) COMMENT '룰 설명/등록 사유',
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '룰기반 스팸 필터 - 키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치 전체 외부화';

-- 초기 룰셋(코드 내장 기본값과 동일)은 hw_spam_rule-seed-data.sql을 별도로 1회 적용한다.
