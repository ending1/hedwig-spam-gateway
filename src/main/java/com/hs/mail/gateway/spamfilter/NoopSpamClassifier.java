package com.hs.mail.gateway.spamfilter;

/** gateway.spam-filter.enabled=false 또는 provider=none일 때 사용되는 항상-정상 분류기. */
public class NoopSpamClassifier implements SpamClassifier {

    @Override
    public SpamVerdict classify(SpamCheckRequest request) {
        return SpamVerdict.ham(name());
    }

    @Override
    public String name() {
        return "none";
    }
}
