package com.hs.mail.gateway.spamfilter;

/** 세 분류기(Gemma/Gemini/Claude)가 공유하는 프롬프트 생성기. */
public final class SpamPromptBuilder {

    private SpamPromptBuilder() {
    }

    public static String build(SpamCheckRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 이메일 스팸 필터입니다. 아래 메일이 스팸인지 판단하고, ")
          .append("반드시 다른 설명 없이 아래 JSON 스키마 형식으로만 답하세요.\n")
          .append("{\"spam\": true|false, \"score\": 0.0~1.0, \"reason\": \"짧은 이유\"}\n\n")
          .append("[발신자]\n").append(nullToEmpty(request.getFrom())).append("\n\n")
          .append("[수신자]\n").append(String.join(", ", request.getRecipients())).append("\n\n")
          .append("[제목]\n").append(nullToEmpty(request.getSubject())).append("\n\n")
          .append("[헤더]\n").append(nullToEmpty(request.getHeaders())).append("\n\n")
          .append("[본문]\n").append(nullToEmpty(request.getBody())).append("\n");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
