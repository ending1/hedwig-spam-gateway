-- 내장 DB(H2) 기동 시 자동 적용되는 개발/테스트용 스키마.
-- 실제 운영 배포 시에는 src/main/sql/{oracle,mariadb,mssql}/hw_ban_list-schema.sql을 DBA가 검토 후 적용한다.
CREATE TABLE IF NOT EXISTS hw_ban_list (
    ip VARCHAR(45) NOT NULL PRIMARY KEY,
    banned_at TIMESTAMP NOT NULL,
    reason VARCHAR(255),
    expires_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS hw_greylist (
    triplet_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    first_seen_at TIMESTAMP NOT NULL,
    passed_at TIMESTAMP
);
