package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.explanation.infra.LlmJobQueue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 6 월마감: 전표 집계 → 손익·전월/전년 동월 비교·주요 변동, 체크리스트, LLM 설명(요청·재생성·수정),
 * 마감 확정·확정 이후 변경 표시·마감 취소, 월별 추이, 권한·회사 격리.
 */
@SpringBootTest(properties = "app.documents.timeout-check-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
class ClosingApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** LLM 작업을 붙잡아 두는 큐. 실제 worker 대신 테스트가 내부 콜백 API를 호출한다. */
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

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
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
                map("name", "마감상사-" + UUID.randomUUID()), 201).get("id").asLong();
        return "/api/v1/companies/" + cid;
    }

    private Map<String, Long> accountIds(Login login, String base) throws Exception {
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode a : call(HttpMethod.GET, base + "/accounts", login.token(), null, 200)) {
            ids.put(a.get("code").asText(), a.get("id").asLong());
        }
        return ids;
    }

    /** 차변 debitCode / 대변 creditCode 한 쌍의 전표 */
    private long entry(Login login, String base, Map<String, Long> acc, String date, String debitCode,
                       String creditCode, String amount, Long transactionId) throws Exception {
        Map<String, Object> body = map("entryDate", date, "description", debitCode + "/" + creditCode,
                "lines", List.of(
                        map("accountId", acc.get(debitCode), "debit", amount, "credit", "0"),
                        map("accountId", acc.get(creditCode), "debit", "0", "credit", amount)));
        if (transactionId != null) {
            body.put("transactionId", transactionId);
        }
        return call(HttpMethod.POST, base + "/journal-entries", login.token(), body, 201).get("id").asLong();
    }

    private long transaction(Login login, String base, String date, String counterparty, String amount) throws Exception {
        return call(HttpMethod.POST, base + "/transactions", login.token(), map("txDate", date,
                "counterparty", counterparty, "amount", amount, "direction", "OUT"), 201).get("id").asLong();
    }

    private void insertReviewRequiredDocument(long companyId, long userId, String issueDate) {
        jdbc.update("""
                INSERT INTO documents (company_id, doc_type, status, file_key, file_name, content_type, file_size,
                                       file_sha256, vendor_name, vendor_business_no, issue_date, total_amount, uploaded_by)
                VALUES (?, 'RECEIPT', 'REVIEW_REQUIRED', ?, 'r.png', 'image/png', 10, ?, '가상커피', '211-88-55554',
                        CAST(? AS date), 28000, ?)
                """, companyId, "companies/" + companyId + "/documents/" + UUID.randomUUID() + ".png",
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                issueDate, userId);
    }

    private void importBank(Login login, String base, Map<String, Long> acc, String csv) throws Exception {
        long bankAccountId = call(HttpMethod.POST, base + "/bank-accounts", login.token(),
                map("name", "운영 계좌", "bankName", "가상은행", "glAccountId", acc.get("1020")), 201).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(base + "/bank-accounts/" + bankAccountId + "/imports").file(file)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().is(201));
    }

    /** 금액은 JSON 문자열로 온다 */
    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.get(field).asText().equals(value)) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " 항목이 없습니다: " + array);
    }

    private JsonNode lastAudit(Login login, String base, String action) throws Exception {
        for (JsonNode a : call(HttpMethod.GET, base + "/audit-logs?size=20", login.token(), null, 200).get("content")) {
            if (a.get("action").asText().equals(action)) {
                return a;
            }
        }
        throw new AssertionError(action + " Audit Log가 없습니다.");
    }

    private void callback(long explanationId, int attempt, String text) throws Exception {
        mvc.perform(request(HttpMethod.POST, "/internal/v1/explanations/" + explanationId + "/result")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(map("attempt", attempt, "success", true, "status", "GENERATED",
                                "text", text, "checkpoints", List.of("광고비 증가 사유 확인"),
                                "meta", map("provider", "ollama", "model", "qwen2.5:7b-instruct",
                                        "promptVersion", "closing-v1", "durationMs", 1800, "validation", List.of())))))
                .andExpect(status().is(202));
    }

    /**
     * 가상 회사 장부.
     * <pre>
     * 2025-09  매출 10,000,000 / 급여 3,000,000
     * 2026-08  매출 12,000,000 / 급여 3,000,000 / 광고선전비 1,000,000 (거래: 가상 광고)
     * 2026-09  매출 20,000,000 / 급여 3,000,000 / 광고선전비 5,000,000 (거래: (주)가상광고) / 임차료 2,000,000
     *          + 취소된 접대비 9,000,000 (집계 제외), 전표 없는 거래 1건, 확인 필요 증빙 1건, 미대사 은행 거래 2건
     * </pre>
     */
    private Map<String, Long> books(Login admin, String base, long companyId) throws Exception {
        Map<String, Long> acc = accountIds(admin, base);
        entry(admin, base, acc, "2025-09-10", "1020", "4010", "10000000", null);
        entry(admin, base, acc, "2025-09-25", "5100", "1020", "3000000", null);

        entry(admin, base, acc, "2026-08-10", "1020", "4010", "12000000", null);
        entry(admin, base, acc, "2026-08-25", "5100", "1020", "3000000", null);
        long augAd = transaction(admin, base, "2026-08-12", "가상 광고", "1000000");
        entry(admin, base, acc, "2026-08-12", "5400", "1020", "1000000", augAd);

        entry(admin, base, acc, "2026-09-10", "1020", "4010", "20000000", null);
        entry(admin, base, acc, "2026-09-25", "5100", "1020", "3000000", null);
        long sepAd = transaction(admin, base, "2026-09-12", "(주)가상광고", "5000000");
        entry(admin, base, acc, "2026-09-12", "5400", "1020", "5000000", sepAd);
        entry(admin, base, acc, "2026-09-01", "5500", "1020", "2000000", null);
        long voided = entry(admin, base, acc, "2026-09-15", "5300", "1020", "9000000", null);
        call(HttpMethod.POST, base + "/journal-entries/" + voided + "/void", admin.token(), map("reason", "중복 입력"), 200);

        transaction(admin, base, "2026-09-20", "가상택배", "33000");
        insertReviewRequiredDocument(companyId, admin.userId(), "2026-09-18");
        importBank(admin, base, acc, "거래일자,거래시간,출금액,입금액,거래처,적요\n"
                + "2026-09-12,10:00,5000000,,가상광고,광고비\n"
                + "2026-09-25,10:00,3000000,,급여,9월 급여\n");
        return acc;
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 월마감_보고서_집계와_체크리스트() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        long cid = Long.parseLong(base.substring(base.lastIndexOf('/') + 1));
        books(admin, base, cid);

        JsonNode r = call(HttpMethod.GET, base + "/closing/2026-09", admin.token(), null, 200);
        assertThat(r.get("month").asText()).isEqualTo("2026-09");
        assertThat(r.get("prevMonth").asText()).isEqualTo("2026-08");
        assertThat(r.get("prevYearMonth").asText()).isEqualTo("2025-09");

        // --- 합계 (취소된 접대비 900만은 제외)
        JsonNode revenue = find(r.get("totals"), "key", "REVENUE");
        assertThat(dec(revenue.get("current"))).isEqualByComparingTo("20000000");
        assertThat(dec(revenue.get("prevMonth"))).isEqualByComparingTo("12000000");
        assertThat(dec(revenue.get("prevYear"))).isEqualByComparingTo("10000000");
        assertThat(revenue.get("vsPrevMonth").get("text").asText()).isEqualTo("8,000,000원 증가 (+66.7%)");
        assertThat(dec(revenue.get("vsPrevYear").get("pct"))).isEqualByComparingTo("100.0");
        JsonNode expense = find(r.get("totals"), "key", "EXPENSE");
        assertThat(dec(expense.get("current"))).isEqualByComparingTo("10000000");
        assertThat(dec(expense.get("prevMonth"))).isEqualByComparingTo("4000000");
        assertThat(dec(expense.get("prevYear"))).isEqualByComparingTo("3000000");
        JsonNode profit = find(r.get("totals"), "key", "OPERATING_PROFIT");
        assertThat(dec(profit.get("current"))).isEqualByComparingTo("10000000");
        assertThat(profit.get("vsPrevMonth").get("text").asText()).isEqualTo("2,000,000원 증가 (+25.0%)");

        // --- 계정과목: 수익 먼저, 코드 순. 취소 전표만 있는 접대비는 나오지 않는다
        List<String> accountKeys = new ArrayList<>();
        r.get("accounts").forEach(a -> accountKeys.add(a.get("key").asText()));
        assertThat(accountKeys).containsExactly("4010", "5100", "5400", "5500");
        JsonNode rent = find(r.get("accounts"), "key", "5500");
        assertThat(dec(rent.get("prevMonth"))).isEqualByComparingTo("0");
        assertThat(rent.get("vsPrevMonth").get("pct").isNull()).isTrue();

        // --- 거래처: "가상 광고"(8월)와 "(주)가상광고"(9월)는 같은 거래처, 거래 없는 전표는 미상
        JsonNode ad = find(r.get("vendors"), "label", "(주)가상광고");
        assertThat(dec(ad.get("current"))).isEqualByComparingTo("5000000");
        assertThat(dec(ad.get("prevMonth"))).isEqualByComparingTo("1000000");
        JsonNode unknown = find(r.get("vendors"), "label", "(거래처 미상)");
        assertThat(dec(unknown.get("current"))).isEqualByComparingTo("5000000");
        assertThat(dec(unknown.get("prevMonth"))).isEqualByComparingTo("3000000");

        // --- 주요 변동: 상위 10개, 증감액 큰 순
        JsonNode variances = r.get("variances");
        assertThat(variances).hasSize(10);
        assertThat(dec(variances.get(0).get("diff"))).isEqualByComparingTo("10000000");
        assertThat(variances.get(0).get("basis").asText()).isEqualTo("PREV_YEAR");
        JsonNode adMonth = null;
        JsonNode adYear = null;
        List<String> variedKeys = new ArrayList<>();
        for (JsonNode v : variances) {
            variedKeys.add(v.get("key").asText());
            if (v.get("key").asText().equals("5400")) {
                if (v.get("basis").asText().equals("PREV_MONTH")) {
                    adMonth = v;
                } else {
                    adYear = v;
                }
            }
        }
        assertThat(adMonth).isNotNull();
        assertThat(adMonth.get("code").asText()).isEqualTo("INCREASE");
        assertThat(adMonth.get("message").asText())
                .isEqualTo("5400 광고선전비: 전월 1,000,000원 → 당월 5,000,000원, 4,000,000원 증가 (+400.0%)");
        assertThat(adYear).isNotNull();
        assertThat(adYear.get("code").asText()).isEqualTo("NEW");
        // 증감액 10M·8M·7M·6M·5M·4M 순으로 10개가 차서, 임차료 신규(2M)는 상위 10개에 들지 않는다
        assertThat(variedKeys).doesNotContain("5500");

        // --- 체크리스트
        JsonNode checklist = r.get("checklist");
        assertThat(find(checklist, "code", "DOCUMENTS_REVIEWED").get("remaining").asLong()).isEqualTo(1);
        assertThat(find(checklist, "code", "DOCUMENTS_POSTED").get("done").asBoolean()).isTrue();
        JsonNode txPosted = find(checklist, "code", "TRANSACTIONS_POSTED");
        assertThat(txPosted.get("done").asBoolean()).isFalse();
        assertThat(txPosted.get("remaining").asLong()).isEqualTo(1);
        assertThat(txPosted.get("href").asText()).isEqualTo("transactions");
        JsonNode bank = find(checklist, "code", "BANK_RECONCILED");
        assertThat(bank.get("remaining").asLong()).isEqualTo(2);
        assertThat(bank.get("detail").asText()).isEqualTo("미대사 은행 거래 (전체 2건) 2건");
        assertThat(find(checklist, "code", "ANOMALY_SCANNED").get("done").asBoolean()).isFalse();
        assertThat(find(checklist, "code", "ANOMALIES_REVIEWED").get("done").asBoolean()).isTrue();
        assertThat(r.get("progress").get("done").asInt()).isEqualTo(2);
        assertThat(r.get("progress").get("total").asInt()).isEqualTo(6);
        assertThat(r.get("progress").get("percent").asInt()).isEqualTo(33);

        assertThat(r.get("anomalies").get("open").asLong()).isZero();
        assertThat(r.get("closing").get("status").asText()).isEqualTo("OPEN");
        assertThat(r.get("closing").get("id").isNull()).isTrue();
        assertThat(r.get("explanation").isNull()).isTrue();

        // --- 월별 추이
        JsonNode trend = call(HttpMethod.GET, base + "/closing/trend?to=2026-09&months=13", admin.token(), null, 200);
        assertThat(trend).hasSize(13);
        assertThat(trend.get(0).get("month").asText()).isEqualTo("2025-09");
        assertThat(dec(trend.get(0).get("revenue"))).isEqualByComparingTo("10000000");
        assertThat(dec(trend.get(0).get("operatingProfit"))).isEqualByComparingTo("7000000");
        assertThat(dec(trend.get(1).get("revenue"))).isEqualByComparingTo("0");
        assertThat(trend.get(12).get("month").asText()).isEqualTo("2026-09");
        assertThat(dec(trend.get(12).get("expense"))).isEqualByComparingTo("10000000");
        assertThat(trend.get(12).get("status").asText()).isEqualTo("OPEN");
        // 최대 24개월
        assertThat(call(HttpMethod.GET, base + "/closing/trend?to=2026-09&months=100", admin.token(), null, 200))
                .hasSize(24);

        // --- 잘못된 월
        call(HttpMethod.GET, base + "/closing/2026-13", admin.token(), null, 422);
        call(HttpMethod.GET, base + "/closing/abc", admin.token(), null, 422);
        call(HttpMethod.GET, base + "/closing/2099-01", admin.token(), null, 422);
    }

    @Test
    @SuppressWarnings("unchecked")
    void LLM_설명_요청_재생성_수정() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        long cid = Long.parseLong(base.substring(base.lastIndexOf('/') + 1));
        Map<String, Long> acc = books(admin, base, cid);

        // --- 사람이 요청하면 계산된 근거와 함께 큐에 들어간다
        int before = llmQueue.jobs.size();
        JsonNode requested = call(HttpMethod.POST, base + "/closing/2026-09/explanation", admin.token(), null, 200);
        assertThat(requested.get("explanation").get("status").asText()).isEqualTo("PENDING");
        assertThat(requested.get("closing").get("id").isNull()).isFalse();
        List<LlmJobQueue.LlmJob> jobs = new ArrayList<>(llmQueue.jobs.subList(before, llmQueue.jobs.size()));
        assertThat(jobs).hasSize(1);
        LlmJobQueue.LlmJob job = jobs.get(0);
        assertThat(job.targetType()).isEqualTo("CLOSING_MONTH");
        assertThat(job.facts().get("기준월")).isEqualTo("2026-09");
        Map<String, Object> revenue = ((List<Map<String, Object>>) job.facts().get("손익 요약")).get(0);
        assertThat(revenue.get("당월")).isEqualTo("20,000,000원");
        assertThat(revenue.get("전월 대비")).isEqualTo("8,000,000원 증가 (+66.7%)");
        // 원본 전표·거래 내역은 보내지 않는다
        assertThat(job.facts()).doesNotContainKeys("전표", "거래");

        // --- 같은 근거로 다시 요청해도 새로 만들지 않고, 처리 중에는 다시 생성할 수 없다
        call(HttpMethod.POST, base + "/closing/2026-09/explanation", admin.token(), null, 200);
        assertThat(llmQueue.jobs.size()).isEqualTo(before + 1);
        call(HttpMethod.POST, base + "/closing/2026-09/explanation?force=true", admin.token(), null, 409);

        // --- 콜백 반영 → GENERATED
        String text = "9월 매출은 20,000,000원으로 전월보다 8,000,000원 증가했습니다.";
        callback(job.explanationId(), 1, text);
        JsonNode generated = call(HttpMethod.GET, base + "/closing/2026-09", admin.token(), null, 200).get("explanation");
        assertThat(generated.get("status").asText()).isEqualTo("GENERATED");
        assertThat(generated.get("text").asText()).isEqualTo(text);

        // --- 다시 생성 (Audit)
        JsonNode regenerated = call(HttpMethod.POST, base + "/closing/2026-09/explanation?force=true", admin.token(),
                null, 200).get("explanation");
        assertThat(regenerated.get("status").asText()).isEqualTo("PENDING");
        assertThat(regenerated.get("attempt").asInt()).isEqualTo(2);
        JsonNode regenLog = lastAudit(admin, base, "CLOSING_EXPLANATION_REGENERATE");
        assertThat(regenLog.get("targetType").asText()).isEqualTo("CLOSING_MONTH");
        assertThat(regenLog.get("before").get("text").asText()).isEqualTo(text);
        callback(job.explanationId(), 2, text);

        // --- 사람의 수정 (Audit)
        JsonNode edited = call(HttpMethod.PUT, base + "/closing/2026-09/explanation", admin.token(),
                map("text", " 광고비 증가는 9월 캠페인 영향. ", "checkpoints", List.of("캠페인 계약서 확인")), 200)
                .get("explanation");
        assertThat(edited.get("status").asText()).isEqualTo("EDITED");
        assertThat(edited.get("text").asText()).isEqualTo("광고비 증가는 9월 캠페인 영향.");
        JsonNode editLog = lastAudit(admin, base, "CLOSING_EXPLANATION_EDIT");
        assertThat(editLog.get("before").get("status").asText()).isEqualTo("GENERATED");
        assertThat(editLog.get("after").get("status").asText()).isEqualTo("EDITED");
        call(HttpMethod.PUT, base + "/closing/2026-09/explanation", admin.token(), map("text", " "), 400);

        // --- 숫자가 바뀐 뒤 요청: 수정한 설명은 그대로 두고 stale 표시
        entry(admin, base, acc, "2026-09-28", "1020", "4020", "1000000", null);
        before = llmQueue.jobs.size();
        JsonNode stale = call(HttpMethod.POST, base + "/closing/2026-09/explanation", admin.token(), null, 200)
                .get("explanation");
        assertThat(llmQueue.jobs.size()).isEqualTo(before);
        assertThat(stale.get("status").asText()).isEqualTo("EDITED");
        assertThat(stale.get("meta").get("stale").asBoolean()).isTrue();
        // force면 수정한 설명도 새로 만든다
        call(HttpMethod.POST, base + "/closing/2026-09/explanation?force=true", admin.token(), null, 200);
        assertThat(llmQueue.jobs.size()).isEqualTo(before + 1);
    }

    @Test
    void 마감_확정_변경_표시_취소() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        long cid = Long.parseLong(base.substring(base.lastIndexOf('/') + 1));
        Map<String, Long> acc = books(admin, base, cid);

        // 이번 달은 아직 마감할 수 없다
        String thisMonth = YearMonth.now(ZoneId.of("Asia/Seoul")).toString();
        call(HttpMethod.POST, base + "/closing/" + thisMonth + "/close", admin.token(), map("note", "마감"), 409);
        call(HttpMethod.POST, base + "/closing/2026-09/close", admin.token(), map("note", " "), 400);
        call(HttpMethod.POST, base + "/closing/2026-09/reopen", admin.token(), map("note", "취소"), 409);

        // --- 체크리스트가 남아 있어도 확정할 수 있고, 남은 항목이 스냅샷·Audit에 남는다
        JsonNode closed = call(HttpMethod.POST, base + "/closing/2026-09/close", admin.token(),
                map("note", "  은행 대사 2건은 10월에 처리  "), 200).get("closing");
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("closedBy").asLong()).isEqualTo(admin.userId());
        assertThat(closed.get("note").asText()).isEqualTo("은행 대사 2건은 10월에 처리");
        assertThat(closed.get("changedSinceClose").asBoolean()).isFalse();
        assertThat(closed.get("snapshot").get("totals").get("REVENUE").asText()).isEqualTo("20000000.00");
        assertThat(closed.get("snapshot").get("progress").get("done").asInt()).isEqualTo(2);
        JsonNode closeLog = lastAudit(admin, base, "CLOSING_CLOSE");
        assertThat(closeLog.get("before").get("status").asText()).isEqualTo("OPEN");
        List<String> pending = new ArrayList<>();
        closeLog.get("after").get("pendingItems").forEach(p -> pending.add(p.asText()));
        assertThat(pending).containsExactlyInAnyOrder("DOCUMENTS_REVIEWED", "TRANSACTIONS_POSTED", "BANK_RECONCILED",
                "ANOMALY_SCANNED");
        call(HttpMethod.POST, base + "/closing/2026-09/close", admin.token(), map("note", "또"), 409);

        JsonNode trend = call(HttpMethod.GET, base + "/closing/trend?to=2026-09&months=2", admin.token(), null, 200);
        assertThat(trend.get(1).get("status").asText()).isEqualTo("CLOSED");
        assertThat(trend.get(0).get("status").asText()).isEqualTo("OPEN");

        // --- 확정 이후 전표가 바뀌면 표시 (기간 잠금은 범위 밖)
        entry(admin, base, acc, "2026-09-29", "5600", "1020", "50000", null);
        JsonNode changed = call(HttpMethod.GET, base + "/closing/2026-09", admin.token(), null, 200).get("closing");
        assertThat(changed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(changed.get("changedSinceClose").asBoolean()).isTrue();

        // --- 마감 취소: 이전 확정 정보는 Audit Log에 남는다
        JsonNode reopened = call(HttpMethod.POST, base + "/closing/2026-09/reopen", admin.token(),
                map("note", "소모품비 누락 반영"), 200).get("closing");
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("snapshot").isNull()).isTrue();
        assertThat(reopened.get("closedBy").isNull()).isTrue();
        JsonNode reopenLog = lastAudit(admin, base, "CLOSING_REOPEN");
        assertThat(reopenLog.get("before").get("note").asText()).isEqualTo("은행 대사 2건은 10월에 처리");
        assertThat(reopenLog.get("before").get("totals").get("EXPENSE").asText()).isEqualTo("10000000.00");
        assertThat(reopenLog.get("after").get("note").asText()).isEqualTo("소모품비 누락 반영");
        call(HttpMethod.POST, base + "/closing/2026-09/reopen", admin.token(), map("note", "또"), 409);

        // 다시 확정할 수 있다
        call(HttpMethod.POST, base + "/closing/2026-09/close", admin.token(), map("note", "재마감"), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closing_months WHERE company_id = ?", Long.class, cid))
                .isEqualTo(1);
    }

    @Test
    void 권한과_회사_격리() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        Login viewer = signupAndLogin();
        Login accountant = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", accountant.email(), "role", "ACCOUNTANT"), 201);

        // VIEWER: 조회만
        call(HttpMethod.GET, base + "/closing/2026-09", viewer.token(), null, 200);
        call(HttpMethod.GET, base + "/closing/trend", viewer.token(), null, 200);
        call(HttpMethod.POST, base + "/closing/2026-09/explanation", viewer.token(), null, 403);
        call(HttpMethod.PUT, base + "/closing/2026-09/explanation", viewer.token(), map("text", "x"), 403);
        call(HttpMethod.POST, base + "/closing/2026-09/close", viewer.token(), map("note", "x"), 403);

        // ACCOUNTANT: 설명은 가능, 마감 확정·취소는 ADMIN만
        call(HttpMethod.POST, base + "/closing/2026-09/explanation", accountant.token(), null, 200);
        call(HttpMethod.POST, base + "/closing/2026-09/close", accountant.token(), map("note", "x"), 403);
        call(HttpMethod.POST, base + "/closing/2026-09/close", admin.token(), map("note", "마감"), 200);
        call(HttpMethod.POST, base + "/closing/2026-09/reopen", accountant.token(), map("note", "x"), 403);

        // 다른 회사: 404
        Login outsider = signupAndLogin();
        newCompany(outsider);
        call(HttpMethod.GET, base + "/closing/2026-09", outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/closing/trend", outsider.token(), null, 404);
        call(HttpMethod.POST, base + "/closing/2026-09/reopen", outsider.token(), map("note", "x"), 404);
        call(HttpMethod.GET, base + "/closing/2026-09", null, null, 401);
    }
}
