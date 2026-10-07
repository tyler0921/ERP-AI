package com.ledgerguard.reconciliation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 거래처명·메모 비교용 문자열 유사도. 형태소 분석 없이 한국어에도 동작하는 문자 bigram 방식. */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /** 법인 표기, 공백, 기호를 제거하고 소문자로 바꾼다. "(주)가상 물산" → "가상물산" */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("주식회사", "")
                .replace("유한회사", "")
                .replace("(주)", "")
                .replace("㈜", "")
                .replace("(유)", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * 유사도 0~1. 한쪽이 다른 쪽을 포함하면(2글자 이상) 1.0, 아니면 bigram Dice 계수.
     * 은행 적요에는 거래처명·메모가 잘려 나오거나 다른 문구가 붙는 경우가 많아 포함 관계를 우선한다.
     */
    public static double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        if (shorter.length() >= 2 && longer.contains(shorter)) {
            return 1.0;
        }
        return dice(x, y);
    }

    /** 문자 bigram Dice 계수 0~1 (정규화 후) */
    public static double dice(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.length() < 2 || y.length() < 2) {
            return x.equals(y) ? 1.0 : 0;
        }
        Map<String, Integer> bx = bigrams(x);
        Map<String, Integer> by = bigrams(y);
        int overlap = 0;
        for (Map.Entry<String, Integer> e : bx.entrySet()) {
            overlap += Math.min(e.getValue(), by.getOrDefault(e.getKey(), 0));
        }
        return 2.0 * overlap / ((x.length() - 1) + (y.length() - 1));
    }

    private static Map<String, Integer> bigrams(String s) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < s.length() - 1; i++) {
            map.merge(s.substring(i, i + 2), 1, Integer::sum);
        }
        return map;
    }
}
