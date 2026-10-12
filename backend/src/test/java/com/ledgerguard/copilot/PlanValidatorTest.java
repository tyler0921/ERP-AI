package com.ledgerguard.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.copilot.CopilotPlan.AnomalyStatusFilter;
import com.ledgerguard.copilot.CopilotPlan.Compare;
import com.ledgerguard.copilot.CopilotPlan.Level;
import com.ledgerguard.copilot.CopilotPlan.Order;
import com.ledgerguard.copilot.CopilotPlan.PeriodType;
import com.ledgerguard.copilot.CopilotPlan.Tool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {

    @Test
    void 기본값을_채운다() {
        PlanValidator.Result r = PlanValidator.validate(Map.of("tool", "vendor_ranking"));
        assertThat(r.valid()).isTrue();
        CopilotPlan p = r.plan();
        assertThat(p.tool()).isEqualTo(Tool.VENDOR_RANKING);
        assertThat(p.period().type()).isEqualTo(PeriodType.LAST_MONTH); // 기간이 없으면 지난 달
        assertThat(p.compare()).isEqualTo(Compare.PREV_PERIOD);
        assertThat(p.order()).isEqualTo(Order.AMOUNT);
        assertThat(p.limit()).isEqualTo(PlanValidator.DEFAULT_LIMIT);

        CopilotPlan a = PlanValidator.validate(Map.of("tool", "ANOMALY_LIST")).plan();
        assertThat(a.minLevel()).isEqualTo(Level.LOW);
        assertThat(a.status()).isEqualTo(AnomalyStatusFilter.OPEN);
        assertThat(a.limit()).isEqualTo(PlanValidator.DEFAULT_ANOMALY_LIMIT);
    }

    @Test
    void 허용_목록_밖의_값과_범위를_거부한다() {
        assertThat(PlanValidator.validate(Map.of("tool", "RUN_SQL", "sql", "DELETE FROM journal_entries")).issues())
                .singleElement().asString().contains("tool");
        assertThat(PlanValidator.validate(Map.of("tool", "PROFIT_SUMMARY", "period", Map.of("type", "MONTH", "month", 13)))
                .issues()).anyMatch(i -> i.contains("period.month"));
        assertThat(PlanValidator.validate(Map.of("tool", "PROFIT_SUMMARY", "period", Map.of("type", "MONTH"))).issues())
                .contains("MONTH 기간에는 month가 필요합니다.");
        assertThat(PlanValidator.validate(Map.of("tool", "PROFIT_SUMMARY", "period", Map.of("type", "QUARTER", "quarter", 1.5)))
                .issues()).anyMatch(i -> i.contains("period.quarter"));
        assertThat(PlanValidator.validate(Map.of("tool", "VENDOR_RANKING", "limit", 500)).issues())
                .anyMatch(i -> i.contains("limit"));
        assertThat(PlanValidator.validate(Map.of("tool", "ACCOUNT_SUMMARY")).issues())
                .anyMatch(i -> i.contains("account"));
        assertThat(PlanValidator.validate(Map.of("tool", "PROFIT_SUMMARY", "period", "이번 달")).issues())
                .contains("period 형식이 올바르지 않습니다.");
        assertThat(PlanValidator.validate(null).valid()).isFalse();
    }

    @Test
    void 모르는_필드와_도구에_맞지_않는_필드는_버린다() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("tool", "PROFIT_SUMMARY");
        raw.put("period", Map.of("type", "QUARTER", "year", "2026", "quarter", 3));
        raw.put("account", "광고선전비");
        raw.put("companyId", 999);
        raw.put("limit", 3);
        CopilotPlan p = PlanValidator.validate(raw).plan();
        assertThat(p.account()).isNull();
        assertThat(p.period().year()).isEqualTo(2026);
        assertThat(p.toMap()).containsOnlyKeys("tool", "period", "compare");
        assertThat(p.toMap().get("period")).isEqualTo(Map.of("type", "QUARTER", "year", 2026, "quarter", 3));
    }

    @Test
    void 지원하지_않는_질문은_이유만_남긴다() {
        CopilotPlan p = PlanValidator.validate(Map.of("tool", "UNSUPPORTED", "reason", "부서 데이터가 없습니다.")).plan();
        assertThat(p.toMap()).isEqualTo(Map.of("tool", "UNSUPPORTED", "reason", "부서 데이터가 없습니다."));
        assertThat(PlanValidator.validate(Map.of("tool", "UNSUPPORTED")).plan().reason()).isEqualTo("지원하지 않는 질문입니다.");
        assertThat(PlanValidator.validate(Map.of("tool", "UNSUPPORTED", "reason", "가".repeat(500))).plan().reason())
                .hasSize(200);
    }

    @Test
    void 계획_JSON은_검증을_다시_통과한다() {
        for (Map<String, Object> raw : List.<Map<String, Object>>of(
                Map.of("tool", "ACCOUNT_SUMMARY", "account", "광고비", "compare", "PREV_YEAR",
                        "period", Map.of("type", "MONTH", "month", 9)),
                Map.of("tool", "ANOMALY_LIST", "minLevel", "HIGH", "status", "ALL", "limit", 3),
                Map.of("tool", "VENDOR_RANKING", "order", "INCREASE", "account", "광고선전비"),
                Map.of("tool", "CLOSING_STATUS", "period", Map.of("type", "THIS_MONTH")))) {
            CopilotPlan p = PlanValidator.validate(raw).plan();
            assertThat(PlanValidator.validate(p.toMap()).plan()).isEqualTo(p);
        }
    }
}
