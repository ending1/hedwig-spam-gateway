package com.hs.mail.gateway.spamfilter;

import java.util.Locale;
import java.util.regex.Pattern;

/** RAG 인덱스 구축/질의에서 공통으로 쓰는 텍스트 전처리. 구축 도구와 게이트웨이가 같은 규칙을 써야 유사도가 일관된다. */
public final class RagText {

    private static final Pattern STYLE_SCRIPT = Pattern.compile("(?is)<(style|script|head)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&[a-zA-Z#0-9]+;");
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern LONG_NUM = Pattern.compile("\\d{6,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /**
     * 화면에 보이지 않는 서식 문자. 마케팅 메일이 미리보기 텍스트 뒤를 이 문자들로 수백 개 채우는데(프리헤더
     * 패딩), 그대로 두면 서로 무관한 메일끼리 이 반복 패턴 때문에 높은 유사도를 받는다.
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180E\\u200B-\\u200F\\u202A-\\u202E"
                    + "\\u2060-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0]");

    private RagText() {
    }

    /** HTML 마크업/스타일/스크립트/엔티티를 제거한 가시 텍스트. */
    public static String visibleText(String html) {
        if (html == null) {
            return "";
        }
        String t = STYLE_SCRIPT.matcher(INVISIBLE.matcher(html).replaceAll("")).replaceAll(" ");
        t = COMMENT.matcher(t).replaceAll(" ");
        t = TAG.matcher(t).replaceAll(" ");
        t = ENTITY.matcher(t).replaceAll(" ");
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    /** 사례를 프롬프트/파일에 남기기 전 개인정보성 값(이메일, 긴 숫자열)을 가린다. */
    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        String t = EMAIL.matcher(text).replaceAll("<email>");
        return LONG_NUM.matcher(t).replaceAll("<num>");
    }

    /** 유사도 계산용 정규화: 소문자, URL/이메일 토큰화, 숫자 일반화, 공백 정리. */
    public static String normalizeForIndex(String text) {
        if (text == null) {
            return "";
        }
        String t = INVISIBLE.matcher(text).replaceAll("");
        t = URL.matcher(t).replaceAll(" url ");
        t = EMAIL.matcher(t).replaceAll(" email ");
        t = t.toLowerCase(Locale.ROOT).replaceAll("\\d", "0");
        return SPACES.matcher(t).replaceAll(" ").trim();
    }
}
