package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@code delay-after-retries}회 이상 실패한 느린/불안정 도메인 메일만 처리하는 별도 워커.
 * Hedwig의 {@code delay.executor}와 동일한 목적으로, 소규모 전용 스레드풀에 격리해 신규 메일
 * 처리(queue, {@link OutboundDeliveryWorker})가 느린 도메인 때문에 지연되지 않도록 한다.
 */
@Component
public class OutboundDelayWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboundDelayWorker.class);

    private final OutboundSpoolService spoolService;
    private final OutboundDeliveryProcessor processor;
    private final GatewayProperties properties;
    private final ThreadPoolExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public OutboundDelayWorker(OutboundSpoolService spoolService, OutboundDeliveryProcessor processor,
                                GatewayProperties properties) {
        this.spoolService = spoolService;
        this.processor = processor;
        this.properties = properties;
        GatewayProperties.Outbound cfg = properties.getOutbound();
        this.executor = new ThreadPoolExecutor(
                cfg.getDelayWorkerPoolSize(), cfg.getDelayWorkerMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "gateway-outbound-delay-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Scheduled(fixedDelayString = "10000")
    public void scanAndDispatch() {
        if (!properties.getOutbound().isEnabled()) {
            return;
        }
        List<OutboundMailItem> due = spoolService.listDue(OutboundSpoolService.DELAY_DIR, System.currentTimeMillis());
        for (OutboundMailItem item : due) {
            if (!inFlight.add(item.getId())) {
                continue;
            }
            executor.submit(() -> {
                try {
                    processor.process(item, OutboundSpoolService.DELAY_DIR);
                } catch (Exception e) {
                    log.error("delay 큐 발송 처리 중 예외: id={}", item.getId(), e);
                } finally {
                    inFlight.remove(item.getId());
                }
            });
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
