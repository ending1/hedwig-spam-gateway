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

CREATE TABLE IF NOT EXISTS hw_mail_list (
    list_type VARCHAR(5) NOT NULL,
    pattern VARCHAR(255) NOT NULL,
    recipient VARCHAR(255) NOT NULL DEFAULT '',
    reason VARCHAR(255),
    PRIMARY KEY (list_type, pattern, recipient)
);

CREATE TABLE IF NOT EXISTS hw_spam_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_type VARCHAR(20) NOT NULL,
    pattern VARCHAR(500) NOT NULL,
    weight DOUBLE NOT NULL DEFAULT 1.0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

-- 기본 룰셋 시드는 SpamRuleService가 기동 시 hw_spam_rule이 비어 있을 때만 넣는다(인메모리/파일/외부 DB 공통).
-- 이 스크립트는 기동할 때마다 실행되므로 INSERT를 두면 파일 DB에서 재기동마다 중복된다.

CREATE TABLE IF NOT EXISTS hw_spam_rule_stat (
    rule_id BIGINT PRIMARY KEY,
    hit_count BIGINT NOT NULL DEFAULT 0,
    spam_hit_count BIGINT NOT NULL DEFAULT 0,
    last_hit_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS hw_spam_rule_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    subject VARCHAR(200),
    snippet VARCHAR(500),
    from_domain VARCHAR(255),
    spam_verdict BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS hw_spam_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter VARCHAR(100),
    from_domain VARCHAR(255),
    subject VARCHAR(200),
    snippet VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    llm_verdict VARCHAR(10),
    llm_score DOUBLE DEFAULT 0,
    llm_reason VARCHAR(1000),
    suggested_pattern VARCHAR(500),
    suggested_weight DOUBLE DEFAULT 0,
    rag_added BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);
