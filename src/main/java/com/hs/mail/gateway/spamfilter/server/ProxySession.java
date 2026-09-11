package com.hs.mail.gateway.spamfilter.server;

import java.util.ArrayList;
import java.util.List;

/**
 * front/back 핸들러가 공유하는 트랜잭션 상태. front(client) 채널과 back(backend) 채널은
 * 항상 같은 EventLoop 스레드에 고정되므로(연결 시 frontChannel.eventLoop() 재사용) 동기화가 필요 없다.
 */
class ProxySession {

    boolean dataPending;
    boolean bufferingData;
    List<String> dataBuffer = new ArrayList<>();
    String mailFrom;
    List<String> recipients = new ArrayList<>();

    void resetTransaction() {
        dataPending = false;
        bufferingData = false;
        dataBuffer = new ArrayList<>();
        mailFrom = null;
        recipients = new ArrayList<>();
    }
}
