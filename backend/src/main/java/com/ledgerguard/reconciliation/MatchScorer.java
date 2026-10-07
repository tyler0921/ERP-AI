package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.MatchEnums.MatchLevel;
import com.ledgerguard.transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 은행 거래 ↔ 전표 Match Score (0~100). 규칙 기반이며 같은 입력에는 항상 같은 점수를 낸다.
 *
 * <pre>
 * 금액      40  은행 금액 = 전표의 은행 계정 금액이면 40
 * 날짜      20  차이 0일 20, 1일 16, 2일 12, 3일 8, 4~7일 4, 이후 0
 * 거래처    20  {@link TextSimilarity#similarity} × 20
 * 메모      10  {@link TextSimilarity#similarity} × 10
 * 과거 패턴 10  같은 거래처의 과거 확정 매칭과 상대 계정과목이 겹친 횟수: 1회 5, 2회 이상 10
 * </pre>
 *
 * 금액이 다르면 최대 60점이므로 자동 제안 기준(70점)에 도달하지 않는다.
 * 점수 근거는 사람이 검토할 수 있게, 그리고 이후 LLM 설명의 입력으로 쓸 수 있게 detail에 담는다.
 */
@Component
public class MatchScorer {

    public static final int AMOUNT_POINTS = 40;
    public static final int DATE_POINTS = 20;
    public static final int COUNTERPARTY_POINTS = 20;
    public static final int MEMO_POINTS = 10;
    public static final int PATTERN_POINTS = 10;

    /** 후보 전표를 찾는 날짜 범위(±일) */
    public static final int CANDIDATE_WINDOW_DAYS = 30;

    public record BankSide(LocalDate date, Direction direction, BigDecimal amount, String counterparty, String memo) {
    }

    /**
     * @param bankAmount       전표에서 은행 계정과목이 움직인 금액 (출금이면 대변, 입금이면 차변 합계)
     * @param counterparty     연결 거래의 거래처 (없으면 null)
     * @param description      전표 적요
     * @param memo             연결 거래의 메모 (없으면 null)
     * @param counterAccounts  은행 계정과목을 제외한 상대 계정과목 ID
     */
    public record EntrySide(Long entryId, LocalDate date, BigDecimal bankAmount, String counterparty,
                            String description, String memo, Set<Long> counterAccounts) {
    }

    public record Score(int total, MatchLevel level, Map<String, Object> detail) {
    }

    /** 같은 거래처의 과거 확정 매칭 중 상대 계정과목이 겹치는 건수를 돌려준다. */
    @FunctionalInterface
    public interface PatternHistory {
        int overlapCount(String bankCounterparty, Set<Long> counterAccounts);

        PatternHistory NONE = (cp, accounts) -> 0;
    }

    public Score score(BankSide bank, EntrySide entry, PatternHistory history) {
        List<String> reasons = new ArrayList<>();

        boolean amountEqual = bank.amount().compareTo(entry.bankAmount()) == 0;
        int amount = amountEqual ? AMOUNT_POINTS : 0;
        reasons.add(amountEqual ? "금액 일치" : "금액 불일치 (차이 " + bank.amount().subtract(entry.bankAmount()).abs().toPlainString() + ")");

        long dayDiff = Math.abs(ChronoUnit.DAYS.between(bank.date(), entry.date()));
        int date = datePoints(dayDiff);
        reasons.add(dayDiff == 0 ? "거래일 동일" : "거래일 " + dayDiff + "일 차이");

        // 전표에 연결 거래가 없으면 적요에 거래처명이 들어 있는 경우가 많아 둘 중 높은 값을 쓴다.
        double cpSim = Math.max(TextSimilarity.similarity(bank.counterparty(), entry.counterparty()),
                TextSimilarity.similarity(bank.counterparty(), entry.description()));
        int counterparty = (int) Math.round(cpSim * COUNTERPARTY_POINTS);
        if (cpSim >= 0.999) {
            reasons.add("거래처 일치");
        } else if (cpSim > 0) {
            reasons.add("거래처 유사도 " + percent(cpSim));
        }

        String entryText = join(entry.description(), entry.memo());
        double memoSim = TextSimilarity.similarity(bank.memo(), entryText);
        int memo = (int) Math.round(memoSim * MEMO_POINTS);
        if (memoSim > 0) {
            reasons.add("메모 유사도 " + percent(memoSim));
        }

        int patternCount = history.overlapCount(bank.counterparty(), entry.counterAccounts());
        int pattern = patternCount >= 2 ? PATTERN_POINTS : patternCount == 1 ? PATTERN_POINTS / 2 : 0;
        if (patternCount > 0) {
            reasons.add("같은 거래처의 과거 대사 이력 " + patternCount + "건과 계정과목 일치");
        }

        int total = Math.min(100, amount + date + counterparty + memo + pattern);
        MatchLevel level = MatchLevel.of(total);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("total", total);
        detail.put("level", level.name());
        detail.put("amount", amount);
        detail.put("date", date);
        detail.put("counterparty", counterparty);
        detail.put("memo", memo);
        detail.put("pattern", pattern);
        detail.put("bankAmount", bank.amount().toPlainString());
        detail.put("entryAmount", entry.bankAmount().toPlainString());
        detail.put("dayDiff", dayDiff);
        detail.put("counterpartySimilarity", round2(cpSim));
        detail.put("memoSimilarity", round2(memoSim));
        detail.put("patternCount", patternCount);
        detail.put("reasons", reasons);
        return new Score(total, level, detail);
    }

    static int datePoints(long dayDiff) {
        if (dayDiff == 0) {
            return DATE_POINTS;
        }
        if (dayDiff <= 3) {
            return DATE_POINTS - (int) dayDiff * 4;
        }
        return dayDiff <= 7 ? 4 : 0;
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
