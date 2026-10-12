package com.ledgerguard.rules;

import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuleDtos {

    private RuleDtos() {
    }

    public record DocumentView(Long id, String title, String category, String status, String fileName,
                               String contentType, long fileSize, int chunkCount, int charCount, String embeddingModel,
                               String error, int attempt, Long uploadedBy, Instant createdAt, Instant indexedAt,
                               Long archivedBy, Instant archivedAt, String archiveReason) {

        static DocumentView from(RuleDocument d) {
            return new DocumentView(d.getId(), d.getTitle(), d.getCategory().name(), d.getStatus().name(),
                    d.getFileName(), d.getContentType(), d.getFileSize(), d.getChunkCount(), d.getCharCount(),
                    d.getEmbeddingModel(), d.getError(), d.getAttempt(), d.getUploadedBy(), d.getCreatedAt(),
                    d.getIndexedAt(), d.getArchivedBy(), d.getArchivedAt(), d.getArchiveReason());
        }
    }

    public record ChunkView(Long id, String section, Integer page, String content) {
    }

    public record ArchiveRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * ai-service RAG worker의 색인 콜백.
     *
     * @param chunks 청크와 임베딩 (차원은 app.rag.embedding-dims와 같아야 한다)
     */
    public record IndexCallback(int attempt, boolean success, String model, Integer dims, Integer charCount,
                                List<ChunkPayload> chunks, String error) {
    }

    public record ChunkPayload(Integer index, String section, Integer page, String content, List<Double> embedding) {
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    public record QuestionSummary(Long id, String question, String status, int sourceCount, Instant createdAt) {

        static QuestionSummary from(RuleQuestion q) {
            return new QuestionSummary(q.getId(), q.getQuestion(), q.getStatus().name(), q.getSources().size(),
                    q.getCreatedAt());
        }
    }

    /** @param sources 질문 시점의 검색 결과 스냅샷 (번호 = 순서 + 1, 답변의 [번호] 인용과 같다) */
    public record QuestionView(Long id, String question, String status, String retrievalMode,
                               Map<String, Object> retrievalMeta, List<Map<String, Object>> sources, Instant createdAt,
                               ExplanationView answer) {

        static QuestionView from(RuleQuestion q, ExplanationView answer) {
            return new QuestionView(q.getId(), q.getQuestion(), q.getStatus().name(), q.getRetrievalMode().name(),
                    q.getRetrievalMeta(), q.getSources(), q.getCreatedAt(), answer);
        }
    }
}
