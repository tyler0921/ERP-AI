package com.ledgerguard.copilot;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.copilot.CopilotDtos.PlanCallback;
import com.ledgerguard.copilot.CopilotDtos.QuestionSummary;
import com.ledgerguard.copilot.CopilotDtos.QuestionView;
import com.ledgerguard.copilot.CopilotQuestion.PlanSource;
import com.ledgerguard.copilot.CopilotQuestion.Status;
import com.ledgerguard.explanation.AiExplanation;
import com.ledgerguard.explanation.AiExplanation.TargetType;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import com.ledgerguard.explanation.ExplanationService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI Copilot: 질문 → (LLM worker) 계획 → 검증 → Query Tool 실행 → 답변 요청.
 * LLM은 계획(Tool·파라미터 선택)과 답변 문장만 만들고, 조회·계산은 {@link CopilotQueryService}가 한다.
 */
@Service
public class CopilotService {

    /** 한 사용자가 동시에 해석 중일 수 있는 질문 수 */
    public static final int MAX_PLANNING = 3;
    public static final int PAGE_SIZE = 30;

    private final CopilotQuestionRepository questions;
    private final CopilotQueryService query;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final ExplanationService explanations;
    private final PlanJobDispatcher dispatcher;

    public CopilotService(CopilotQuestionRepository questions, CopilotQueryService query, CompanyAccessService access,
                          AuditService audit, ExplanationService explanations, PlanJobDispatcher dispatcher) {
        this.questions = questions;
        this.query = query;
        this.access = access;
        this.audit = audit;
        this.explanations = explanations;
        this.dispatcher = dispatcher;
    }

    // ================================================================ 질문·조회

    @Transactional
    public QuestionView ask(Long userId, Long companyId, String text) {
        access.requireMember(userId, companyId);
        if (questions.countByCompanyIdAndAskedByAndStatus(companyId, userId, Status.PLANNING) >= MAX_PLANNING) {
            throw new ApiException(ErrorCode.COPILOT_BUSY);
        }
        CopilotQuestion q = new CopilotQuestion();
        q.setCompanyId(companyId);
        q.setAskedBy(userId);
        q.setQuestion(text.trim());
        q.setStatus(Status.PLANNING);
        questions.saveAndFlush(q);
        audit.record(companyId, userId, AuditActions.COPILOT_ASK, AuditActions.TARGET_COPILOT_QUESTION, q.getId(),
                null, Map.of("question", q.getQuestion()));
        dispatcher.dispatchAfterCommit(q, planContext(companyId, q.getQuestion()));
        return QuestionView.from(q, null);
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
        return view(own(userId, companyId, id));
    }

    /** 해석 실패·큐 장애 후 다시 시도 (새 시도 번호로 계획 작업을 다시 넣는다) */
    @Transactional
    public QuestionView retry(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        CopilotQuestion q = own(userId, companyId, id);
        if (q.getStatus() != Status.FAILED) {
            throw new ApiException(ErrorCode.COPILOT_STATE_INVALID, "해석에 실패한 질문만 다시 시도할 수 있습니다.");
        }
        if (questions.countByCompanyIdAndAskedByAndStatus(companyId, userId, Status.PLANNING) >= MAX_PLANNING) {
            throw new ApiException(ErrorCode.COPILOT_BUSY);
        }
        q.setAttempt(q.getAttempt() + 1);
        q.setStatus(Status.PLANNING);
        q.setError(null);
        questions.saveAndFlush(q);
        dispatcher.dispatchAfterCommit(q, planContext(companyId, q.getQuestion()));
        return view(q);
    }

