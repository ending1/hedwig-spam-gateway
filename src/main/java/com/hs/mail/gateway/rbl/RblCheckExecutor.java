package com.hs.mail.gateway.rbl;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RBL DNS 조회(블로킹)를 Netty 이벤트루프 스레드 밖에서 수행하기 위한 전용 스레드풀. */
@Component
public class RblCheckExecutor implements DisposableBean {

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "gateway-rbl-check");
        t.setDaemon(true);
        return t;
    });

    public ExecutorService get() {
        return executor;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
