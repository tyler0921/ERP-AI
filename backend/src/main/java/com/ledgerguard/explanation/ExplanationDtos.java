package com.ledgerguard.explanation;

import com.ledgerguard.explanation.AiExplanation.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ExplanationDtos {

    private ExplanationDtos() {
    }

    /**
     * @param facts LLM에 준 입력 그대로
     * @param meta  provider·model·promptVersion·durationMs·validation·rejectedText·stale·error
     */
    public record ExplanationView(Long id, Status status, String text, List<String> checkpoints,
                                  Map<String, Object> meta, Map<String, Object> facts, int attempt,
                                  Instant requestedAt, Instant completedAt, Long editedBy, Instant editedAt) {

        public static ExplanationView from(AiExplanation e) {
            return new ExplanationView(e.getId(), e.getStatus(), e.getText(), e.getCheckpoints(), e.getMeta(),
                    e.getFacts(), e.getAttempt(), e.getRequestedAt(), e.getCompletedAt(), e.getEditedBy(),
                    e.getEditedAt());
        }
    }

    public record EditRequest(@NotBlank @Size(max = 4000) String text,
                              @Size(max = 5) List<@NotBlank @Size(max = 200) String> checkpoints) {
    }

    /**
     * LLM worker 콜백.
     *
     * @param status GENERATED(LLM 설명이 검증 통과) 또는 FALLBACK(템플릿 설명)
     */
    public record ResultCallback(int attempt, boolean success, String status, String text, List<String> checkpoints,
                                 Map<String, Object> meta, String error) {
    }
}
