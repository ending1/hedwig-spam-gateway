package com.hs.mail.gateway.osblock;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OS 레벨 차단(iptables)은 Linux 한정 선택 기능이다 (스펙 4.3).
 * AIX/SunOS에서는 애플리케이션 레벨 차단(BanCheckHandler)만 동작하며, 여기서는 반드시 no-op 처리한다.
 */
@Component
public class IptablesBlocker {

    private static final Logger log = LoggerFactory.getLogger(IptablesBlocker.class);

    private final GatewayProperties properties;
    private final boolean linux;

    public IptablesBlocker(GatewayProperties properties) {
        this.properties = properties;
        this.linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    public void block(String ip) {
        if (!properties.getOsBlock().isEnabled()) {
            return;
        }
        if (!linux) {
            log.debug("gateway.os-block.enabled=true이지만 Linux가 아니므로 애플리케이션 레벨 차단만 수행: ip={}", ip);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("iptables", "-A", "INPUT", "-s", ip, "-j", "DROP");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exit = process.waitFor();
            if (exit != 0) {
                log.warn("iptables 차단 규칙 추가 실패(exit={}): ip={}", exit, ip);
            }
        } catch (Exception e) {
            log.warn("iptables 실행 중 오류, 애플리케이션 레벨 차단으로 대체: ip={}", ip, e);
        }
    }
}
