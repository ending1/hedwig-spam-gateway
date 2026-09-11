package com.hs.mail.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * 전체 스프링 컨텍스트가 정상 기동되는지(빈 배선/설정 바인딩 오류 조기 발견) 확인한다.
 * 실제 Netty 리스너/DB는 내장 H2 + 임의 포트로 대체한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gateway.listen-port=0",
        "gateway.outbound.listen-port=0"
})
class GatewayApplicationContextTest {

    @TempDir
    static Path spoolDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.outbound.spool-dir", () -> spoolDir.toString());
    }

    @Test
    void 컨텍스트가_정상적으로_로드된다() {
        // 컨텍스트 로딩 자체가 검증 대상 - 예외 없이 끝나면 성공
    }
}
