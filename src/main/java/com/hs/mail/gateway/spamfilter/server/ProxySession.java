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
    /** 화이트리스트 매치 - DATA 단계의 스팸 판정(룰기반/LLM)을 모두 건너뛰고 무조건 정상 처리. */
    boolean whitelisted;
    /** 블랙리스트 매치(태그 모드) - 스팸 판정 없이 무조건 스팸 헤더를 강제 주입. */
    boolean forceSpamTag;

    void resetTransaction() {
        dataPending = false;
        bufferingData = false;
        dataBuffer = new ArrayList<>();
        mailFrom = null;
        recipients = new ArrayList<>();
        whitelisted = false;
        forceSpamTag = false;
    }
}
