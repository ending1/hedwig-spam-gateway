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
    /** EHLO/HELO로 받은 이름(연결 동안 유지). SPF의 HELO 검사와 반송 메일(MAIL FROM:<>)에 쓴다. */
    String helo = "";
    /** 현재 유효한 backend 연결의 세대. 보류로 backend를 교체하면 올라가고, 옛 연결의 응답/종료는 무시된다. */
    int backendGeneration;
    /** backend 교체 직후 재전송한 EHLO에 대한 응답(배너 1 + EHLO 1)처럼 client에 전달하지 말고 버릴 응답 개수. */
    int suppressReplies;
    /** 이번 메일의 SPF/DKIM/DMARC 결과(검증하지 않았으면 null). */
    com.hs.mail.gateway.auth.AuthResult authResult;

    void resetTransaction() {
        dataPending = false;
        bufferingData = false;
        dataBuffer = new ArrayList<>();
        mailFrom = null;
        recipients = new ArrayList<>();
        whitelisted = false;
        forceSpamTag = false;
        authResult = null;
    }
}
