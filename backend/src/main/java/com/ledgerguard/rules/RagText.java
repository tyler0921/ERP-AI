package com.ledgerguard.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RAG 보조 계산. 순수 클래스.
 * <ul>
 *   <li>pgvector 리터럴("[0.1,0.2,…]") 만들기와 차원·값 검증</li>
 *   <li>키워드 검색어 뽑기 (임베딩 서비스 장애 시 대체 검색)</li>
 *   <li>답변 LLM에 줄 근거 데이터(번호 붙은 규정 발췌)</li>
 * </ul>
 */
public final class RagText {

    public static final int MAX_TERMS = 8;

    private RagText() {
    }

    /** @throws IllegalArgumentException 차원이 다르거나 NaN·무한대가 있을 때 */
    public static String vectorLiteral(List<? extends Number> values, int dims) {
        if (values == null || values.size() != dims) {
            throw new IllegalArgumentException("임베딩 차원이 " + dims + "이 아닙니다: " + (values == null ? 0 : values.size()));
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            Number n = values.get(i);
            double d = n == null ? Double.NaN : n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("임베딩에 올바르지 않은 값이 있습니다.");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append((float) d);
        }
        return sb.append(']').toString();
    }

    // 조사·어미 (긴 것부터 떼어 낸다)
    private static final String[] SUFFIXES = {"으로는", "에서는", "에서", "으로", "에게", "까지", "부터", "하고", "이나", "이랑",
            "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "만", "와", "과", "랑"};
    private static final Set<String> STOPWORDS = Set.of("어떻게", "무엇", "뭐야", "뭔가요", "되나요", "되나", "있나요", "있어",
            "가능", "가능해", "가능한가요", "해야", "하나요", "해야하나요", "알려줘", "알려주세요", "어떤", "경우", "얼마", "얼마나",
            "규정", "회사", "우리", "관련", "대해", "대한", "무슨", "언제", "어디", "누가", "그리고", "또는", "하면");

    /** 질문 → 검색어 (2글자 이상, 조사 제거, 불용어 제외, 중복 제거, 최대 8개) */
    public static List<String> keywordTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String t = raw;
            for (String s : SUFFIXES) {
                if (t.length() > s.length() + 1 && t.endsWith(s)) {
                    t = t.substring(0, t.length() - s.length());
                    break;
                }
            }
            if (t.length() >= 2 && !STOPWORDS.contains(t) && !STOPWORDS.contains(raw)) {
                terms.add(t);
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return new ArrayList<>(terms);
    }

    /** "경비 처리 규정 › 제12조(접대비) (p.3)" */
    public static String sourceLabel(String title, String section, Integer page) {
        StringBuilder sb = new StringBuilder(title);
        if (section != null && !section.isBlank()) {
            sb.append(" › ").append(section);
        }
        if (page != null) {
            sb.append(" (p.").append(page).append(')');
        }
        return sb.toString();
    }

    /** 답변 LLM에 줄 근거: 질문과 번호 붙은 발췌. 번호는 sources 순서(1부터)이며 답변의 [번호] 인용과 맞춘다 */
    public static Map<String, Object> facts(String question, List<Map<String, Object>> sources) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("질문", question);
        List<Map<String, Object>> excerpts = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> s = sources.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("번호", i + 1);
            e.put("출처", sourceLabel(String.valueOf(s.get("title")), (String) s.get("section"),
                    s.get("page") instanceof Number n ? n.intValue() : null));
            e.put("본문", s.get("content"));
            excerpts.add(e);
        }
        facts.put("규정 발췌", excerpts);
        return facts;
    }
}
