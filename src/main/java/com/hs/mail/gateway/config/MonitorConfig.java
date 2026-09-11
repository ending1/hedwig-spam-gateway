package com.hs.mail.gateway.config;

import com.hs.mail.gateway.ban.BanListService;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.GatewayMetricsEndpoint;
import com.hs.mail.gateway.monitor.GreylistStats;
import com.hs.mail.gateway.monitor.RblStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
import com.hs.mail.gateway.outbound.OutboundDeliveryProcessor;
import com.hs.mail.gateway.outbound.OutboundSpoolService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorConfig {

    @Bean
    public GatewayMetricsEndpoint gatewayMetricsEndpoint(GatewayProperties properties,
                                                           ConnectionStats connectionStats,
                                                           BanListService banListService,
                                                           OutboundSpoolService outboundSpoolService,
                                                           OutboundDeliveryProcessor outboundDeliveryProcessor,
                                                           SpamFilterStats spamFilterStats,
                                                           RblStats rblStats,
                                                           GreylistStats greylistStats) {
        return new GatewayMetricsEndpoint(properties, connectionStats, banListService,
                outboundSpoolService, outboundDeliveryProcessor, spamFilterStats, rblStats, greylistStats);
    }
}
