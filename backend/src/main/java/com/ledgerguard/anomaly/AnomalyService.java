package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyDtos.ResultListItem;
import com.ledgerguard.anomaly.AnomalyDtos.ResultResponse;
import com.ledgerguard.anomaly.AnomalyDtos.ReviewRequest;
import com.ledgerguard.anomaly.AnomalyDtos.ScanRequest;
import com.ledgerguard.anomaly.AnomalyDtos.ScanResponse;
import com.ledgerguard.anomaly.AnomalyDtos.SettingsRequest;
import com.ledgerguard.anomaly.AnomalyDtos.SettingsResponse;
import com.ledgerguard.anomaly.AnomalyDtos.SummaryResponse;
import com.ledgerguard.anomaly.AnomalyEnums.MlStatus;
import com.ledgerguard.anomaly.AnomalyEnums.ResultStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.anomaly.AnomalyFeatureExtractor.Features;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import com.ledgerguard.anomaly.AnomalyRuleEngine.Thresholds;
import com.ledgerguard.anomaly.RiskScorer.Risk;
import com.ledgerguard.anomaly.infra.AnomalyModelClient;
import com.ledgerguard.anomaly.infra.AnomalyModelClient.ModelResult;
import com.ledgerguard.anomaly.infra.AnomalyModelClient.ModelUnavailableException;
import com.ledgerguard.anomaly.infra.AnomalyModelClient.Sample;
import com.ledgerguard.anomaly.infra.AnomalyModelClient.ScoredSample;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.company.CompanyRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이상거래 분석(규칙 + ML → Risk Score)과 사람의 검토.
 * 판단은 규칙 엔진과 알고리즘이 하고, 최종 판정은 사람이 한다. 재분석은 사람의 판정을 덮어쓰지 않는다.
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    public record Filter(ResultStatus status, RiskLevel level, SourceType sourceType, LocalDate from, LocalDate to) {
    }

    public static final int MAX_PERIOD_DAYS = 366;
    /** 대상 기간 끝 이후의 중복 결제도 보도록 며칠 더 읽는다 */
    static final int LOOKAHEAD_DAYS = AnomalyRuleEngine.DUPLICATE_WINDOW_DAYS + 4;

    private final AnomalyResultRepository results;
    private final AnomalyScanRepository scans;
    private final AnomalySettingsRepository settingsRepository;
    private final CompanyRepository companies;
    private final AnomalyEventLoader loader;
    private final AnomalyModelClient model;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final int mlMinSamples;

    public AnomalyService(AnomalyResultRepository results, AnomalyScanRepository scans,
                          AnomalySettingsRepository settingsRepository, CompanyRepository companies,
                          AnomalyEventLoader loader, AnomalyModelClient model, CompanyAccessService access,
                          AuditService audit, @Value("${app.anomaly.ml-min-samples}") int mlMinSamples) {
        this.results = results;
        this.scans = scans;
        this.settingsRepository = settingsRepository;
        this.companies = companies;
        this.loader = loader;
        this.model = model;
        this.access = access;
        this.audit = audit;
        this.mlMinSamples = mlMinSamples;
    }

    // ================================================================ 설정

    @Transactional(readOnly = true)
    public SettingsResponse getSettings(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        return settingsRepository.findById(companyId).map(s -> SettingsResponse.from(s, true))
                .orElseGet(() -> SettingsResponse.from(AnomalySettings.defaults(companyId), false));
    }

    @Transactional
    public SettingsResponse updateSettings(Long userId, Long companyId, SettingsRequest req) {
        access.requireAdmin(userId, companyId);
        AnomalySettings s = settingsRepository.findById(companyId).orElse(null);
        SettingsResponse before = s == null ? null : SettingsResponse.from(s, true);
        if (s == null) {
            s = AnomalySettings.defaults(companyId);
        }
        s.setApprovalLimit(scale(req.approvalLimit()));
        s.setNearLimitRatio(req.nearLimitRatio().setScale(2, RoundingMode.UNNECESSARY));
        s.setEvidenceMinAmount(scale(req.evidenceMinAmount()));
        s.setNewVendorMinAmount(scale(req.newVendorMinAmount()));
        s.setSpikeMultiplier(req.spikeMultiplier().setScale(2, RoundingMode.UNNECESSARY));
        s.setNightStartHour(req.nightStartHour());
        s.setNightEndHour(req.nightEndHour());
        s.setMlEnabled(req.mlEnabled());
        s.setUpdatedBy(userId);
        settingsRepository.saveAndFlush(s);

        SettingsResponse after = SettingsResponse.from(s, true);
        audit.record(companyId, userId, AuditActions.ANOMALY_SETTINGS_UPDATE, AuditActions.TARGET_ANOMALY_SETTINGS,
                companyId, before, after);
        return after;
    }

    // ================================================================ 분석

    @Transactional
    public ScanResponse scan(Long userId, Long companyId, ScanRequest req) {
        access.requireWrite(userId, companyId);
        LocalDate from = req.from();
        LocalDate to = req.to();
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new ApiException(ErrorCode.ANOMALY_PERIOD_INVALID);
        }
        // 같은 회사의 분석·검토를 직렬화한다 (동시 분석이 서로의 결과를, 분석이 방금 내린 판정을 덮어쓰지 않게)
        lockCompany(companyId);
        long started = System.currentTimeMillis();

        AnomalySettings settings = currentSettings(companyId);
        Thresholds thresholds = settings.toThresholds();

        AnomalyScan scan = new AnomalyScan();
        scan.setCompanyId(companyId);
        scan.setPeriodFrom(from);
        scan.setPeriodTo(to);
        scan.setSummary(Map.of());
        scan.setMlStatus(MlStatus.SKIPPED);
        scan.setMlInfo(Map.of());
        scan.setSettingsSnapshot(settingsSnapshot(settings));
        scan.setCreatedBy(userId);
        scans.saveAndFlush(scan);

        ScanCounter counter = new ScanCounter();
        Map<String, Object> mlInfo = new LinkedHashMap<>();
        List<MlStatus> statuses = new ArrayList<>();
        for (SourceType source : SourceType.values()) {
            List<AnomalyEvent> population = loader.load(companyId, source,
                    from.minusDays(AnomalyFeatureExtractor.HISTORY_DAYS), to.plusDays(LOOKAHEAD_DAYS));
            List<AnomalyEvent> targets = population.stream()
                    .filter(e -> !e.date().isBefore(from) && !e.date().isAfter(to)).toList();
            Set<String> targetKeys = targets.stream().map(AnomalyEvent::key).collect(Collectors.toSet());

            Map<String, Features> features = AnomalyFeatureExtractor.extract(population);
            Map<String, List<RuleHit>> hits = AnomalyRuleEngine.evaluate(population, features,
                    e -> targetKeys.contains(e.key()), thresholds);
            MlOutcome ml = runModel(settings, population, features);
            mlInfo.put(source.name(), ml.info());
            if (!targets.isEmpty()) {
                statuses.add(ml.status());
            }

            Map<Long, AnomalyResult> existing = results
                    .findByCompanyIdAndSourceTypeAndTxDateBetween(companyId, source, from, to).stream()
                    .collect(Collectors.toMap(AnomalyResult::getSourceId, Function.identity()));
            for (AnomalyEvent e : targets) {
                List<RuleHit> eventHits = hits.getOrDefault(e.key(), List.of());
                ScoredSample scored = ml.scores().get(e.key());
                Risk risk = RiskScorer.combine(eventHits, scored == null ? null : scored.score());
                counter.analyze(source, risk);
                apply(companyId, existing.remove(e.sourceId()), e, risk, eventHits, features.get(e.key()),
                        mlDetail(ml, scored, risk), scan.getId(), counter);
            }
            // 기간 안의 기존 결과인데 이번에 읽히지 않은 것: 원천 거래가 취소(VOID)되었거나 입금으로 바뀐 경우
            for (AnomalyResult r : existing.values()) {
                if (r.getStatus() == ResultStatus.OPEN) {
                    r.setStatus(ResultStatus.CLEARED);
                    r.setScanId(scan.getId());
                    Map<String, Object> context = new LinkedHashMap<>(r.getContext());
                    context.put("clearedReason", "원천 거래가 분석 대상에서 빠졌습니다 (취소 등).");
                    r.setContext(context);
                    counter.cleared++;
                }
            }
        }

        scan.setAnalyzedCount(counter.analyzed);
        scan.setFlaggedCount(counter.flagged);
        scan.setSummary(counter.toMap());
        scan.setMlStatus(overall(settings, statuses));
        scan.setMlInfo(mlInfo);
        scan.setDurationMs(System.currentTimeMillis() - started);
        results.flush();
        scans.saveAndFlush(scan);

        ScanResponse response = ScanResponse.from(scan);
        audit.record(companyId, userId, AuditActions.ANOMALY_SCAN, AuditActions.TARGET_ANOMALY_SCAN, scan.getId(),
                null, response);
        return response;
    }

    /**
     * 결과 반영. 사람이 판정한 결과는 그대로 두되, 판정 이후 새로운 규칙이 적중했으면(새 근거) 다시 연다.
     * 기준(40점) 미만이 된 미검토 결과는 해제(CLEARED)한다.
     */
    private void apply(Long companyId, AnomalyResult r, AnomalyEvent e, Risk risk, List<RuleHit> hits, Features features,
                       Map<String, Object> mlDetail, Long scanId, ScanCounter counter) {
        if (r != null && r.isReviewed()) {
            Set<String> reviewedCodes = codes(r.getRuleHits());
            boolean newEvidence = risk.flagged() && !reviewedCodes.containsAll(
                    hits.stream().map(RuleHit::code).collect(Collectors.toSet()));
            if (!newEvidence) {
                counter.keptReviewed++;
                return;
            }
            Map<String, Object> previous = reviewSnapshot(r);
            fill(r, e, risk, hits, features, mlDetail, scanId);
            r.getContext().put("previousReview", previous);
            clearReview(r);
            r.setStatus(ResultStatus.OPEN);
            counter.reopened++;
            return;
        }
        if (risk.flagged()) {
            if (r == null) {
                r = new AnomalyResult();
                r.setCompanyId(companyId);
                counter.created++;
            } else {
                counter.updated++;
            }
            fill(r, e, risk, hits, features, mlDetail, scanId);
            r.setStatus(ResultStatus.OPEN);
            results.save(r);
        } else if (r != null && r.getStatus() == ResultStatus.OPEN) {
            fill(r, e, risk, hits, features, mlDetail, scanId);
            r.setStatus(ResultStatus.CLEARED);
            r.getContext().put("clearedReason", "재분석 결과 위험도가 기준(" + RiskScorer.FLAG_THRESHOLD + "점) 미만입니다.");
            counter.cleared++;
        }
    }

    private void fill(AnomalyResult r, AnomalyEvent e, Risk risk, List<RuleHit> hits, Features features,
                      Map<String, Object> mlDetail, Long scanId) {
        if (r.getId() == null) {
            r.setSourceType(e.sourceType());
            r.setSourceId(e.sourceId());
        }
        r.setTxDate(e.date());
        r.setTxTime(e.time());
        r.setCounterparty(truncate(e.counterparty(), 200));
        r.setAmount(e.amount());
        r.setRiskScore(risk.score());
        r.setRiskLevel(risk.level());
        r.setRuleScore(risk.ruleScore());
        Object score = mlDetail.get("score");
        r.setMlScore(score instanceof Number n ? BigDecimal.valueOf(n.doubleValue()).setScale(3, RoundingMode.HALF_UP)
                : null);
        r.setRuleHits(hits.stream().map(RuleHit::toMap).toList());
        r.setFeatures(features == null ? new LinkedHashMap<>() : features.toMap());
        r.setMlDetail(mlDetail);
        r.setContext(context(e));
        r.setScanId(scanId);
    }

    private MlOutcome runModel(AnomalySettings settings, List<AnomalyEvent> population, Map<String, Features> features) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("samples", population.size());
        if (!settings.isMlEnabled()) {
            info.put("status", MlStatus.DISABLED.name());
            info.put("message", "회사 설정에서 ML을 사용하지 않습니다.");
            return new MlOutcome(MlStatus.DISABLED, info, Map.of(), null);
        }
        if (population.size() < mlMinSamples) {
            info.put("status", MlStatus.SKIPPED.name());
            info.put("message", "표본이 " + population.size() + "건으로 최소 " + mlMinSamples + "건보다 적어 ML을 건너뛰었습니다.");
            return new MlOutcome(MlStatus.SKIPPED, info, Map.of(), null);
        }
        List<Sample> samples = population.stream()
                .map(e -> new Sample(e.key(), features.get(e.key()).toModelInput())).toList();
        long started = System.currentTimeMillis();
        try {
            ModelResult result = model.score(samples, mlMinSamples);
            info.put("durationMs", System.currentTimeMillis() - started);
            info.put("model", result.model());
            if (!result.ok()) {
                info.put("status", MlStatus.SKIPPED.name());
                info.put("message", result.message());
                return new MlOutcome(MlStatus.SKIPPED, info, Map.of(), result.model());
            }
            info.put("status", MlStatus.APPLIED.name());
            Map<String, ScoredSample> scores = new HashMap<>();
            for (ScoredSample s : result.scores()) {
                scores.put(s.id(), s);
            }
            return new MlOutcome(MlStatus.APPLIED, info, scores, result.model());
        } catch (ModelUnavailableException e) {
            log.warn("이상탐지 ML 호출 실패, 규칙 점수만 사용합니다: {}", e.toString());
            info.put("status", MlStatus.UNAVAILABLE.name());
            info.put("message", e.getMessage() + " 규칙 점수만으로 계산했습니다.");
            return new MlOutcome(MlStatus.UNAVAILABLE, info, Map.of(), null);
        }
    }

    private record MlOutcome(MlStatus status, Map<String, Object> info, Map<String, ScoredSample> scores, String model) {
    }

    private static Map<String, Object> mlDetail(MlOutcome ml, ScoredSample scored, Risk risk) {
        Map<String, Object> m = new LinkedHashMap<>();
        MlStatus status = ml.status() == MlStatus.APPLIED && scored == null ? MlStatus.SKIPPED : ml.status();
        m.put("status", status.name());
        m.put("model", ml.model());
        m.put("points", risk.mlPoints());
        m.put("maxPoints", RiskScorer.ML_MAX_POINTS);
        if (scored != null) {
            m.put("score", scored.score());
            m.put("rawScore", scored.rawScore());
            m.put("topFeatures", scored.topFeatures() == null ? List.of() : scored.topFeatures());
        } else {
            m.put("message", ml.info().get("message"));
        }
        return m;
    }

    private static MlStatus overall(AnomalySettings settings, List<MlStatus> statuses) {
        if (!settings.isMlEnabled()) {
            return MlStatus.DISABLED;
        }
        if (statuses.contains(MlStatus.UNAVAILABLE)) {
            return MlStatus.UNAVAILABLE;
        }
        long applied = statuses.stream().filter(s -> s == MlStatus.APPLIED).count();
        if (applied == 0) {
            return MlStatus.SKIPPED;
        }
        return applied == statuses.size() ? MlStatus.APPLIED : MlStatus.PARTIAL;
    }

    /** 스캔 한 번의 건수 */
    private static final class ScanCounter {
        int analyzed;
        int flagged;
        int created;
        int updated;
        int cleared;
        int reopened;
        int keptReviewed;
        final Map<RiskLevel, Integer> byLevel = new EnumMap<>(RiskLevel.class);
        final Map<SourceType, Integer> bySource = new EnumMap<>(SourceType.class);

        void analyze(SourceType source, Risk risk) {
            analyzed++;
            bySource.merge(source, 1, Integer::sum);
            if (risk.flagged()) {
                flagged++;
                byLevel.merge(risk.level(), 1, Integer::sum);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("created", created);
            m.put("updated", updated);
            m.put("cleared", cleared);
            m.put("reopened", reopened);
            m.put("keptReviewed", keptReviewed);
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (RiskLevel level : RiskLevel.values()) {
                levels.put(level.name(), byLevel.getOrDefault(level, 0));
            }
            m.put("byLevel", levels);
            Map<String, Integer> sources = new LinkedHashMap<>();
            for (SourceType s : SourceType.values()) {
                sources.put(s.name(), bySource.getOrDefault(s, 0));
            }
            m.put("analyzedBySource", sources);
            return m;
        }
    }

    // ================================================================ 검토

    @Transactional
    public ResultResponse review(Long userId, Long companyId, Long id, ReviewRequest req) {
        access.requireWrite(userId, companyId);
        lockCompany(companyId);
        AnomalyResult r = find(companyId, id);
        if (r.getStatus() != ResultStatus.OPEN) {
            throw new ApiException(ErrorCode.ANOMALY_STATE_INVALID, "검토 대기(OPEN) 상태인 결과만 판정할 수 있습니다.");
        }
        Map<String, Object> before = reviewSnapshot(r);
        r.setStatus(req.decision() == AnomalyEnums.Decision.CONFIRMED ? ResultStatus.CONFIRMED : ResultStatus.DISMISSED);
        r.setAdjustedLevel(req.adjustedLevel() == null || req.adjustedLevel() == r.getRiskLevel()
                ? null : req.adjustedLevel());
        r.setReviewNote(req.note().trim());
        r.setReviewedBy(userId);
        r.setReviewedAt(Instant.now());
        results.saveAndFlush(r);

        Map<String, Object> after = reviewSnapshot(r);
        after.put("riskScore", r.getRiskScore());
        after.put("ruleCodes", new ArrayList<>(codes(r.getRuleHits())));
        audit.record(companyId, userId, AuditActions.ANOMALY_REVIEW, AuditActions.TARGET_ANOMALY_RESULT, id,
                before, after);
        return ResultResponse.from(r);
    }

    /** 판정을 취소하고 다시 검토 대기로 돌린다. 이전 판정은 context.previousReview와 Audit Log에 남는다. */
    @Transactional
    public ResultResponse reopen(Long userId, Long companyId, Long id, String note) {
        access.requireWrite(userId, companyId);
        lockCompany(companyId);
        AnomalyResult r = find(companyId, id);
        if (!r.isReviewed()) {
            throw new ApiException(ErrorCode.ANOMALY_STATE_INVALID, "판정된 결과만 다시 열 수 있습니다.");
        }
        Map<String, Object> before = reviewSnapshot(r);
        Map<String, Object> context = new LinkedHashMap<>(r.getContext());
        context.put("previousReview", before);
        r.setContext(context);
        clearReview(r);
        r.setStatus(ResultStatus.OPEN);
        results.saveAndFlush(r);

        Map<String, Object> after = reviewSnapshot(r);
        after.put("note", note.trim());
        audit.record(companyId, userId, AuditActions.ANOMALY_REOPEN, AuditActions.TARGET_ANOMALY_RESULT, id,
                before, after);
        return ResultResponse.from(r);
    }

    // ================================================================ 조회

    @Transactional(readOnly = true)
    public Page<ResultListItem> list(Long userId, Long companyId, Filter filter, Pageable pageable) {
        access.requireMember(userId, companyId);
        Specification<AnomalyResult> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (filter.status() != null) {
                p.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.level() != null) {
                p.add(cb.equal(root.get("effectiveLevel"), filter.level()));
            }
            if (filter.sourceType() != null) {
                p.add(cb.equal(root.get("sourceType"), filter.sourceType()));
            }
            if (filter.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("txDate"), filter.from()));
            }
            if (filter.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDate>get("txDate"), filter.to()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return results.findAll(spec, pageable).map(ResultListItem::from);
    }

    @Transactional(readOnly = true)
    public ResultResponse get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        return ResultResponse.from(find(companyId, id));
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        Map<RiskLevel, Long> openByLevel = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            openByLevel.put(level, 0L);
        }
        Map<ResultStatus, Long> byStatus = new EnumMap<>(ResultStatus.class);
        for (Object[] row : results.countByStatusAndLevel(companyId)) {
            ResultStatus status = (ResultStatus) row[0];
            long count = (Long) row[2];
            byStatus.merge(status, count, Long::sum);
            if (status == ResultStatus.OPEN) {
                openByLevel.merge((RiskLevel) row[1], count, Long::sum);
            }
        }
        long open = byStatus.getOrDefault(ResultStatus.OPEN, 0L);
        long highRisk = openByLevel.get(RiskLevel.HIGH) + openByLevel.get(RiskLevel.CRITICAL);
        ScanResponse lastScan = scans.findFirstByCompanyIdOrderByIdDesc(companyId).map(ScanResponse::from).orElse(null);
        return new SummaryResponse(openByLevel, open, highRisk, byStatus.getOrDefault(ResultStatus.CONFIRMED, 0L),
                byStatus.getOrDefault(ResultStatus.DISMISSED, 0L), byStatus.getOrDefault(ResultStatus.CLEARED, 0L),
                lastScan);
    }

    @Transactional(readOnly = true)
    public List<ScanResponse> scans(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        return scans.findTop20ByCompanyIdOrderByIdDesc(companyId).stream().map(ScanResponse::from).toList();
    }

    // ================================================================ 도우미

    private AnomalySettings currentSettings(Long companyId) {
        return settingsRepository.findById(companyId).orElseGet(() -> AnomalySettings.defaults(companyId));
    }

    private void lockCompany(Long companyId) {
        companies.lockById(companyId).orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
    }

    private AnomalyResult find(Long companyId, Long id) {
        return results.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static Map<String, Object> settingsSnapshot(AnomalySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approvalLimit", s.getApprovalLimit().toPlainString());
        m.put("nearLimitRatio", s.getNearLimitRatio().toPlainString());
        m.put("evidenceMinAmount", s.getEvidenceMinAmount().toPlainString());
        m.put("newVendorMinAmount", s.getNewVendorMinAmount().toPlainString());
        m.put("spikeMultiplier", s.getSpikeMultiplier().toPlainString());
        m.put("nightStartHour", s.getNightStartHour());
        m.put("nightEndHour", s.getNightEndHour());
        m.put("mlEnabled", s.isMlEnabled());
        return m;
    }

    private static Map<String, Object> context(AnomalyEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memo", e.memo());
        m.put("bankAccountId", e.bankAccountId());
        m.put("accountId", e.accountId());
        m.put("accountCode", e.accountCode());
        m.put("accountName", e.accountName());
        m.put("journalEntryIds", e.journalEntryIds());
        m.put("documentIds", e.documentIds());
        return m;
    }

    private static Map<String, Object> reviewSnapshot(AnomalyResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.getStatus().name());
        m.put("riskLevel", r.getRiskLevel().name());
        m.put("adjustedLevel", r.getAdjustedLevel() == null ? null : r.getAdjustedLevel().name());
        m.put("note", r.getReviewNote());
        m.put("reviewedBy", r.getReviewedBy());
        m.put("reviewedAt", r.getReviewedAt() == null ? null : r.getReviewedAt().toString());
        return m;
    }

    private static void clearReview(AnomalyResult r) {
        r.setAdjustedLevel(null);
        r.setReviewNote(null);
        r.setReviewedBy(null);
        r.setReviewedAt(null);
    }

    private static Set<String> codes(List<Map<String, Object>> hits) {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> h : hits) {
            codes.add(String.valueOf(h.get("code")));
        }
        return codes;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
