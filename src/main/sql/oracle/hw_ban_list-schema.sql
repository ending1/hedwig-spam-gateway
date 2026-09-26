-- Hedwig oracle 스키마 스타일(소문자 테이블/컬럼, COMMENT ON) 답습.
-- DBA 검토 전 "안" 단계 스크립트. TABLESPACE는 현장 환경에 맞게 조정.

CREATE TABLE hw_ban_list (
  ip           varchar2(45) NOT NULL,
  banned_at    timestamp NOT NULL,
  reason       varchar2(255 CHAR),
  expires_at   timestamp NOT NULL,
  CONSTRAINT pk_hw_ban_list PRIMARY KEY (ip)
);

COMMENT ON TABLE hw_ban_list IS '스팸 게이트웨이 공유 밴 목록';
COMMENT ON COLUMN hw_ban_list.ip IS '차단 대상 IP';
COMMENT ON COLUMN hw_ban_list.banned_at IS '차단 등록 시각';
COMMENT ON COLUMN hw_ban_list.reason IS '차단 사유';
COMMENT ON COLUMN hw_ban_list.expires_at IS '차단 만료 시각';

CREATE INDEX idx_hw_ban_list_expires ON hw_ban_list (expires_at);
