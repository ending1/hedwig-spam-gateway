package com.hs.mail.gateway.auth;

import java.util.List;

/**
 * 메일 인증(SPF/DKIM/DMARC)에 필요한 DNS 조회 추상화. 없는 이름(NXDOMAIN)이나 해당 타입 없음은 빈 목록이고,
 * 서버 오류/시간 초과처럼 "알 수 없음"은 {@link TempFailure}로 구분한다(인증 결과가 temperror가 된다).
 */
public interface DnsLookup {

    /** 일시적 DNS 장애. */
    class TempFailure extends Exception {
        public TempFailure(String message) {
            super(message);
        }
    }

    /** TXT 레코드들. 한 레코드가 여러 문자열 조각이면 이어 붙인 값 하나로 돌려준다. */
    List<String> txt(String name) throws TempFailure;

    /** A + AAAA 주소(문자열). */
    List<String> addresses(String name) throws TempFailure;

    /** MX 대상 호스트 이름(우선순위 순). */
    List<String> mx(String name) throws TempFailure;
}
