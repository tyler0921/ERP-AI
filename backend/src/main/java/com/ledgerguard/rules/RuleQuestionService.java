package com.ledgerguard.rules;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.explanation.AiExplanation;
import com.ledgerguard.explanation.AiExplanation.TargetType;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import com.ledgerguard.explanation.ExplanationService;
import com.ledgerguard.rules.RuleChunkRepository.Hit;
import com.ledgerguard.rules.RuleDtos.QuestionSummary;
import com.ledgerguard.rules.RuleDtos.QuestionView;
import com.ledgerguard.rules.RuleQuestion.RetrievalMode;
import com.ledgerguard.rules.RuleQuestion.Status;
import com.ledgerguard.rules.infra.EmbeddingClient;
import com.ledgerguard.rules.infra.EmbeddingClient.EmbeddingUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 규정 질문: 질문 임베딩(ai-service 동기) → pgvector 검색 Top-K(회사·색인 완료 문서만) → 답변 요청(LLM 큐).
 * 임베딩이 실패하면 키워드 검색으로 대체하고, 관련도 기준을 넘는 발췌가 없으면 LLM을 부르지 않는다.
 */
@Service
public class RuleQuestionService {

    public static final int PAGE_SIZE = 30;

    private final RuleQuestionRepository questions;
    private final RuleDocumentRepository documents;
    private final RuleChunkRepository chunks;
    private final EmbeddingClient embeddings;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final ExplanationService explanations;
    private final int dims;
    private final int topK;
    private final double minScore;
    private final double relativeMargin;

    public RuleQuestionService(RuleQuestionRepository questions, RuleDocumentRepository documents,
                               RuleChunkRepository chunks, EmbeddingClient embeddings, CompanyAccessService access,
                               AuditService audit, ExplanationService explanations,
                               @Value("${app.rag.embedding-dims}") int dims, @Value("${app.rag.top-k}") int topK,
                               @Value("${app.rag.min-score}") double minScore,
                               @Value("${app.rag.relative-margin}") double relativeMargin) {
        this.questions = questions;
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.access = access;
        this.audit = audit;
        this.explanations = explanations;
        this.dims = dims;
        this.topK = topK;
        this.minScore = minScore;
        this.relativeMargin = relativeMargin;
    }

