package com.hs.mail.gateway;

import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파일 H2로 실제 스프링 컨텍스트를 띄워, 스키마가 만들어지고 기본 룰셋이 시드되는지 확인한다.
 * (Spring Boot는 기본적으로 파일 H2를 임베디드로 보지 않아 schema.sql을 건너뛰는 함정이 있다 -
 * spring.sql.init.mode=always로 막아 두었고, 이 테스트가 그 회귀를 지킨다.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gateway.listen-port=0",
        "gateway.outbound.listen-port=0"
})
class FileDbContextTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tmp.resolve("db").toAbsolutePath() + ";MODE=MySQL");
        registry.add("gateway.outbound.spool-dir", () -> tmp.resolve("spool").toString());
    }

    @Autowired
    SpamRuleService ruleService;

    @Test
    void 파일_DB로_기동해도_스키마가_만들어지고_기본_룰셋이_시드된다() {
        assertTrue(ruleService.list().size() > 100, "룰이 0개면 파일 DB에서 schema.sql이 실행되지 않은 것: " + ruleService.list().size());
    }
}
