package com.hs.mail.gateway.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 테스트용 DNS: 이름별 레코드를 직접 넣고, failFor에 넣은 이름은 일시 장애로 응답한다. */
class FakeDns implements DnsLookup {

    final Map<String, List<String>> txt = new HashMap<>();
    final Map<String, List<String>> addr = new HashMap<>();
    final Map<String, List<String>> mx = new HashMap<>();
    final List<String> failFor = new ArrayList<>();
    int queries;

    FakeDns txt(String name, String... values) {
        List<String> l = txt.get(name);
        if (l == null) {
            l = new ArrayList<>();
            txt.put(name, l);
        }
        for (String v : values) {
            l.add(v);
        }
        return this;
    }

    FakeDns a(String name, String... ips) {
        addr.put(name, new ArrayList<>(java.util.Arrays.asList(ips)));
        return this;
    }

    FakeDns mx(String name, String... hosts) {
        mx.put(name, new ArrayList<>(java.util.Arrays.asList(hosts)));
        return this;
    }

    private void maybeFail(String name) throws TempFailure {
        queries++;
        if (failFor.contains(name)) {
            throw new TempFailure("fake failure " + name);
        }
    }

    @Override
    public List<String> txt(String name) throws TempFailure {
        maybeFail(name);
        List<String> l = txt.get(name);
        return l == null ? new ArrayList<String>() : l;
    }

    @Override
    public List<String> addresses(String name) throws TempFailure {
        maybeFail(name);
        List<String> l = addr.get(name);
        return l == null ? new ArrayList<String>() : l;
    }

    @Override
    public List<String> mx(String name) throws TempFailure {
        maybeFail(name);
        List<String> l = mx.get(name);
        return l == null ? new ArrayList<String>() : l;
    }
}
