package com.hs.mail.gateway.monitor;

import com.hs.mail.gateway.ban.BanListService;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.outbound.OutboundDeliveryProcessor;
import com.hs.mail.gateway.outbound.OutboundSpoolService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K8s liveness probe 없이 외부 크론/스크립트가 폴링할 수 있도록 인스턴스별 현재 커넥션 수,
 * 밴 처리 건수, 아웃바운드 스풀 상태, 스팸 판정 건수, RBL/그레이리스팅 건수를 노출한다 (스펙 4.6).
 * /actuator/gateway 로 접근.
 */
@Endpoint(id = "gateway")
public class GatewayMetricsEndpoint {

    private final GatewayProperties properties;
    private final ConnectionStats connectionStats;
    private final BanListService banListService;
    private final OutboundSpoolService outboundSpoolService;
    private final OutboundDeliveryProcessor outboundDeliveryProcessor;
    private final SpamFilterStats spamFilterStats;
    private final RblStats rblStats;
    private final GreylistStats greylistStats;

    public GatewayMetricsEndpoint(GatewayProperties properties,
                                   ConnectionStats connectionStats,
                                   BanListService banListService,
                                   OutboundSpoolService outboundSpoolService,
                                   OutboundDeliveryProcessor outboundDeliveryProcessor,
                                   SpamFilterStats spamFilterStats,
                                   RblStats rblStats,
                                   GreylistStats greylistStats) {
        this.properties = properties;
        this.connectionStats = connectionStats;
        this.banListService = banListService;
        this.outboundSpoolService = outboundSpoolService;
        this.outboundDeliveryProcessor = outboundDeliveryProcessor;
        this.spamFilterStats = spamFilterStats;
        this.rblStats = rblStats;
        this.greylistStats = greylistStats;
    }

    @ReadOperation
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", properties.getInstanceId());
        result.put("currentConnections", connectionStats.getCurrentConnections());
        result.put("currentBanCount", banListService.getCurrentBanCount());
        result.put("totalBanEventCount", banListService.getTotalBanEventCount());

        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("queueCount", outboundSpoolService.count(OutboundSpoolService.QUEUE_DIR));
        outbound.put("delayCount", outboundSpoolService.count(OutboundSpoolService.DELAY_DIR));
        outbound.put("deadLetterCount", outboundSpoolService.count(OutboundSpoolService.DEADLETTER_DIR));
        outbound.put("successCount", outboundDeliveryProcessor.getSuccessCount());
        outbound.put("failureCount", outboundDeliveryProcessor.getFailureCount());
        result.put("outbound", outbound);

        Map<String, Object> spamFilter = new LinkedHashMap<>();
        spamFilter.put("enabled", properties.getSpamFilter().isEnabled());
        spamFilter.put("provider", properties.getSpamFilter().getProvider());
        spamFilter.put("spamCount", spamFilterStats.getSpamCount());
        spamFilter.put("hamCount", spamFilterStats.getHamCount());
        spamFilter.put("classifierErrorCount", spamFilterStats.getClassifierErrorCount());
        result.put("spamFilter", spamFilter);

        Map<String, Object> rbl = new LinkedHashMap<>();
        rbl.put("enabled", properties.getRbl().isEnabled());
        rbl.put("zones", properties.getRbl().getZones());
        rbl.put("blockedCount", rblStats.getBlockedCount());
        result.put("rbl", rbl);

        Map<String, Object> greylist = new LinkedHashMap<>();
        greylist.put("enabled", properties.getGreylist().isEnabled());
        greylist.put("deferredCount", greylistStats.getDeferredCount());
        greylist.put("allowedCount", greylistStats.getAllowedCount());
        result.put("greylist", greylist);

        return result;
    }
}
