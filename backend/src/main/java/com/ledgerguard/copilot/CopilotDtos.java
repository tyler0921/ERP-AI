package com.ledgerguard.copilot;

import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class CopilotDtos {

    private CopilotDtos() {
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    /** 사람이 고친 해석. 형식은 LLM 계획과 같다 ({@link PlanValidator}) */
    public record PlanRequest(@NotNull Map<String, Object> plan) {
    }

    /**
     * LLM worker의 계획 콜백.
     *
     * @param source LLM 또는 RULE(LLM 실패·형식 오류 시 규칙 Planner)
     * @param meta   모델·소요 시간·LLM이 낸 원래 계획·검증 문제
     */
    public record PlanCallback(int attempt, boolean success, Map<String, Object> plan, String source,
                               Map<String, Object> meta, String error) {
    }

    public record QuestionSummary(Long id, String question, String status, String tool, String period,
                                  Instant createdAt) {

        static QuestionSummary from(CopilotQuestion q) {
            Map<String, Object> r = q.getResult();
            return new QuestionSummary(q.getId(), q.getQuestion(), q.getStatus().name(),
                    r == null ? null : (String) r.get("title"), r == null ? null : (String) r.get("period"),
                    q.getCreatedAt());
        }
    }

    public record QuestionView(Long id, String question, String status, int attempt, Map<String, Object> plan,
                               String planSource, Map<String, Object> planMeta, Map<String, Object> result,
                               String error, Instant createdAt, Instant plannedAt, ExplanationView answer) {

        static QuestionView from(CopilotQuestion q, ExplanationView answer) {
            return new QuestionView(q.getId(), q.getQuestion(), q.getStatus().name(), q.getAttempt(), q.getPlan(),
                    q.getPlanSource() == null ? null : q.getPlanSource().name(), q.getPlanMeta(), q.getResult(),
                    q.getError(), q.getCreatedAt(), q.getPlannedAt(), answer);
        }
    }
}
