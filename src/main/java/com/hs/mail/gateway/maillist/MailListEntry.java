package com.hs.mail.gateway.maillist;

/**
 * hw_mail_list 테이블 한 행. pattern은 정확한 이메일("user@domain.com") 또는 "@domain.com" 형태의
 * 도메인 와일드카드. recipient가 빈 문자열이면 전역(관리자) 규칙, 특정 주소면 해당 수신자 전용 규칙.
 */
public class MailListEntry {

    public enum ListType {
        WHITE, BLACK
    }

    private final ListType listType;
    private final String pattern;
    private final String recipient;
    private final String reason;

    public MailListEntry(ListType listType, String pattern, String recipient, String reason) {
        this.listType = listType;
        this.pattern = pattern;
        this.recipient = recipient == null ? "" : recipient;
        this.reason = reason;
    }

    public ListType getListType() {
        return listType;
    }

    public String getPattern() {
        return pattern;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getReason() {
        return reason;
    }

    public boolean isGlobal() {
        return recipient == null || recipient.isEmpty();
    }

    /** sender/recipient가 이 규칙에 매치되는지 확인. */
    public boolean matches(String sender, String recipientAddress) {
        if (!isGlobal() && !recipient.equalsIgnoreCase(recipientAddress)) {
            return false;
        }
        if (sender == null) {
            return false;
        }
        if (pattern.startsWith("@")) {
            String domain = pattern.substring(1);
            int at = sender.indexOf('@');
            return at >= 0 && sender.substring(at + 1).equalsIgnoreCase(domain);
        }
        return pattern.equalsIgnoreCase(sender);
    }
}
