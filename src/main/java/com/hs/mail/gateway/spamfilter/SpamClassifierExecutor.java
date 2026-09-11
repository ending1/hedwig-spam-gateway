package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 분류기 HTTP 호출(블로킹)을 Netty 이벤트루프 스레드 밖에서 수행하기 위한 전용 스레드풀.
 * 크기는 {@code gateway.spam-filter.worker-pool-size}로 제어한다.
 */
@Component
public class SpamClassifierExecutor implements DisposableBean {

    private final ExecutorService executor;

    public SpamClassifierExecutor(GatewayProperties properties) {
        int poolSize = Math.max(1, properties.getSpamFilter().getWorkerPoolSize());
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "gateway-spam-classifier");
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService get() {
        return executor;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
