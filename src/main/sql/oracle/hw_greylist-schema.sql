-- Hedwig oracle 스키마 스타일(소문자 테이블/컬럼, COMMENT ON) 답습.
-- DBA 검토 전 "안" 단계 스크립트. TABLESPACE는 현장 환경에 맞게 조정.

CREATE TABLE hw_greylist (
  triplet_hash   varchar2(64 CHAR) NOT NULL,
  first_seen_at  timestamp NOT NULL,
  passed_at      timestamp,
  CONSTRAINT pk_hw_greylist PRIMARY KEY (triplet_hash)
);

COMMENT ON TABLE hw_greylist IS '스팸 게이트웨이 그레이리스팅 (발신IP,MAIL FROM,RCPT TO) 삼중항 상태';
COMMENT ON COLUMN hw_greylist.triplet_hash IS 'SHA-256(발신IP|MAIL FROM|RCPT TO)';
COMMENT ON COLUMN hw_greylist.first_seen_at IS '최초 관측 시각';
COMMENT ON COLUMN hw_greylist.passed_at IS '재시도 통과(그레이리스팅 해제) 시각, NULL이면 대기중';
