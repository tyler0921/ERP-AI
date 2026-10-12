package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.copilot.CopilotService;
import com.ledgerguard.explanation.infra.LlmJobQueue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 7 AI Copilot: 질문 → 계획 작업(큐) → 계획 콜백(검증·실행) → 답변 작업(큐) → 답변 콜백,
 * 해석·답변 수정(Audit), 실패·재시도·지원하지 않는 질문, Query Tool 결과, 권한·회사 격리.
 * 실제 LLM worker 대신 테스트가 내부 콜백 API를 호출한다.
 */
@SpringBootTest(properties = "app.documents.timeout-check-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
class CopilotApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static class CapturingLlmQueue implements LlmJobQueue {
        final List<LlmJob> jobs = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(LlmJob job) {
            jobs.add(job);
        }
    }

    @TestConfiguration
    static class Infra {
        @Bean
        @Primary
        CapturingLlmQueue capturingLlmQueue() {
            return new CapturingLlmQueue();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingLlmQueue llmQueue;
    @Autowired
    CopilotService copilotService;

    private static final String TOKEN = "dev-internal-token-change-me";

    // ------------------------------------------------------------------ helpers

    private record Login(String email, long userId, String token) {
    }

    private JsonNode call(HttpMethod method, String url, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        String content = mvc.perform(builder).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isBlank() ? null : om.readTree(content);
    }

    private JsonNode internal(String url, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.POST, url)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        String content = mvc.perform(builder).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(content);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    private Login signupAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        JsonNode user = call(HttpMethod.POST, "/api/v1/auth/signup", null,
                map("email", email, "password", "password1234", "name", "테스터"), 201);
        JsonNode tokens = call(HttpMethod.POST, "/api/v1/auth/login", null,
                map("email", email, "password", "password1234"), 200);
        return new Login(email, user.get("id").asLong(), tokens.get("accessToken").asText());
    }

    private String newCompany(Login admin) throws Exception {
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "질문상사-" + UUID.randomUUID()), 201).get("id").asLong();
        return "/api/v1/companies/" + cid;
    }

    private static long companyId(String base) {
        return Long.parseLong(base.substring(base.lastIndexOf('/') + 1));
    }

    private void entry(Login login, String base, Map<String, Long> acc, String date, String dr, String cr, String amount,
                       Long transactionId) throws Exception {
        Map<String, Object> body = map("entryDate", date, "description", dr + "/" + cr, "lines", List.of(
                map("accountId", acc.get(dr), "debit", amount, "credit", "0"),
                map("accountId", acc.get(cr), "debit", "0", "credit", amount)));
        if (transactionId != null) {
            body.put("transactionId", transactionId);
        }
        call(HttpMethod.POST, base + "/journal-entries", login.token(), body, 201);
    }

    private long tx(Login login, String base, String date, String counterparty, String amount) throws Exception {
        return call(HttpMethod.POST, base + "/transactions", login.token(), map("txDate", date,
                "counterparty", counterparty, "amount", amount, "direction", "OUT"), 201).get("id").asLong();
    }

    /**
     * 가상 장부 (Phase 6 테스트와 같은 모양) + 이상거래 3건.
     * <pre>
     * 2025-09  매출 10,000,000 / 급여 3,000,000
     * 2026-08  매출 12,000,000 / 급여 3,000,000 / 광고선전비 1,000,000 (가상 광고)
     * 2026-09  매출 20,000,000 / 급여 3,000,000 / 광고선전비 5,000,000 ((주)가상광고) / 운반비 400,000 (가상택배)
     * 2026-08  운반비 900,000 (가상택배)
     * </pre>
     */
    private void books(Login admin, String base) throws Exception {
        Map<String, Long> acc = new HashMap<>();
        for (JsonNode a : call(HttpMethod.GET, base + "/accounts", admin.token(), null, 200)) {
            acc.put(a.get("code").asText(), a.get("id").asLong());
        }
        entry(admin, base, acc, "2025-09-10", "1020", "4010", "10000000", null);
        entry(admin, base, acc, "2025-09-25", "5100", "1020", "3000000", null);
        entry(admin, base, acc, "2026-08-10", "1020", "4010", "12000000", null);
        entry(admin, base, acc, "2026-08-25", "5100", "1020", "3000000", null);
        entry(admin, base, acc, "2026-08-12", "5400", "1020", "1000000", tx(admin, base, "2026-08-12", "가상 광고", "1000000"));
        entry(admin, base, acc, "2026-08-14", "5800", "1020", "900000", tx(admin, base, "2026-08-14", "가상택배", "900000"));
        entry(admin, base, acc, "2026-09-10", "1020", "4010", "20000000", null);
        entry(admin, base, acc, "2026-09-25", "5100", "1020", "3000000", null);
        entry(admin, base, acc, "2026-09-12", "5400", "1020", "5000000", tx(admin, base, "2026-09-12", "(주)가상광고", "5000000"));
        entry(admin, base, acc, "2026-09-14", "5800", "1020", "400000", tx(admin, base, "2026-09-14", "가상택배", "400000"));

        long cid = companyId(base);
        long scanId = jdbc.queryForObject("""
                INSERT INTO anomaly_scans (company_id, period_from, period_to, analyzed_count, flagged_count, summary,
                                           ml_status, ml_info, settings_snapshot, duration_ms, created_by)
                VALUES (?, DATE '2026-09-01', DATE '2026-09-30', 10, 3, '{}', 'SKIPPED', '{}', '{}', 1, ?) RETURNING id
                """, Long.class, cid, admin.userId());
        anomaly(cid, admin.userId(), scanId, 1, "2026-09-08", "가상컨설팅", "4950000", 100, "CRITICAL", "OPEN");
        anomaly(cid, admin.userId(), scanId, 2, "2026-09-16", "가상물산", "1253000", 65, "HIGH", "OPEN");
        anomaly(cid, admin.userId(), scanId, 3, "2026-09-12", "심야주점", "45000", 49, "MEDIUM", "OPEN");
        anomaly(cid, admin.userId(), scanId, 4, "2026-09-20", "가상렌탈", "440000", 70, "HIGH", "DISMISSED");
    }

    private void anomaly(long cid, long reviewer, long scanId, long sourceId, String date, String counterparty, String amount, int score,
                         String level, String status) {
        jdbc.update("""
                INSERT INTO anomaly_results (company_id, source_type, source_id, tx_date, counterparty, amount, risk_score,
                    risk_level, rule_score, rule_hits, features, ml_detail, context, status, review_note, reviewed_by,
                    reviewed_at, scan_id)
                VALUES (?, 'BANK_TRANSACTION', ?, CAST(? AS date), ?, CAST(? AS numeric), ?, ?, ?,
                        CAST(? AS jsonb), '{}', '{}', '{}', ?, ?, ?, ?, ?)
                """, cid, sourceId, date, counterparty, amount, score, level, score,
                "[{\"code\":\"MISSING_EVIDENCE\",\"title\":\"증빙 없는 거래\",\"weight\":20},"
                        + "{\"code\":\"SPLIT_PAYMENT\",\"title\":\"쪼개기 결제 의심\",\"weight\":45}]",
                status, "OPEN".equals(status) ? null : "정상 지급", "OPEN".equals(status) ? null : reviewer,
                "OPEN".equals(status) ? null : java.sql.Timestamp.from(Instant.now()), scanId);
    }

    private List<LlmJobQueue.LlmJob> jobsAfter(int index) {
        return new ArrayList<>(llmQueue.jobs.subList(index, llmQueue.jobs.size()));
    }

    private JsonNode ask(Login login, String base, String question) throws Exception {
        return call(HttpMethod.POST, base + "/copilot/questions", login.token(), map("question", question), 201);
    }

    private JsonNode planCallback(long id, int attempt, Map<String, Object> plan, String source) throws Exception {
        return internal("/internal/v1/copilot/questions/" + id + "/plan", TOKEN, map("attempt", attempt, "success", true,
                "plan", plan, "source", source, "meta", map("model", "qwen2.5:7b-instruct", "durationMs", 900)), 202);
    }

    private JsonNode get(Login login, String base, long id) throws Exception {
        return call(HttpMethod.GET, base + "/copilot/questions/" + id, login.token(), null, 200);
    }

    private JsonNode lastAudit(Login login, String base, String action) throws Exception {
        for (JsonNode a : call(HttpMethod.GET, base + "/audit-logs?size=30", login.token(), null, 200).get("content")) {
            if (a.get("action").asText().equals(action)) {
                return a;
            }
        }
        throw new AssertionError(action + " Audit Log가 없습니다.");
    }

    /** 결과 표 하나의 행에서 key 열 값 목록 */
    private static List<String> column(JsonNode result, int table, String key) {
        List<String> values = new ArrayList<>();
        result.get("tables").get(table).get("rows").forEach(r -> values.add(r.get(key).asText()));
        return values;
    }

    // -------------------------------------------------------------------- tests

    @Test
    @SuppressWarnings("unchecked")
    void 질문부터_계획_조회_답변_수정까지() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        books(admin, base);

        // --- 질문: 커밋 후 계획 작업이 큐에 들어간다 (질문·오늘·계정과목 이름만, 원본 데이터 없음)
        int before = llmQueue.jobs.size();
        JsonNode asked = ask(admin, base, "  9월 광고비가 얼마나 늘었어?  ");
        long id = asked.get("id").asLong();
        assertThat(asked.get("status").asText()).isEqualTo("PLANNING");
        assertThat(asked.get("question").asText()).isEqualTo("9월 광고비가 얼마나 늘었어?");
        List<LlmJobQueue.LlmJob> jobs = jobsAfter(before);
        assertThat(jobs).hasSize(1);
        LlmJobQueue.LlmJob planJob = jobs.get(0);
        assertThat(planJob.targetType()).isEqualTo("COPILOT_PLAN");
        assertThat(planJob.explanationId()).isEqualTo(id);
        assertThat(planJob.facts()).containsOnlyKeys("question", "today", "accounts");
        assertThat(planJob.facts().get("today")).isEqualTo(java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) planJob.facts().get("accounts");
        assertThat(accounts).extracting(a -> a.get("name")).contains("상품매출", "광고선전비").doesNotContain("보통예금");
        assertThat(lastAudit(admin, base, "COPILOT_ASK").get("after").get("question").asText()).isEqualTo("9월 광고비가 얼마나 늘었어?");

        // --- 계획 콜백: 토큰 확인 → 검증·실행 → 답변 작업
        internal("/internal/v1/copilot/questions/" + id + "/plan", null, map("attempt", 1, "success", true), 401);
        before = llmQueue.jobs.size();
        assertThat(planCallback(id, 1, map("tool", "ACCOUNT_SUMMARY", "account", "광고비",
                "period", map("type", "MONTH", "year", 2026, "month", 9)), "LLM").get("status").asText()).isEqualTo("applied");
        assertThat(planCallback(id, 1, map("tool", "PROFIT_SUMMARY"), "LLM").get("status").asText()).isEqualTo("ignored");
        JsonNode q = get(admin, base, id);
        assertThat(q.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(q.get("planSource").asText()).isEqualTo("LLM");
        assertThat(q.get("plan").get("account").asText()).isEqualTo("광고비");
        assertThat(q.get("planMeta").get("model").asText()).isEqualTo("qwen2.5:7b-instruct");
        JsonNode result = q.get("result");
        assertThat(result.get("title").asText()).isEqualTo("5400 광고선전비");
        assertThat(result.get("period").asText()).isEqualTo("2026년 9월");
        assertThat(result.get("comparePeriod").asText()).isEqualTo("2026년 8월");
        assertThat(result.get("summary").get(0).asText())
                .isEqualTo("2026년 9월 광고선전비는 5,000,000원이며, 2026년 8월 1,000,000원 대비 4,000,000원 증가 (+400.0%)입니다.");
        assertThat(column(result, 1, "vendor")).containsExactly("(주)가상광고");
        assertThat(q.get("answer").get("status").asText()).isEqualTo("PENDING");
        List<LlmJobQueue.LlmJob> answerJobs = jobsAfter(before);
        assertThat(answerJobs).hasSize(1);
        assertThat(answerJobs.get(0).targetType()).isEqualTo("COPILOT_ANSWER");
        assertThat(answerJobs.get(0).facts().get("질문")).isEqualTo("9월 광고비가 얼마나 늘었어?");
        assertThat((List<String>) answerJobs.get(0).facts().get("요약")).hasSize(2);

        // --- 답변 콜백 (Phase 5 설명 콜백 그대로)
        long answerId = answerJobs.get(0).explanationId();
        String text = "9월 광고선전비는 5,000,000원으로 8월 1,000,000원보다 4,000,000원 늘었습니다.";
        internal("/internal/v1/explanations/" + answerId + "/result", TOKEN, map("attempt", 1, "success", true,
                "status", "GENERATED", "text", text, "checkpoints", List.of(),
                "meta", map("model", "qwen2.5:7b-instruct", "promptVersion", "copilot-v1")), 202);
        assertThat(get(admin, base, id).get("answer").get("text").asText()).isEqualTo(text);

        // --- 답변 수정·다시 생성 (Audit)
        JsonNode edited = call(HttpMethod.PUT, base + "/copilot/questions/" + id + "/answer", admin.token(),
                map("text", " 광고비가 4배 늘었습니다. ", "checkpoints", List.of("캠페인 계약 확인")), 200).get("answer");
        assertThat(edited.get("status").asText()).isEqualTo("EDITED");
        assertThat(lastAudit(admin, base, "COPILOT_ANSWER_EDIT").get("before").get("text").asText()).isEqualTo(text);
        JsonNode regenerated = call(HttpMethod.POST, base + "/copilot/questions/" + id + "/answer/regenerate", admin.token(),
                null, 200).get("answer");
        assertThat(regenerated.get("status").asText()).isEqualTo("PENDING");
        assertThat(regenerated.get("attempt").asInt()).isEqualTo(2);
        assertThat(lastAudit(admin, base, "COPILOT_ANSWER_REGENERATE").get("before").get("status").asText()).isEqualTo("EDITED");
        // 다시 생성은 생성 중이면 막히지만,
        call(HttpMethod.POST, base + "/copilot/questions/" + id + "/answer/regenerate", admin.token(), null, 409);

        // --- 해석 수정은 근거가 바뀌므로 생성 중인 답변도 새 시도로 대체한다 (Audit)
        before = llmQueue.jobs.size();
        JsonNode replanned = call(HttpMethod.PUT, base + "/copilot/questions/" + id + "/plan", admin.token(),
                map("plan", map("tool", "VENDOR_RANKING", "order", "INCREASE",
                        "period", map("type", "MONTH", "year", 2026, "month", 9))), 200);
        assertThat(replanned.get("planSource").asText()).isEqualTo("USER");
        assertThat(replanned.get("result").get("title").asText()).isEqualTo("거래처별 비용 (증가액 순)");
        assertThat(column(replanned.get("result"), 0, "vendor")).containsExactly("(주)가상광고"); // 미상(급여)은 변동 없음, 가상택배는 감소
        assertThat(replanned.get("answer").get("status").asText()).isEqualTo("PENDING");
        assertThat(replanned.get("answer").get("attempt").asInt()).isEqualTo(3);
        assertThat(jobsAfter(before)).extracting(LlmJobQueue.LlmJob::targetType).containsExactly("COPILOT_ANSWER");
        // 대체된 이전 시도의 결과는 반영되지 않는다
        assertThat(internal("/internal/v1/explanations/" + answerId + "/result", TOKEN, map("attempt", 2, "success", true,
                "status", "GENERATED", "text", "이전 해석의 답변입니다.", "checkpoints", List.of(), "meta", map()), 202)
                .get("status").asText()).isEqualTo("ignored");
        JsonNode planLog = lastAudit(admin, base, "COPILOT_PLAN_EDIT");
        assertThat(planLog.get("before").get("plan").get("tool").asText()).isEqualTo("ACCOUNT_SUMMARY");
        assertThat(planLog.get("after").get("plan").get("tool").asText()).isEqualTo("VENDOR_RANKING");

        // 잘못된 해석은 거부하고 그대로 둔다
        JsonNode invalid = call(HttpMethod.PUT, base + "/copilot/questions/" + id + "/plan", admin.token(),
                map("plan", map("tool", "RUN_SQL", "sql", "DELETE FROM journal_entries")), 422);
        assertThat(invalid.get("code").asText()).isEqualTo("COPILOT_PLAN_INVALID");
        assertThat(invalid.get("details").get(0).get("reason").asText()).contains("tool");
        JsonNode noAccount = call(HttpMethod.PUT, base + "/copilot/questions/" + id + "/plan", admin.token(),
                map("plan", map("tool", "ACCOUNT_SUMMARY", "account", "부가세")), 422);
        assertThat(noAccount.get("message").asText()).contains("찾을 수 없습니다");
        assertThat(get(admin, base, id).get("result").get("tool").asText()).isEqualTo("VENDOR_RANKING");

        // --- 목록 (내 질문, 최신 순)
        JsonNode list = call(HttpMethod.GET, base + "/copilot/questions", admin.token(), null, 200);
        assertThat(list.get("content").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(list.get("content").get(0).get("tool").asText()).isEqualTo("거래처별 비용 (증가액 순)");
    }

    @Test
    void Query_Tool_결과() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        books(admin, base);
        long id = ask(admin, base, "질문").get("id").asLong();
        planCallback(id, 1, map("tool", "UNSUPPORTED", "reason", "x"), "RULE");
        String url = base + "/copilot/questions/" + id + "/plan";

        // 손익 요약: 2026-09 vs 전년 동월
        JsonNode profit = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "PROFIT_SUMMARY", "compare", "PREV_YEAR",
                "period", map("type", "MONTH", "year", 2026, "month", 9))), 200).get("result");
        assertThat(profit.get("comparePeriod").asText()).isEqualTo("2025년 9월");
        assertThat(dec(profit.get("highlights").get(0).get("value"))).isEqualByComparingTo("20000000");
        assertThat(dec(profit.get("highlights").get(1).get("value"))).isEqualByComparingTo("8400000");
        assertThat(profit.get("summary").get(0).asText()).contains("10,000,000원 증가 (+100.0%)");

        // 3분기 손익: 7~9월 합계, 비교는 2분기 (실적 없음)
        JsonNode q3 = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "PROFIT_SUMMARY",
                "period", map("type", "QUARTER", "year", 2026, "quarter", 3))), 200).get("result");
        assertThat(q3.get("period").asText()).isEqualTo("2026년 3분기");
        assertThat(dec(q3.get("highlights").get(0).get("value"))).isEqualByComparingTo("32000000");

        // 거래처 순위: 운반비만, 감소액 순 (계정 조건이 SQL에 들어간다)
        JsonNode down = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "VENDOR_RANKING", "account", "택배비",
                "order", "DECREASE", "period", map("type", "MONTH", "year", 2026, "month", 9))), 200).get("result");
        assertThat(down.get("title").asText()).isEqualTo("거래처별 운반비 (감소액 순)");
        assertThat(column(down, 0, "vendor")).containsExactly("가상택배");
        assertThat(dec(down.get("tables").get(0).get("rows").get(0).get("diff"))).isEqualByComparingTo("-500000");
        // 수익 계정은 거래처 순위에 쓸 수 없다
        call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "VENDOR_RANKING", "account", "상품매출")), 422);

        // 이상거래: 높음 이상 + 검토 대기만 (정상 처리된 가상렌탈 제외), 위험도 순
        JsonNode high = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "ANOMALY_LIST", "minLevel", "HIGH",
                "period", map("type", "MONTH", "year", 2026, "month", 9))), 200).get("result");
        assertThat(column(high, 0, "counterparty")).containsExactly("가상컨설팅", "가상물산");
        assertThat(high.get("tables").get(0).get("rows").get(0).get("anomalyId").asLong()).isPositive();
        assertThat(high.get("tables").get(0).get("rows").get(0).get("rules").asText()).isEqualTo("쪼개기 결제 의심, 증빙 없는 거래");
        JsonNode all = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "ANOMALY_LIST", "status", "ALL", "limit", 2,
                "period", map("type", "MONTH", "year", 2026, "month", 9))), 200).get("result");
        assertThat(all.get("highlights").get(0).get("value").asText()).isEqualTo("4");
        assertThat(all.get("tables").get(0).get("rows")).hasSize(2);
        assertThat(all.get("summary").get(2).asText()).isEqualTo("위험도 상위 2건을 보여줍니다.");

        // 월마감 현황: 한 달만
        JsonNode closing = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "CLOSING_STATUS",
                "period", map("type", "MONTH", "year", 2026, "month", 9))), 200).get("result");
        assertThat(closing.get("highlights").get(1).get("value").asText()).isEqualTo("마감 전");
        assertThat(closing.get("summary").get(1).asText()).isEqualTo("남은 항목은 이상거래 검토 (3건)입니다.");
        JsonNode quarter = call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "CLOSING_STATUS",
                "period", map("type", "QUARTER", "year", 2026, "quarter", 3))), 422);
        assertThat(quarter.get("message").asText()).contains("한 달 단위");
        // 미래 기간
        String next = YearMonth.now(ZoneId.of("Asia/Seoul")).plusMonths(1).toString();
        call(HttpMethod.PUT, url, admin.token(), map("plan", map("tool", "PROFIT_SUMMARY", "period", map("type", "MONTH",
                "year", Integer.parseInt(next.substring(0, 4)), "month", Integer.parseInt(next.substring(5))))), 422);
    }

    @Test
    void 실패_재시도_지원하지_않는_질문_시간_초과() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        books(admin, base);

        // 형식이 틀린 계획 → FAILED (거부된 계획은 meta에) → 다시 시도하면 새 시도 번호
        long id = ask(admin, base, "광고비?").get("id").asLong();
        planCallback(id, 1, map("tool", "ACCOUNT_SUMMARY", "period", map("type", "SOMEDAY")), "LLM");
        JsonNode failed = get(admin, base, id);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).contains("period.type");
        assertThat(failed.get("planMeta").get("rejectedPlan").get("tool").asText()).isEqualTo("ACCOUNT_SUMMARY");
        int before = llmQueue.jobs.size();
        JsonNode retried = call(HttpMethod.POST, base + "/copilot/questions/" + id + "/retry", admin.token(), null, 200);
        assertThat(retried.get("status").asText()).isEqualTo("PLANNING");
        assertThat(retried.get("attempt").asInt()).isEqualTo(2);
        assertThat(jobsAfter(before)).singleElement().satisfies(j -> assertThat(j.attempt()).isEqualTo(2));
        call(HttpMethod.POST, base + "/copilot/questions/" + id + "/retry", admin.token(), null, 409);
        assertThat(planCallback(id, 1, map("tool", "PROFIT_SUMMARY"), "LLM").get("status").asText()).isEqualTo("ignored");

        // 형식은 맞지만 실행할 수 없는 계획(없는 계정과목) → FAILED, 계획은 남겨 사람이 고칠 수 있게
        planCallback(id, 2, map("tool", "ACCOUNT_SUMMARY", "account", "부가세"), "RULE");
        JsonNode noAccount = get(admin, base, id);
        assertThat(noAccount.get("status").asText()).isEqualTo("FAILED");
        assertThat(noAccount.get("planSource").asText()).isEqualTo("RULE");
        assertThat(noAccount.get("plan").get("account").asText()).isEqualTo("부가세");
        assertThat(noAccount.get("error").asText()).contains("찾을 수 없습니다").contains("5400 광고선전비");
        JsonNode fixed = call(HttpMethod.PUT, base + "/copilot/questions/" + id + "/plan", admin.token(),
                map("plan", map("tool", "ACCOUNT_SUMMARY", "account", "광고선전비")), 200);
        assertThat(fixed.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(fixed.get("error").isNull()).isTrue();

        // worker가 계획을 못 만듦
        long broken = ask(admin, base, "뭐든").get("id").asLong();
        internal("/internal/v1/copilot/questions/" + broken + "/plan", TOKEN,
                map("attempt", 1, "success", false, "error", "RuntimeError: boom"), 202);
        assertThat(get(admin, base, broken).get("error").asText()).isEqualTo("RuntimeError: boom");

        // 지원하지 않는 질문: 조회·LLM 답변 없이 이유와 예시
        long dept = ask(admin, base, "3분기 접대비가 가장 많은 부서는?").get("id").asLong();
        before = llmQueue.jobs.size();
        planCallback(dept, 1, map("tool", "UNSUPPORTED", "reason", "부서 데이터가 없어 부서별로 조회할 수 없습니다."), "RULE");
        JsonNode unsupported = get(admin, base, dept);
        assertThat(unsupported.get("status").asText()).isEqualTo("UNSUPPORTED");
        assertThat(unsupported.get("answer").isNull()).isTrue();
        assertThat(unsupported.get("result").get("summary").get(0).asText()).isEqualTo("부서 데이터가 없어 부서별로 조회할 수 없습니다.");
        assertThat(unsupported.get("result").get("summary").get(1).asText()).startsWith("이런 질문을 할 수 있습니다");
        assertThat(jobsAfter(before)).isEmpty();
        call(HttpMethod.POST, base + "/copilot/questions/" + dept + "/answer/regenerate", admin.token(), null, 409);

        // 동시에 해석 중인 질문은 3개까지
        ask(admin, base, "1");
        ask(admin, base, "2");
        ask(admin, base, "3");
        assertThat(call(HttpMethod.POST, base + "/copilot/questions", admin.token(), map("question", "4"), 429)
                .get("code").asText()).isEqualTo("COPILOT_BUSY");
        call(HttpMethod.POST, base + "/copilot/questions", admin.token(), map("question", " "), 400);
        call(HttpMethod.POST, base + "/copilot/questions", admin.token(), map("question", "가".repeat(501)), 400);

        // 시간 초과 → FAILED
        assertThat(copilotService.failTimedOut(Instant.now().plusSeconds(1))).isGreaterThanOrEqualTo(3);
        JsonNode timedOut = call(HttpMethod.GET, base + "/copilot/questions", admin.token(), null, 200).get("content").get(0);
        assertThat(timedOut.get("status").asText()).isEqualTo("FAILED");
        ask(admin, base, "이제 가능");
    }

    @Test
    void 권한과_회사_격리() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        Login viewer = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);

        // VIEWER도 질문할 수 있다 (조회만 하므로). 다른 사람의 질문은 보이지 않는다
        long mine = ask(viewer, base, "지난달 손익은?").get("id").asLong();
        planCallback(mine, 1, map("tool", "PROFIT_SUMMARY"), "LLM");
        assertThat(get(viewer, base, mine).get("status").asText()).isEqualTo("ANSWERED");
        call(HttpMethod.GET, base + "/copilot/questions/" + mine, admin.token(), null, 404);
        call(HttpMethod.PUT, base + "/copilot/questions/" + mine + "/answer", admin.token(), map("text", "x"), 404);
        assertThat(call(HttpMethod.GET, base + "/copilot/questions", admin.token(), null, 200).get("totalElements").asLong())
                .isZero();

        // 다른 회사: 경로의 회사가 다르면 404, 멤버가 아니면 404
        Login outsider = signupAndLogin();
        String otherBase = newCompany(outsider);
        call(HttpMethod.GET, otherBase + "/copilot/questions/" + mine, outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/copilot/questions", outsider.token(), null, 404);
        call(HttpMethod.POST, base + "/copilot/questions", outsider.token(), map("question", "x"), 404);
        call(HttpMethod.GET, base + "/copilot/questions", null, null, 401);
    }
}
