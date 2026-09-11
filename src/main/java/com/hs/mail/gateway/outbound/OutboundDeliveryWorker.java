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
 * 신규/재시도 메일(queue 디렉터리)을 처리하는 워커. Hedwig의 {@code remote.executor}(20~40 스레드)와
 * 동일한 목적으로, Hedwig 프로세스와는 별도 JVM인 이 게이트웨이 안에서 아웃바운드 발송 부하를 흡수한다.
 */
@Component
public class OutboundDeliveryWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryWorker.class);

    private final OutboundSpoolService spoolService;
    private final OutboundDeliveryProcessor processor;
    private final GatewayProperties properties;
    private final ThreadPoolExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public OutboundDeliveryWorker(OutboundSpoolService spoolService, OutboundDeliveryProcessor processor,
                                   GatewayProperties properties) {
        this.spoolService = spoolService;
        this.processor = processor;
        this.properties = properties;
        GatewayProperties.Outbound cfg = properties.getOutbound();
        this.executor = new ThreadPoolExecutor(
                cfg.getWorkerPoolSize(), cfg.getWorkerMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "gateway-outbound-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Scheduled(fixedDelayString = "2000")
    public void scanAndDispatch() {
        if (!properties.getOutbound().isEnabled()) {
            return;
        }
        List<OutboundMailItem> due = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis());
        for (OutboundMailItem item : due) {
            if (!inFlight.add(item.getId())) {
                continue;
            }
            executor.submit(() -> {
                try {
                    processor.process(item, OutboundSpoolService.QUEUE_DIR);
                } catch (Exception e) {
                    log.error("아웃바운드 발송 처리 중 예외: id={}", item.getId(), e);
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
