package com.hs.mail.gateway.auth;

import java.util.List;

/** 한 메일의 인증 검사 전체에 시간 상한을 거는 래퍼: 상한을 넘으면 더 조회하지 않고 TempFailure(결과는 temperror). */
public final class DeadlineDns implements DnsLookup {

    private final DnsLookup delegate;
    private final long deadlineMillis;

    public DeadlineDns(DnsLookup delegate, long deadlineMillis) {
        this.delegate = delegate;
        this.deadlineMillis = deadlineMillis;
    }

    private void check() throws TempFailure {
        if (System.currentTimeMillis() > deadlineMillis) {
            throw new TempFailure("인증 검사 시간 초과");
        }
    }

    @Override
    public List<String> txt(String name) throws TempFailure {
        check();
        return delegate.txt(name);
    }

    @Override
    public List<String> addresses(String name) throws TempFailure {
        check();
        return delegate.addresses(name);
    }

    @Override
    public List<String> mx(String name) throws TempFailure {
        check();
        return delegate.mx(name);
    }
}
