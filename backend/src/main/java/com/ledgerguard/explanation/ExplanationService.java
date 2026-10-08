package com.ledgerguard.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.explanation.AiExplanation.Status;
import com.ledgerguard.explanation.AiExplanation.TargetType;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import com.ledgerguard.explanation.ExplanationDtos.ResultCallback;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 설명의 요청·반영·수정. 권한 확인과 Audit는 대상 도메인 서비스(AnomalyService)가 한다.
 * LLM은 설명만 만들고, 결과는 현재 시도(attempt)일 때만 반영한다.
 */
@Service
public class ExplanationService {

    static final int MAX_TEXT = 4000;
    static final int MAX_CHECKPOINTS = 5;
    static final int MAX_CHECKPOINT_LENGTH = 200;

    private final AiExplanationRepository explanations;
    private final LlmJobDispatcher dispatcher;
    private final ObjectMapper canonical;

    public ExplanationService(AiExplanationRepository explanations, LlmJobDispatcher dispatcher,
                              ObjectMapper objectMapper) {
        this.explanations = explanations;
        this.dispatcher = dispatcher;
        // 해시가 Map 순서에 흔들리지 않도록 키를 정렬해 직렬화한다
        this.canonical = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 자동 요청(분석 직후). 근거가 이전과 같으면 다시 만들지 않는다.
     * 사람이 수정한 설명은 덮어쓰지 않고, 근거가 바뀌었으면 stale 표시만 한다.
     *
     * @return 새로 요청했으면 true
     */
    @Transactional
    public boolean request(Long companyId, TargetType type, Long targetId, Map<String, Object> facts) {
        String hash = hash(facts);
        AiExplanation e = explanations.findByTargetTypeAndTargetId(type, targetId).orElse(null);
        if (e != null && e.getFactsHash().equals(hash) && e.getStatus() != Status.FAILED) {
            return false;
        }
        if (e != null && e.getStatus() == Status.EDITED) {
            Map<String, Object> meta = new LinkedHashMap<>(e.getMeta());
            meta.put("stale", true);
            e.setMeta(meta);
            e.setFacts(facts);
            e.setFactsHash(hash);
            return false;
        }
        enqueue(e, companyId, type, targetId, facts, hash);
        return true;
    }

    /** 사람이 요청한 재생성. 수정한 설명도 새 설명으로 바꾼다. */
    @Transactional
    public AiExplanation regenerate(Long companyId, TargetType type, Long targetId, Map<String, Object> facts) {
        AiExplanation e = explanations.findByTargetTypeAndTargetId(type, targetId).orElse(null);
        Map<String, Object> input = facts != null ? facts : e == null ? null : e.getFacts();
        if (input == null) {
            throw new ApiException(ErrorCode.EXPLANATION_STATE_INVALID, "설명할 근거가 없습니다.");
        }
        if (e != null && e.getStatus() == Status.PENDING) {
            throw new ApiException(ErrorCode.EXPLANATION_STATE_INVALID, "설명을 만드는 중입니다. 잠시 후 다시 시도하세요.");
        }
        return enqueue(e, companyId, type, targetId, input, hash(input));
    }

    private AiExplanation enqueue(AiExplanation e, Long companyId, TargetType type, Long targetId,
                                  Map<String, Object> facts, String hash) {
        if (e == null) {
            e = new AiExplanation();
            e.setCompanyId(companyId);
            e.setTargetType(type);
            e.setTargetId(targetId);
            e.setAttempt(1);
        } else {
            e.setAttempt(e.getAttempt() + 1);
        }
        e.setStatus(Status.PENDING);
        e.setFacts(facts);
        e.setFactsHash(hash);
        e.setText(null);
        e.setCheckpoints(new ArrayList<>());
        e.setMeta(new LinkedHashMap<>());
        e.setRequestedAt(Instant.now());
        e.setCompletedAt(null);
        e.setEditedBy(null);
        e.setEditedAt(null);
        explanations.saveAndFlush(e);
        dispatcher.dispatchAfterCommit(e);
        return e;
    }

    /** 사람의 수정. 설명이 아직 없으면(LLM을 쓰지 못한 경우) 근거와 함께 새로 만든다. */
    @Transactional
    public AiExplanation edit(Long userId, Long companyId, TargetType type, Long targetId, String text,
                              List<String> checkpoints, Map<String, Object> factsIfMissing) {
        AiExplanation e = explanations.findByTargetTypeAndTargetId(type, targetId).orElse(null);
        if (e == null) {
            e = new AiExplanation();
            e.setCompanyId(companyId);
            e.setTargetType(type);
            e.setTargetId(targetId);
            e.setFacts(factsIfMissing);
            e.setFactsHash(hash(factsIfMissing));
            e.setRequestedAt(Instant.now());
        }
        Map<String, Object> meta = new LinkedHashMap<>(e.getMeta());
        meta.remove("stale");
        if (e.getStatus() != null && e.getStatus() != Status.EDITED) {
            meta.put("editedFrom", e.getStatus().name());
        }
        e.setStatus(Status.EDITED);
        e.setText(text.trim());
        e.setCheckpoints(cleanCheckpoints(checkpoints));
        e.setMeta(meta);
        e.setEditedBy(userId);
        e.setEditedAt(Instant.now());
        explanations.saveAndFlush(e);
        return e;
    }

    /**
     * LLM worker 콜백. 현재 시도의 PENDING(또는 시간 초과로 FAILED가 된) 설명에만 반영한다.
     *
     * @return 반영 여부
     */
    @Transactional
    public boolean applyResult(Long id, ResultCallback callback) {
        AiExplanation e = explanations.lockById(id).orElse(null);
        if (e == null || e.getAttempt() != callback.attempt()
                || (e.getStatus() != Status.PENDING && e.getStatus() != Status.FAILED)) {
            return false;
        }
        Map<String, Object> meta = new LinkedHashMap<>(callback.meta() == null ? Map.of() : callback.meta());
        e.setCompletedAt(Instant.now());
        if (!callback.success() || callback.text() == null || callback.text().isBlank()) {
            e.setStatus(Status.FAILED);
            meta.put("error", truncate(callback.error() == null ? "설명을 만들지 못했습니다." : callback.error(), 500));
            e.setMeta(meta);
            return true;
        }
        e.setStatus("GENERATED".equals(callback.status()) ? Status.GENERATED : Status.FALLBACK);
        e.setText(truncate(callback.text().trim(), MAX_TEXT));
        e.setCheckpoints(cleanCheckpoints(callback.checkpoints()));
        e.setMeta(meta);
        return true;
    }

    /** 응답 없는 작업을 FAILED로 돌린다. (스케줄러) */
    @Transactional
    public int failTimedOut(Instant requestedBefore) {
        List<AiExplanation> stale = explanations.findTop100ByStatusAndRequestedAtBefore(Status.PENDING, requestedBefore);
        for (AiExplanation e : stale) {
            e.setStatus(Status.FAILED);
            Map<String, Object> meta = new LinkedHashMap<>(e.getMeta());
            meta.put("error", "LLM 응답 시간이 초과되었습니다. 다시 생성하세요.");
            e.setMeta(meta);
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    public Optional<ExplanationView> view(TargetType type, Long targetId) {
        return explanations.findByTargetTypeAndTargetId(type, targetId).map(ExplanationView::from);
    }

    /** Audit Log용 스냅샷 */
    public static Map<String, Object> snapshot(AiExplanation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) {
            return m;
        }
        m.put("status", e.getStatus().name());
        m.put("text", e.getText());
        m.put("checkpoints", e.getCheckpoints());
        m.put("model", e.getMeta().get("model"));
        return m;
    }

    @Transactional(readOnly = true)
    public Optional<AiExplanation> find(TargetType type, Long targetId) {
        return explanations.findByTargetTypeAndTargetId(type, targetId);
    }

    String hash(Map<String, Object> facts) {
        try {
            byte[] json = canonical.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> cleanCheckpoints(List<String> checkpoints) {
        if (checkpoints == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(checkpoints.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> truncate(c.trim(), MAX_CHECKPOINT_LENGTH))
                .limit(MAX_CHECKPOINTS)
                .toList());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
