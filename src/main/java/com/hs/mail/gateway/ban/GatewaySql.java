package com.hs.mail.gateway.ban;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Hedwig의 {@code Config.getSQL(key)} / hedwig_sql.properties 패턴을 그대로 답습한다.
 * SQL은 코드에 직접 쓰지 않고 gateway-sql.properties에 "&lt;dialect&gt;.&lt;key&gt;" 형태로 외부화한다.
 */
@Component
public class GatewaySql {

    private final Properties sqlProperties = new Properties();
    private final GatewayProperties gatewayProperties;

    public GatewaySql(GatewayProperties gatewayProperties,
                       @Value("classpath:gateway-sql.properties") Resource resource) throws IOException {
        this.gatewayProperties = gatewayProperties;
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            sqlProperties.load(reader);
        }
    }

    public String get(String key) {
        String dialectKey = gatewayProperties.getDbDialect() + "." + key;
        String sql = sqlProperties.getProperty(dialectKey);
        if (sql == null) {
            sql = sqlProperties.getProperty("ansi." + key);
        }
        if (sql == null) {
            throw new IllegalStateException("gateway-sql.properties에 정의되지 않은 SQL 키: " + dialectKey);
        }
        return sql.trim();
    }
}