    @Transactional
    public QuestionView ask(Long userId, Long companyId, String text) {
        access.requireMember(userId, companyId);
        String question = text.trim();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("topK", topK);
        meta.put("minScore", minScore);
        meta.put("relativeMargin", relativeMargin);

        List<Hit> hits;
        RetrievalMode mode;
        long indexed = documents.countByCompanyIdAndStatus(companyId, RuleDocument.Status.INDEXED);
        if (indexed == 0) {
            hits = List.of();
            mode = RetrievalMode.VECTOR;
            meta.put("reason", "색인된 규정 문서가 없습니다.");
        } else {
            try {
                EmbeddingClient.Embeddings e = embeddings.embed(List.of(question), "query");
                if (e.dims() != dims) {
                    throw new EmbeddingUnavailableException("임베딩 차원이 설정과 다릅니다: " + e.dims(), null);
                }
                meta.put("model", e.model());
                hits = chunks.searchByVector(companyId, RagText.vectorLiteral(e.vectors().get(0), dims), topK);
                mode = RetrievalMode.VECTOR;
                meta.put("bestScore", hits.isEmpty() ? null : round(hits.get(0).score()));
                // 관련도가 기준 미만이거나 1위보다 크게 낮은 발췌는 근거로 쓰지 않는다
                double cutoff = hits.isEmpty() ? minScore : Math.max(minScore, hits.get(0).score() - relativeMargin);
                hits = hits.stream().filter(h -> h.score() >= cutoff).toList();
            } catch (EmbeddingUnavailableException | IllegalArgumentException ex) {
                List<String> terms = RagText.keywordTerms(question);
                meta.put("embeddingError", ex.getMessage());
                meta.put("terms", terms);
                hits = chunks.searchByKeywords(companyId, terms, topK);
                mode = RetrievalMode.KEYWORD;
            }
        }

        RuleQuestion q = new RuleQuestion();
        q.setCompanyId(companyId);
        q.setAskedBy(userId);
        q.setQuestion(question);
        q.setRetrievalMode(mode);
        q.setRetrievalMeta(meta);
        q.setSources(new ArrayList<>(hits.stream().map(Hit::toMap).toList()));
        q.setStatus(hits.isEmpty() ? Status.NO_MATCH : Status.ANSWERED);
        questions.saveAndFlush(q);
        audit.record(companyId, userId, AuditActions.RULE_ASK, AuditActions.TARGET_RULE_QUESTION, q.getId(), null,
                Map.of("question", question, "status", q.getStatus().name(), "retrievalMode", mode.name(),
                        "sourceCount", hits.size()));
        ExplanationView answer = null;
        if (q.getStatus() == Status.ANSWERED) {
            explanations.request(companyId, TargetType.RULE_ANSWER, q.getId(), RagText.facts(question, q.getSources()));
            answer = explanations.view(TargetType.RULE_ANSWER, q.getId()).orElse(null);
        }
        return QuestionView.from(q, answer);
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionSummary> list(Long userId, Long companyId, int page) {
        access.requireMember(userId, companyId);
        return PageResponse.from(questions.findByCompanyIdAndAskedByOrderByIdDesc(companyId, userId,
                PageRequest.of(Math.max(0, page), PAGE_SIZE)).map(QuestionSummary::from));
    }

    @Transactional(readOnly = true)
    public QuestionView get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        RuleQuestion q = own(userId, companyId, id);
        return QuestionView.from(q, explanations.view(TargetType.RULE_ANSWER, q.getId()).orElse(null));
    }

    @Transactional
    public QuestionView editAnswer(Long userId, Long companyId, Long id, EditRequest req) {
        access.requireMember(userId, companyId);
        RuleQuestion q = answered(userId, companyId, id);
        Map<String, Object> before = ExplanationService.snapshot(explanations.find(TargetType.RULE_ANSWER, q.getId()).orElse(null));
        AiExplanation e = explanations.edit(userId, companyId, TargetType.RULE_ANSWER, q.getId(), req.text(),
                req.checkpoints(), RagText.facts(q.getQuestion(), q.getSources()));
        audit.record(companyId, userId, AuditActions.RULE_ANSWER_EDIT, AuditActions.TARGET_RULE_QUESTION, q.getId(),
                before, ExplanationService.snapshot(e));
        return QuestionView.from(q, ExplanationView.from(e));
    }

    @Transactional
    public QuestionView regenerateAnswer(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        RuleQuestion q = answered(userId, companyId, id);
        Map<String, Object> before = ExplanationService.snapshot(explanations.find(TargetType.RULE_ANSWER, q.getId()).orElse(null));
        AiExplanation e = explanations.regenerate(companyId, TargetType.RULE_ANSWER, q.getId(),
                RagText.facts(q.getQuestion(), q.getSources()));
        audit.record(companyId, userId, AuditActions.RULE_ANSWER_REGENERATE, AuditActions.TARGET_RULE_QUESTION,
                q.getId(), before, ExplanationService.snapshot(e));
        return QuestionView.from(q, ExplanationView.from(e));
    }

    private RuleQuestion own(Long userId, Long companyId, Long id) {
        return questions.findByIdAndCompanyIdAndAskedBy(id, companyId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "질문을 찾을 수 없습니다."));
    }

    private RuleQuestion answered(Long userId, Long companyId, Long id) {
        RuleQuestion q = own(userId, companyId, id);
        if (q.getStatus() != Status.ANSWERED) {
            throw new ApiException(ErrorCode.RULE_STATE_INVALID, "관련 규정을 찾지 못한 질문에는 답변이 없습니다.");
        }
        return q;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