    /** 사람이 고친 해석으로 다시 조회하고 답변을 새로 만든다 (Audit) */
    @Transactional
    public QuestionView editPlan(Long userId, Long companyId, Long id, Map<String, Object> rawPlan) {
        access.requireMember(userId, companyId);
        CopilotQuestion q = own(userId, companyId, id);
        if (q.getStatus() == Status.PLANNING) {
            throw new ApiException(ErrorCode.COPILOT_STATE_INVALID, "질문을 해석하는 중입니다. 잠시 후 다시 시도하세요.");
        }
        PlanValidator.Result validated = PlanValidator.validate(rawPlan);
        if (!validated.valid()) {
            throw new ApiException(ErrorCode.COPILOT_PLAN_INVALID, ErrorCode.COPILOT_PLAN_INVALID.defaultMessage(),
                    validated.issues().stream().map(i -> new ApiError.Detail("plan", i)).toList());
        }
        ToolResult result;
        try {
            result = query.execute(userId, companyId, validated.plan());
        } catch (PlanException e) {
            throw new ApiException(ErrorCode.COPILOT_PLAN_INVALID, e.getMessage());
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("plan", q.getPlan());
        before.put("planSource", q.getPlanSource() == null ? null : q.getPlanSource().name());
        before.put("status", q.getStatus().name());
        applyResult(q, validated.plan(), PlanSource.USER, result, true);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("plan", q.getPlan());
        after.put("planSource", PlanSource.USER.name());
        after.put("status", q.getStatus().name());
        audit.record(companyId, userId, AuditActions.COPILOT_PLAN_EDIT, AuditActions.TARGET_COPILOT_QUESTION, q.getId(),
                before, after);
        return view(q);
    }

    // ================================================================ 답변

    @Transactional
    public QuestionView editAnswer(Long userId, Long companyId, Long id, EditRequest req) {
        access.requireMember(userId, companyId);
        CopilotQuestion q = answered(userId, companyId, id);
        Map<String, Object> before = ExplanationService.snapshot(
                explanations.find(TargetType.COPILOT_ANSWER, q.getId()).orElse(null));
        AiExplanation e = explanations.edit(userId, companyId, TargetType.COPILOT_ANSWER, q.getId(), req.text(),
                req.checkpoints(), CopilotFacts.build(q.getQuestion(), q.getResult()));
        audit.record(companyId, userId, AuditActions.COPILOT_ANSWER_EDIT, AuditActions.TARGET_COPILOT_QUESTION,
                q.getId(), before, ExplanationService.snapshot(e));
        return QuestionView.from(q, ExplanationView.from(e));
    }

    @Transactional
    public QuestionView regenerateAnswer(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        CopilotQuestion q = answered(userId, companyId, id);
        Map<String, Object> before = ExplanationService.snapshot(
                explanations.find(TargetType.COPILOT_ANSWER, q.getId()).orElse(null));
        AiExplanation e = explanations.regenerate(companyId, TargetType.COPILOT_ANSWER, q.getId(),
                CopilotFacts.build(q.getQuestion(), q.getResult()));
        audit.record(companyId, userId, AuditActions.COPILOT_ANSWER_REGENERATE, AuditActions.TARGET_COPILOT_QUESTION,
                q.getId(), before, ExplanationService.snapshot(e));
        return QuestionView.from(q, ExplanationView.from(e));
    }

    // ================================================================ LLM worker 콜백

    /**
     * 계획 콜백. 현재 시도의 PLANNING 질문에만 반영한다. 계획이 형식에 맞지 않거나 실행할 수 없으면 FAILED로 두고
     * (계획은 남겨) 사람이 해석을 고쳐 다시 조회할 수 있게 한다.
     *
     * @return 반영 여부
     */
    @Transactional
    public boolean applyPlan(Long id, PlanCallback callback) {
        CopilotQuestion q = questions.lockById(id).orElse(null);
        if (q == null || q.getAttempt() != callback.attempt() || q.getStatus() != Status.PLANNING) {
            return false;
        }
        Map<String, Object> meta = new LinkedHashMap<>(callback.meta() == null ? Map.of() : callback.meta());
        q.setPlannedAt(Instant.now());
        q.setPlanMeta(meta);
        if (!callback.success()) {
            fail(q, callback.error() == null ? "질문을 해석하지 못했습니다." : truncate(callback.error()));
            return true;
        }
        PlanValidator.Result validated = PlanValidator.validate(callback.plan());
        if (!validated.valid()) {
            meta.put("rejectedPlan", callback.plan());
            meta.put("planIssues", validated.issues());
            q.setPlanMeta(meta);
            fail(q, "질문 해석 결과가 올바르지 않습니다: " + String.join(" / ", validated.issues()));
            return true;
        }
        PlanSource source = "RULE".equals(callback.source()) ? PlanSource.RULE : PlanSource.LLM;
        try {
            applyResult(q, validated.plan(), source, query.execute(q.getAskedBy(), q.getCompanyId(), validated.plan()), false);
        } catch (PlanException e) {
            q.setPlan(validated.plan().toMap());
            q.setPlanSource(source);
            fail(q, e.getMessage());
        } catch (ApiException e) {
            // 질문 이후 회사 멤버에서 빠진 경우 등
            q.setPlan(validated.plan().toMap());
            q.setPlanSource(source);
            fail(q, e.getMessage());
        }
        return true;
    }

    /** 응답 없는 계획 작업을 FAILED로 돌린다. (스케줄러) */
    @Transactional
    public int failTimedOut(Instant updatedBefore) {
        List<CopilotQuestion> stale = questions.findTop100ByStatusAndUpdatedAtBefore(Status.PLANNING, updatedBefore);
        for (CopilotQuestion q : stale) {
            fail(q, "질문 해석(LLM) 응답 시간이 초과되었습니다. 다시 시도하세요.");
        }
        return stale.size();
    }

    // ================================================================ 내부

    private void applyResult(CopilotQuestion q, CopilotPlan plan, PlanSource source, ToolResult result, boolean byUser) {
        q.setPlan(plan.toMap());
        q.setPlanSource(source);
        q.setResult(result.toMap());
        q.setError(null);
        if (plan.tool() == CopilotPlan.Tool.UNSUPPORTED) {
            q.setStatus(Status.UNSUPPORTED);
            questions.saveAndFlush(q);
            return;
        }
        q.setStatus(Status.ANSWERED);
        questions.saveAndFlush(q);
        Map<String, Object> facts = CopilotFacts.build(q.getQuestion(), q.getResult());
        AiExplanation existing = explanations.find(TargetType.COPILOT_ANSWER, q.getId()).orElse(null);
        if (existing == null) {
            explanations.request(q.getCompanyId(), TargetType.COPILOT_ANSWER, q.getId(), facts);
        } else if (byUser) {
            // 해석을 고쳤으면 근거가 바뀐 것이므로, 수정했거나 생성 중인 답변도 새 결과로 다시 만든다
            explanations.replace(q.getCompanyId(), TargetType.COPILOT_ANSWER, q.getId(), facts);
        } else {
            explanations.request(q.getCompanyId(), TargetType.COPILOT_ANSWER, q.getId(), facts);
        }
    }

    /** Planner에 주는 맥락: 질문, 오늘 날짜(회사 시간대), 수익·비용 계정과목 이름 */
    private Map<String, Object> planContext(Long companyId, String question) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("question", question);
        context.put("today", query.today().toString());
        context.put("accounts", query.incomeAccounts(companyId).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.code());
            m.put("name", a.name());
            m.put("type", a.type());
            return m;
        }).toList());
        return context;
    }

    private CopilotQuestion own(Long userId, Long companyId, Long id) {
        return questions.findByIdAndCompanyIdAndAskedBy(id, companyId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "질문을 찾을 수 없습니다."));
    }

    private CopilotQuestion answered(Long userId, Long companyId, Long id) {
        CopilotQuestion q = own(userId, companyId, id);
        if (q.getStatus() != Status.ANSWERED) {
            throw new ApiException(ErrorCode.COPILOT_STATE_INVALID, "조회가 끝난 질문에만 답변이 있습니다.");
        }
        return q;
    }

    private QuestionView view(CopilotQuestion q) {
        ExplanationView answer = q.getStatus() == Status.ANSWERED
                ? explanations.view(TargetType.COPILOT_ANSWER, q.getId()).orElse(null) : null;
        return QuestionView.from(q, answer);
    }

    private void fail(CopilotQuestion q, String error) {
        q.setStatus(Status.FAILED);
        q.setError(error);
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
