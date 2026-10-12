package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingMath.Change;
import com.ledgerguard.closing.ClosingStatement.Kind;
import com.ledgerguard.closing.ClosingStatement.Line;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주요 변동 탐지 (규칙 기반, 순수 클래스).
 *
 * <pre>
 * INCREASE / DECREASE  |증감액| ≥ 1,000,000원 그리고 |증감률| ≥ 30%
 * NEW                  비교값 0, 당월 ≥ 1,000,000원
 * GONE                 당월 0, 비교값 ≥ 1,000,000원
 * </pre>
 * 비교 기준은 전월과 전년 동월이다. 증감액이 큰 순으로 정렬한다.
 */
public final class VarianceDetector {

    public static final BigDecimal MIN_AMOUNT = new BigDecimal("1000000");
    public static final BigDecimal MIN_PCT = new BigDecimal("30");

    private VarianceDetector() {
    }

    public enum Basis {
        PREV_MONTH("전월"),
        PREV_YEAR("전년 동월");

        public final String label;

        Basis(String label) {
            this.label = label;
        }
    }

    public record Variance(Kind kind, String key, String label, Basis basis, String code, Change change,
                           String message) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name());
            m.put("key", key);
            m.put("label", label);
            m.put("basis", basis.name());
            m.put("code", code);
            m.putAll(change.toMap());
            m.put("message", message);
            return m;
        }
    }

    public static List<Variance> detect(List<Line> lines) {
        List<Variance> result = new ArrayList<>();
        for (Line line : lines) {
            check(line, Basis.PREV_MONTH, line.vsPrevMonth(), result);
            check(line, Basis.PREV_YEAR, line.vsPrevYear(), result);
        }
        result.sort(Comparator.comparing((Variance v) -> v.change().diff().abs()).reversed()
                .thenComparing(v -> v.basis().ordinal())
                .thenComparing(Variance::label));
        return result;
    }

    private static void check(Line line, Basis basis, Change c, List<Variance> out) {
        String code = null;
        if (c.base().signum() == 0) {
            if (c.current().abs().compareTo(MIN_AMOUNT) >= 0) {
                code = "NEW";
            }
        } else if (c.current().signum() == 0) {
            if (c.base().abs().compareTo(MIN_AMOUNT) >= 0) {
                code = "GONE";
            }
        } else if (c.diff().abs().compareTo(MIN_AMOUNT) >= 0 && c.pct() != null && c.pct().abs().compareTo(MIN_PCT) >= 0) {
            code = c.diff().signum() > 0 ? "INCREASE" : "DECREASE";
        }
        if (code == null) {
            return;
        }
        String message = switch (code) {
            case "NEW" -> String.format("%s: %s에는 없다가 당월 %s 발생", line.label(), basis.label, ClosingMath.won(c.current()));
            case "GONE" -> String.format("%s: %s %s → 당월 없음", line.label(), basis.label, ClosingMath.won(c.base()));
            default -> String.format("%s: %s %s → 당월 %s, %s", line.label(), basis.label, ClosingMath.won(c.base()),
                    ClosingMath.won(c.current()), c.describe());
        };
        out.add(new Variance(line.kind(), line.key(), line.label(), basis, code, c, message));
    }
}
