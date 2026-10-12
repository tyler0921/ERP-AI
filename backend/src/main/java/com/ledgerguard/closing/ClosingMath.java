package com.ledgerguard.closing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 월마감 비교 계산. 순수 클래스.
 *
 * <p>증감률 = 증감액 / |비교값| × 100 (소수 첫째 자리, 반올림). 비교값이 0이면 계산하지 않는다(null).
 * 영업이익처럼 비교값이 음수일 수 있어 분모는 절댓값을 쓴다.
 */
public final class ClosingMath {

    private ClosingMath() {
    }

    /** 비교 한 쌍: 당월과 비교값(전월 또는 전년 동월) */
    public record Change(BigDecimal current, BigDecimal base, BigDecimal diff, BigDecimal pct) {

        public static Change of(BigDecimal current, BigDecimal base) {
            BigDecimal diff = current.subtract(base);
            BigDecimal pct = base.signum() == 0 ? null
                    : diff.multiply(BigDecimal.valueOf(100)).divide(base.abs(), 1, RoundingMode.HALF_UP);
            return new Change(current, base, diff, pct);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("current", current.toPlainString());
            m.put("base", base.toPlainString());
            m.put("diff", diff.toPlainString());
            m.put("pct", pct == null ? null : pct.toPlainString());
            return m;
        }

        /** "21,000,000원 증가 (+700.0%)" — LLM 근거와 화면에 같은 문장을 쓴다 */
        public String describe() {
            String direction = diff.signum() > 0 ? "증가" : diff.signum() < 0 ? "감소" : "변동 없음";
            if (diff.signum() == 0) {
                return direction;
            }
            return won(diff.abs()) + " " + direction + " (" + (pct == null ? "비교값 없음" : signedPct(pct)) + ")";
        }
    }

    public static String signedPct(BigDecimal pct) {
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    /** 1253000.00 → "1,253,000원", 음수는 "-1,253,000원" */
    public static String won(BigDecimal amount) {
        boolean whole = amount.stripTrailingZeros().scale() <= 0;
        return new DecimalFormat(whole ? "#,##0" : "#,##0.00").format(amount) + "원";
    }
}
