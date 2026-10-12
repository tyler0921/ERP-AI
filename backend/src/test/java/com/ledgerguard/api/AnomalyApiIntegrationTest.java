package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.anomaly.infra.AnomalyModelClient;
import com.ledgerguard.explanation.ExplanationService;
import com.ledgerguard.explanation.infra.LlmJobQueue;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * Phase 4 이상거래 탐지: 은행 CSV·거래 → 규칙 + ML(가짜 모델) → Risk Score → 검토·다시 열기·재분석,
 * AI 서비스 장애 시 규칙만으로 계산, 증빙 연결(SQL), 회사 격리·권한. 실제 IsolationForest는 ai-service pytest가 검증한다.
 */
@SpringBootTest(properties = "app.documents.timeout-check-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
class AnomalyApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** 심야(22시 이후) 거래에만 높은 ML 점수를 주는 가짜 모델 */
    static class FakeModel implements AnomalyModelClient {
        final List<List<Sample>> calls = new CopyOnWriteArrayList<>();
        volatile boolean down;

        @Override
        public ModelResult score(List<Sample> samples, int minSamples) {
            calls.add(samples);
            if (down) {
                throw new ModelUnavailableException("AI 서비스(이상탐지 모델)에 연결할 수 없습니다.", null);
            }
            List<ScoredSample> scores = new ArrayList<>();
            for (Sample s : samples) {
                Object hour = s.features().get("transactionHour");
                boolean night = hour instanceof Number n && n.intValue() >= 22;
                scores.add(new ScoredSample(s.id(), night ? 1.0 : 0.0, night ? 0.78 : 0.42,
                        night ? List.of(Map.of("feature", "transactionHour", "value", hour, "median", 11, "zScore", 4.2))
                                : List.of()));
            }
            return new ModelResult("OK", "fake-isolation-forest", null, scores);
        }
    }

    /** LLM 작업을 붙잡아 두는 큐. 실제 LLM worker 대신 테스트가 내부 콜백 API를 호출한다. */
    static class CapturingLlmQueue implements LlmJobQueue {
        final List<LlmJob> jobs = new CopyOnWriteArrayList<>();
        volatile boolean down;

        @Override
        public void enqueue(LlmJob job) {
            if (down) {
                throw new QueueException("down", new RuntimeException());
            }
            jobs.add(job);
        }
    }

    @TestConfiguration
    static class Infra {
        @Bean
        @Primary
        FakeModel fakeModel() {
            return new FakeModel();
        }

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
    FakeModel model;
    @Autowired
    CapturingLlmQueue llmQueue;
    @Autowired
    ExplanationService explanationService;

    private static final String SEPT_FROM = "2026-09-01";
    private static final String SEPT_TO = "2026-09-30";

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

    private Map<String, Long> accountIds(Login login, String base) throws Exception {
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode a : call(HttpMethod.GET, base + "/accounts", login.token(), null, 200)) {
            ids.put(a.get("code").asText(), a.get("id").asLong());
        }
        return ids;
    }

    private String newCompany(Login admin) throws Exception {
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "이상탐지상사-" + UUID.randomUUID()), 201).get("id").asLong();
        return "/api/v1/companies/" + cid;
    }

    private static LocalDate weekday(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * 가상 은행 거래내역: 2026-03~08 평범한 월 지출(거래처 7곳) + 9월 이상 거래.
     * 9월: 승인 기준 직전 분할 결제(가상컨설팅 495만·480만), 중복 결제(가상물산 2일 연속 125.3만), 토요일 심야 결제.
     */
    private static String statement() {
        String[] vendors = {"가상통신", "가상물산", "한빛문구", "가상택배", "네이버광고", "가상렌탈", "가상전기"};
        int[] base = {55000, 1200000, 83000, 120000, 330000, 440000, 210000};
        StringBuilder csv = new StringBuilder("거래일자,거래시간,출금액,입금액,거래처,적요\n");
        for (int month = 3; month <= 9; month++) {
            for (int v = 0; v < vendors.length; v++) {
                LocalDate d = weekday(LocalDate.of(2026, month, 3 + v * 3));
                csv.append(String.format("%s,10:%02d,%d,,%s,월 정기 지출%n", d, v * 5, base[v] + month * 1000, vendors[v]));
            }
        }
        csv.append("2026-09-08,10:00,4950000,,가상컨설팅,자문료\n");
        csv.append("2026-09-08,15:00,4800000,,가상컨설팅,자문료 잔금\n");
        csv.append("2026-09-15,11:00,1253000,,가상물산,매입대금\n");
        csv.append("2026-09-16,11:30,1253000,,가상물산,매입대금\n");
        csv.append("2026-09-12,23:40,45000,,심야주점,\n");
        return csv.toString();
    }

    private long bankSetup(Login admin, String base) throws Exception {
        Map<String, Long> acc = accountIds(admin, base);
        long bankAccountId = call(HttpMethod.POST, base + "/bank-accounts", admin.token(),
                map("name", "운영 계좌", "bankName", "가상은행", "glAccountId", acc.get("1020")), 201).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                statement().getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(base + "/bank-accounts/" + bankAccountId + "/imports").file(file)
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().is(201));
        return bankAccountId;
    }

    private JsonNode scan(Login login, String base, String from, String to, int expected) throws Exception {
        return call(HttpMethod.POST, base + "/anomalies/scan", login.token(), map("from", from, "to", to), expected);
    }

    private Map<String, JsonNode> openByCounterpartyAndAmount(Login login, String base) throws Exception {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode r : call(HttpMethod.GET, base + "/anomalies?size=100", login.token(), null, 200).get("content")) {
            result.put(r.get("counterparty").asText() + ":" + r.get("amount").asText() + ":" + r.get("txDate").asText(), r);
        }
        return result;
    }

    private static List<String> codes(JsonNode result) {
        List<String> codes = new ArrayList<>();
        JsonNode list = result.has("ruleCodes") ? result.get("ruleCodes") : result.get("ruleHits");
        list.forEach(n -> codes.add(n.isTextual() ? n.asText() : n.get("code").asText()));
        return codes;
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 은행_거래_분석부터_검토와_재분석까지() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        bankSetup(admin, base);

        // 분석 전: 기본 설정, 결과 없음
        JsonNode settings = call(HttpMethod.GET, base + "/anomaly-settings", admin.token(), null, 200);
        assertThat(settings.get("saved").asBoolean()).isFalse();
        assertThat(settings.get("approvalLimit").asText()).isEqualTo("5000000.00");
        assertThat(call(HttpMethod.GET, base + "/anomalies/summary", admin.token(), null, 200).get("open").asLong()).isZero();

        // 기간 검증
        scan(admin, base, "2026-09-30", "2026-09-01", 422);
        scan(admin, base, "2025-01-01", "2026-09-30", 422);

        // --- 9월 분석
        int callsBefore = model.calls.size();
        JsonNode result = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(result.get("analyzedCount").asInt()).isEqualTo(12); // 9월 정기 7건 + 이상 5건
        assertThat(result.get("flaggedCount").asInt()).isEqualTo(5);
        assertThat(result.get("mlStatus").asText()).isEqualTo("APPLIED");
        assertThat(result.get("mlInfo").get("BANK_TRANSACTION").get("samples").asInt()).isEqualTo(53); // 3~9월 49 + 5 - 3/3(기준선 180일 밖)
        assertThat(result.get("mlInfo").get("TRANSACTION").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(result.get("summary").get("created").asInt()).isEqualTo(5);
        assertThat(result.get("summary").get("byLevel").get("CRITICAL").asInt()).isEqualTo(2);
        assertThat(result.get("settings").get("approvalLimit").asText()).isEqualTo("5000000.00");
        // 모델에는 과거 기준선을 포함한 전체 모집단을 보내고, 금액은 문자열이다
        assertThat(model.calls).hasSize(callsBefore + 1);
        AnomalyModelClient.Sample sample = model.calls.get(callsBefore).get(0);
        assertThat(sample.id()).startsWith("BANK_TRANSACTION:");
        assertThat(sample.features().get("amount")).isInstanceOf(String.class);

        Map<String, JsonNode> open = openByCounterpartyAndAmount(admin, base);
        assertThat(open).hasSize(5);
        JsonNode big = open.get("가상컨설팅:4950000.00:2026-09-08");
        assertThat(big.get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(big.get("riskScore").asInt()).isEqualTo(100);
        assertThat(codes(big)).contains("SPLIT_PAYMENT", "NEAR_LIMIT", "NEW_VENDOR_HIGH", "MISSING_EVIDENCE");

        JsonNode dup = open.get("가상물산:1253000.00:2026-09-15");
        assertThat(codes(dup)).containsExactly("DUPLICATE_PAYMENT", "MISSING_EVIDENCE");
        assertThat(dup.get("riskScore").asInt()).isEqualTo(60);
        assertThat(dup.get("riskLevel").asText()).isEqualTo("MEDIUM");

        // 심야 거래: 규칙 30점(심야 10 + 증빙 없음 20) + ML 40점 → HIGH
        JsonNode night = open.get("심야주점:45000.00:2026-09-12");
        assertThat(night.get("ruleScore").asInt()).isEqualTo(30);
        assertThat(night.get("riskScore").asInt()).isEqualTo(70);
        assertThat(night.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(night.get("txTime").asText()).isEqualTo("23:40:00");

        // 정렬: 위험도 높은 순
        JsonNode list = call(HttpMethod.GET, base + "/anomalies", admin.token(), null, 200).get("content");
        assertThat(list.get(0).get("riskScore").asInt()).isEqualTo(100);
        assertThat(list.get(list.size() - 1).get("riskScore").asInt()).isEqualTo(60);
        assertThat(call(HttpMethod.GET, base + "/anomalies?level=HIGH", admin.token(), null, 200)
                .get("totalElements").asLong()).isEqualTo(1);

        // 상세: 규칙 근거·ML 근거·특성
        JsonNode detail = call(HttpMethod.GET, base + "/anomalies/" + night.get("id").asLong(), admin.token(), null, 200);
        assertThat(detail.get("mlDetail").get("status").asText()).isEqualTo("APPLIED");
        assertThat(detail.get("mlDetail").get("points").asInt()).isEqualTo(40);
        assertThat(detail.get("mlDetail").get("topFeatures").get(0).get("feature").asText()).isEqualTo("transactionHour");
        assertThat(detail.get("mlScore").asText()).isEqualTo("1.000");
        assertThat(detail.get("features").get("transactionHour").asInt()).isEqualTo(23);
        assertThat(detail.get("features").get("weekday").asInt()).isEqualTo(6);
        JsonNode offHours = detail.get("ruleHits").get(1);
        assertThat(offHours.get("code").asText()).isEqualTo("OFF_HOURS");
        assertThat(offHours.get("message").asText()).contains("주말 심야");
        assertThat(detail.get("context").get("journalEntryIds")).isEmpty();

        JsonNode summary = call(HttpMethod.GET, base + "/anomalies/summary", admin.token(), null, 200);
        assertThat(summary.get("open").asLong()).isEqualTo(5);
        assertThat(summary.get("highRisk").asLong()).isEqualTo(3);
        assertThat(summary.get("openByLevel").get("MEDIUM").asLong()).isEqualTo(2);
        assertThat(summary.get("lastScan").get("id").asLong()).isEqualTo(result.get("id").asLong());

        // --- 검토: 정상 처리 + Risk Level 하향 조정 (의견 필수)
        long dupId = dup.get("id").asLong();
        call(HttpMethod.POST, base + "/anomalies/" + dupId + "/review", admin.token(),
                map("decision", "DISMISSED", "note", " "), 400);
        JsonNode reviewed = call(HttpMethod.POST, base + "/anomalies/" + dupId + "/review", admin.token(),
                map("decision", "DISMISSED", "adjustedLevel", "LOW", "note", "같은 날 두 건의 별도 발주 (발주서 확인)"), 200);
        assertThat(reviewed.get("status").asText()).isEqualTo("DISMISSED");
        assertThat(reviewed.get("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(reviewed.get("effectiveLevel").asText()).isEqualTo("LOW");
        assertThat(reviewed.get("reviewedBy").asLong()).isEqualTo(admin.userId());
        // 이미 판정된 결과는 다시 판정할 수 없다 (다시 열기 먼저)
        call(HttpMethod.POST, base + "/anomalies/" + dupId + "/review", admin.token(),
                map("decision", "CONFIRMED", "note", "x"), 409);

        long bigId = big.get("id").asLong();
        call(HttpMethod.POST, base + "/anomalies/" + bigId + "/review", admin.token(),
                map("decision", "CONFIRMED", "note", "분할 결제 확인, 내부 감사 요청"), 200);

        summary = call(HttpMethod.GET, base + "/anomalies/summary", admin.token(), null, 200);
        assertThat(summary.get("open").asLong()).isEqualTo(3);
        assertThat(summary.get("confirmed").asLong()).isEqualTo(1);
        assertThat(summary.get("dismissed").asLong()).isEqualTo(1);

        // --- 재분석: 사람의 판정은 덮어쓰지 않는다
        JsonNode rescan = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(rescan.get("summary").get("keptReviewed").asInt()).isEqualTo(2);
        assertThat(rescan.get("summary").get("updated").asInt()).isEqualTo(3);
        assertThat(rescan.get("summary").get("created").asInt()).isZero();
        JsonNode afterRescan = call(HttpMethod.GET, base + "/anomalies/" + dupId, admin.token(), null, 200);
        assertThat(afterRescan.get("status").asText()).isEqualTo("DISMISSED");
        assertThat(afterRescan.get("effectiveLevel").asText()).isEqualTo("LOW");

        // --- 다시 열기: 이전 판정은 context.previousReview와 Audit Log에 남는다
        JsonNode reopened = call(HttpMethod.POST, base + "/anomalies/" + dupId + "/reopen", admin.token(),
                map("note", "거래처 확인 결과 동일 발주 중복 지급"), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("adjustedLevel").isNull()).isTrue();
        assertThat(reopened.get("effectiveLevel").asText()).isEqualTo("MEDIUM");
        assertThat(reopened.get("context").get("previousReview").get("status").asText()).isEqualTo("DISMISSED");
        call(HttpMethod.POST, base + "/anomalies/" + dupId + "/reopen", admin.token(), map("note", "x"), 409);

        JsonNode audit = call(HttpMethod.GET, base + "/audit-logs?size=50", admin.token(), null, 200).get("content");
        List<String> actions = new ArrayList<>();
        audit.forEach(a -> actions.add(a.get("action").asText()));
        assertThat(actions).contains("ANOMALY_SCAN", "ANOMALY_REVIEW", "ANOMALY_REOPEN");
        JsonNode reviewLog = null;
        for (JsonNode a : audit) {
            if (a.get("action").asText().equals("ANOMALY_REVIEW") && a.get("targetId").asLong() == dupId) {
                reviewLog = a;
            }
        }
        assertThat(reviewLog).isNotNull();
        assertThat(reviewLog.get("before").get("status").asText()).isEqualTo("OPEN");
        assertThat(reviewLog.get("after").get("adjustedLevel").asText()).isEqualTo("LOW");

        // --- AI 서비스 장애: 규칙 점수만으로 계산하고 상태를 남긴다
        model.down = true;
        try {
            JsonNode degraded = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
            assertThat(degraded.get("mlStatus").asText()).isEqualTo("UNAVAILABLE");
            assertThat(degraded.get("mlInfo").get("BANK_TRANSACTION").get("message").asText()).contains("규칙 점수만");
            // 심야 거래는 ML 40점이 빠져 30점 → 기준 미만이라 해제된다
            assertThat(degraded.get("summary").get("cleared").asInt()).isEqualTo(1);
            JsonNode nightAfter = call(HttpMethod.GET, base + "/anomalies/" + night.get("id").asLong(), admin.token(), null, 200);
            assertThat(nightAfter.get("status").asText()).isEqualTo("CLEARED");
            assertThat(nightAfter.get("riskScore").asInt()).isEqualTo(30);
            assertThat(nightAfter.get("mlDetail").get("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(nightAfter.get("context").get("clearedReason").asText()).contains("기준");
        } finally {
            model.down = false;
        }
        // 다시 정상이 되면 해제된 결과가 다시 열린다
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(call(HttpMethod.GET, base + "/anomalies/" + night.get("id").asLong(), admin.token(), null, 200)
                .get("status").asText()).isEqualTo("OPEN");

        // --- ML 끔: 설정 스냅샷과 함께 DISABLED
        call(HttpMethod.PUT, base + "/anomaly-settings", admin.token(), map("approvalLimit", "5000000",
                "nearLimitRatio", "0.90", "evidenceMinAmount", "30000", "newVendorMinAmount", "1000000",
                "spikeMultiplier", "5", "nightStartHour", 22, "nightEndHour", 6, "mlEnabled", false), 200);
        int calls = model.calls.size();
        JsonNode disabled = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(disabled.get("mlStatus").asText()).isEqualTo("DISABLED");
        assertThat(disabled.get("settings").get("mlEnabled").asBoolean()).isFalse();
        assertThat(model.calls).hasSize(calls);
    }

    @Test
    void 검토_이후_새_근거가_생기면_다시_연다() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        bankSetup(admin, base);
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        JsonNode dup = openByCounterpartyAndAmount(admin, base).get("가상물산:1253000.00:2026-09-16");
        long id = dup.get("id").asLong();
        call(HttpMethod.POST, base + "/anomalies/" + id + "/review", admin.token(),
                map("decision", "DISMISSED", "note", "정상 지급"), 200);

        // 승인 기준을 낮추면 같은 거래에 새 규칙(승인 기준 직전)이 적중한다
        call(HttpMethod.PUT, base + "/anomaly-settings", admin.token(), map("approvalLimit", "1300000",
                "nearLimitRatio", "0.90", "evidenceMinAmount", "30000", "newVendorMinAmount", "1000000",
                "spikeMultiplier", "5", "nightStartHour", 22, "nightEndHour", 6, "mlEnabled", true), 200);
        JsonNode rescan = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(rescan.get("summary").get("reopened").asInt()).isEqualTo(1);

        JsonNode after = call(HttpMethod.GET, base + "/anomalies/" + id, admin.token(), null, 200);
        assertThat(after.get("status").asText()).isEqualTo("OPEN");
        assertThat(codes(after)).contains("NEAR_LIMIT", "DUPLICATE_PAYMENT");
        assertThat(after.get("context").get("previousReview").get("note").asText()).isEqualTo("정상 지급");
        assertThat(after.get("reviewNote").isNull()).isTrue();
    }

    @Test
    void 거래_원천_증빙_연결과_취소() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        Map<String, Long> acc = accountIds(admin, base);
        long cid = Long.parseLong(base.substring(base.lastIndexOf('/') + 1));

        // 같은 거래처·같은 금액 거래 2건 → 중복 결제(40) → MEDIUM. 거래 원천은 표본이 적어 ML을 건너뛴다.
        long tx1 = call(HttpMethod.POST, base + "/transactions", admin.token(), map("txDate", "2026-09-10",
                "counterparty", "가상커피", "amount", "28000", "direction", "OUT"), 201).get("id").asLong();
        long tx2 = call(HttpMethod.POST, base + "/transactions", admin.token(), map("txDate", "2026-09-11",
                "counterparty", "가상커피", "amount", "28000", "direction", "OUT"), 201).get("id").asLong();
        // 입금은 분석 대상이 아니다
        call(HttpMethod.POST, base + "/transactions", admin.token(), map("txDate", "2026-09-11",
                "counterparty", "가상커피", "amount", "28000", "direction", "IN"), 201);

        // tx1에 전표 + 승인된 증빙, 그리고 같은 거래처·날짜·합계의 다른 증빙 → 영수증 중복 등록
        long entryId = call(HttpMethod.POST, base + "/journal-entries", admin.token(), map("entryDate", "2026-09-10",
                "description", "커피", "transactionId", tx1, "lines", List.of(
                        map("accountId", acc.get("5200"), "debit", "28000", "credit", "0"),
                        map("accountId", acc.get("1020"), "debit", "0", "credit", "28000"))), 201).get("id").asLong();
        long doc1 = insertDocument(cid, admin.userId(), "APPROVED", acc.get("5200"), entryId);
        long doc2 = insertDocument(cid, admin.userId(), "REVIEW_REQUIRED", null, null);

        JsonNode result = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(result.get("mlInfo").get("TRANSACTION").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(result.get("mlStatus").asText()).isEqualTo("SKIPPED");

        JsonNode page = call(HttpMethod.GET, base + "/anomalies?sourceType=TRANSACTION", admin.token(), null, 200);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        JsonNode r1 = null;
        JsonNode r2 = null;
        for (JsonNode r : page.get("content")) {
            if (r.get("sourceId").asLong() == tx1) {
                r1 = r;
            } else if (r.get("sourceId").asLong() == tx2) {
                r2 = r;
            }
        }
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        // tx1: 증빙이 있어 증빙 없음 규칙은 없고, 영수증 중복이 추가된다
        assertThat(codes(r1)).containsExactlyInAnyOrder("DUPLICATE_PAYMENT", "DUPLICATE_RECEIPT");
        assertThat(r1.get("riskScore").asInt()).isEqualTo(80);
        JsonNode d1 = call(HttpMethod.GET, base + "/anomalies/" + r1.get("id").asLong(), admin.token(), null, 200);
        assertThat(d1.get("context").get("accountCode").asText()).isEqualTo("5200");
        assertThat(d1.get("context").get("journalEntryIds").get(0).asLong()).isEqualTo(entryId);
        assertThat(d1.get("context").get("documentIds").get(0).asLong()).isEqualTo(doc1);
        assertThat(d1.get("features").get("accountCategory").asText()).isEqualTo("5200");
        assertThat(d1.get("mlDetail").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(d1.get("mlDetail").get("message").asText()).contains("최소 30건");
        JsonNode receiptHit = null;
        for (JsonNode h : d1.get("ruleHits")) {
            if (h.get("code").asText().equals("DUPLICATE_RECEIPT")) {
                receiptHit = h;
            }
        }
        assertThat(receiptHit).isNotNull();
        assertThat(receiptHit.get("evidence").get("duplicateDocumentIds").get(0).asLong()).isEqualTo(doc2);
        // tx2: 전표 없음 → 증빙 없음 규칙 (2만 8천원은 3만원 기준 이하라 해당 없음)
        assertThat(codes(r2)).containsExactly("DUPLICATE_PAYMENT");

        // tx2 취소 → 재분석: tx2 결과는 원천이 빠져 해제, tx1은 중복 결제가 사라져 40점(영수증 중복)으로 유지
        call(HttpMethod.POST, base + "/transactions/" + tx2 + "/void", admin.token(), map("reason", "중복 입력"), 200);
        JsonNode rescan = scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(rescan.get("summary").get("cleared").asInt()).isEqualTo(1);
        JsonNode cleared = call(HttpMethod.GET, base + "/anomalies/" + r2.get("id").asLong(), admin.token(), null, 200);
        assertThat(cleared.get("status").asText()).isEqualTo("CLEARED");
        assertThat(cleared.get("context").get("clearedReason").asText()).contains("취소");
        JsonNode still = call(HttpMethod.GET, base + "/anomalies/" + r1.get("id").asLong(), admin.token(), null, 200);
        assertThat(still.get("status").asText()).isEqualTo("OPEN");
        assertThat(still.get("riskScore").asInt()).isEqualTo(40);
        assertThat(codes(still)).containsExactly("DUPLICATE_RECEIPT");
    }

    private long insertDocument(long companyId, long userId, String status, Long accountId, Long entryId) {
        return jdbc.queryForObject("""
                INSERT INTO documents (company_id, doc_type, status, file_key, file_name, content_type, file_size,
                                       file_sha256, vendor_name, vendor_business_no, issue_date, total_amount,
                                       account_id, journal_entry_id, uploaded_by)
                VALUES (?, 'RECEIPT', ?, ?, 'r.png', 'image/png', 10, ?, '가상커피', '211-88-55554', DATE '2026-09-10',
                        28000, ?, ?, ?)
                RETURNING id
                """, Long.class, companyId, status, "companies/" + companyId + "/documents/" + UUID.randomUUID() + ".png",
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                accountId, entryId, userId);
    }

    @Test
    void 권한과_회사_격리() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        bankSetup(admin, base);
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        long resultId = call(HttpMethod.GET, base + "/anomalies", admin.token(), null, 200)
                .get("content").get(0).get("id").asLong();

        Login viewer = signupAndLogin();
        Login accountant = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", accountant.email(), "role", "ACCOUNTANT"), 201);

        // VIEWER: 조회만
        call(HttpMethod.GET, base + "/anomalies", viewer.token(), null, 200);
        call(HttpMethod.GET, base + "/anomalies/" + resultId, viewer.token(), null, 200);
        call(HttpMethod.GET, base + "/anomaly-settings", viewer.token(), null, 200);
        scan(viewer, base, SEPT_FROM, SEPT_TO, 403);
        call(HttpMethod.POST, base + "/anomalies/" + resultId + "/review", viewer.token(),
                map("decision", "CONFIRMED", "note", "x"), 403);

        // ACCOUNTANT: 분석·검토 가능, 설정 변경 불가
        scan(accountant, base, SEPT_FROM, SEPT_TO, 200);
        Map<String, Object> settings = map("approvalLimit", "3000000", "nearLimitRatio", "0.95",
                "evidenceMinAmount", "30000", "newVendorMinAmount", "1000000", "spikeMultiplier", "5",
                "nightStartHour", 22, "nightEndHour", 6, "mlEnabled", true);
        call(HttpMethod.PUT, base + "/anomaly-settings", accountant.token(), settings, 403);
        call(HttpMethod.POST, base + "/anomalies/" + resultId + "/review", accountant.token(),
                map("decision", "CONFIRMED", "note", "확인"), 200);

        // 설정 검증
        Map<String, Object> invalid = new LinkedHashMap<>(settings);
        invalid.put("nearLimitRatio", "1.5");
        call(HttpMethod.PUT, base + "/anomaly-settings", admin.token(), invalid, 400);
        JsonNode saved = call(HttpMethod.PUT, base + "/anomaly-settings", admin.token(), settings, 200);
        assertThat(saved.get("saved").asBoolean()).isTrue();
        assertThat(saved.get("approvalLimit").asText()).isEqualTo("3000000.00");
        assertThat(saved.get("nearLimitRatio").asText()).isEqualTo("0.95");

        // 다른 회사: 존재 여부를 숨기기 위해 404
        Login outsider = signupAndLogin();
        String otherBase = newCompany(outsider);
        call(HttpMethod.GET, base + "/anomalies", outsider.token(), null, 404);
        call(HttpMethod.GET, otherBase + "/anomalies/" + resultId, outsider.token(), null, 404);
        call(HttpMethod.POST, otherBase + "/anomalies/" + resultId + "/review", outsider.token(),
                map("decision", "DISMISSED", "note", "x"), 404);
        assertThat(call(HttpMethod.GET, otherBase + "/anomalies/summary", outsider.token(), null, 200)
                .get("open").asLong()).isZero();
    }

    @Test
    void DB_제약_판정된_결과에는_검토자와_의견이_있어야_한다() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        bankSetup(admin, base);
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        long resultId = call(HttpMethod.GET, base + "/anomalies", admin.token(), null, 200)
                .get("content").get(0).get("id").asLong();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE anomaly_results SET status = 'CONFIRMED' WHERE id = ?", resultId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // effective_level은 DB가 계산한다
        jdbc.update("UPDATE anomaly_results SET adjusted_level = 'LOW' WHERE id = ?", resultId);
        assertThat(jdbc.queryForObject("SELECT effective_level FROM anomaly_results WHERE id = ?", String.class, resultId))
                .isEqualTo("LOW");
    }

    // ------------------------------------------------------------------ Phase 5 LLM 설명

    private static final String TOKEN = "dev-internal-token-change-me";

    private JsonNode explanationCallback(long explanationId, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.POST, "/internal/v1/explanations/" + explanationId + "/result")
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        String content = mvc.perform(builder).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(content);
    }

    private static Map<String, Object> llmResult(int attempt, String status, String text) {
        return map("attempt", attempt, "success", true, "status", status, "text", text,
                "checkpoints", List.of("분할 결제 여부 확인"),
                "meta", map("provider", "ollama", "model", "qwen2.5:7b-instruct", "promptVersion", "anomaly-v1",
                        "durationMs", 2100, "validation", List.of()));
    }

    private List<LlmJobQueue.LlmJob> jobsAfter(int index) {
        return new ArrayList<>(llmQueue.jobs.subList(index, llmQueue.jobs.size()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void LLM_설명_요청부터_수정_재생성까지() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        bankSetup(admin, base);

        // --- 분석 직후: OPEN 결과 5건(위험도 높은 순) + 분석 요약 1건의 LLM 작업이 커밋 후 큐에 들어간다
        int before = llmQueue.jobs.size();
        long scanId = scan(admin, base, SEPT_FROM, SEPT_TO, 200).get("id").asLong();
        List<LlmJobQueue.LlmJob> jobs = jobsAfter(before);
        assertThat(jobs).hasSize(6);
        assertThat(jobs.subList(0, 5)).allSatisfy(j -> assertThat(j.targetType()).isEqualTo("ANOMALY_RESULT"));
        assertThat(jobs.get(5).targetType()).isEqualTo("ANOMALY_SCAN");
        Map<String, Object> facts = jobs.get(0).facts();
        assertThat(((Map<String, Object>) facts.get("위험도")).get("위험도 점수")).isEqualTo(100);
        // 금액은 화면과 같은 표시 형식으로 전달한다
        assertThat(((Map<String, Object>) facts.get("거래")).get("출금액")).isIn("4,950,000원", "4,800,000원");
        assertThat(jobs.get(5).facts().get("탐지 건수(40점 이상)")).isEqualTo(5);

        Map<String, JsonNode> open = openByCounterpartyAndAmount(admin, base);
        long nightId = open.get("심야주점:45000.00:2026-09-12").get("id").asLong();
        long dupId = open.get("가상물산:1253000.00:2026-09-15").get("id").asLong();
        JsonNode pending = call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200).get("explanation");
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        assertThat(pending.get("facts").get("거래").get("거래일시").asText()).isEqualTo("2026-09-12 23:40");
        long nightExplanationId = pending.get("id").asLong();

        // --- 콜백: 토큰 확인, 다른 시도 번호는 무시, 한 번만 반영
        explanationCallback(nightExplanationId, null, llmResult(1, "GENERATED", "설명"), 401);
        assertThat(explanationCallback(nightExplanationId, TOKEN, llmResult(9, "GENERATED", "설명"), 202)
                .get("status").asText()).isEqualTo("ignored");
        String text = "심야주점에 대한 450,000원 출금은 토요일 23:40에 이루어졌고 증빙이 없어 확인이 필요합니다.";
        assertThat(explanationCallback(nightExplanationId, TOKEN, llmResult(1, "GENERATED", text), 202)
                .get("status").asText()).isEqualTo("applied");
        assertThat(explanationCallback(nightExplanationId, TOKEN, llmResult(1, "GENERATED", "다른 설명"), 202)
                .get("status").asText()).isEqualTo("ignored");
        JsonNode generated = call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200).get("explanation");
        assertThat(generated.get("status").asText()).isEqualTo("GENERATED");
        assertThat(generated.get("text").asText()).isEqualTo(text);
        assertThat(generated.get("checkpoints").get(0).asText()).isEqualTo("분할 결제 여부 확인");
        assertThat(generated.get("meta").get("model").asText()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(generated.get("completedAt").isNull()).isFalse();

        // 분석 요약은 템플릿(FALLBACK)으로 와도 반영된다
        long scanExplanationId = jobs.get(5).explanationId();
        explanationCallback(scanExplanationId, TOKEN, llmResult(1, "FALLBACK", "출금 12건을 분석해 5건을 탐지했습니다."), 202);
        JsonNode lastScan = call(HttpMethod.GET, base + "/anomalies/summary", admin.token(), null, 200).get("lastScan");
        assertThat(lastScan.get("id").asLong()).isEqualTo(scanId);
        assertThat(lastScan.get("explanation").get("status").asText()).isEqualTo("FALLBACK");

        // --- 근거가 같으면 재분석해도 다시 만들지 않는다 (새 분석의 요약만 요청)
        before = llmQueue.jobs.size();
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        assertThat(jobsAfter(before)).extracting(LlmJobQueue.LlmJob::targetType).containsExactly("ANOMALY_SCAN");

        // --- 사람의 수정 (Audit)
        JsonNode edited = call(HttpMethod.PUT, base + "/anomalies/" + nightId + "/explanation", admin.token(),
                map("text", "  법인카드 심야 사용. 사용 목적 확인 필요.  ", "checkpoints", List.of("사용자 소명 받기")), 200)
                .get("explanation");
        assertThat(edited.get("status").asText()).isEqualTo("EDITED");
        assertThat(edited.get("text").asText()).isEqualTo("법인카드 심야 사용. 사용 목적 확인 필요.");
        assertThat(edited.get("editedBy").asLong()).isEqualTo(admin.userId());
        assertThat(edited.get("meta").get("editedFrom").asText()).isEqualTo("GENERATED");
        call(HttpMethod.PUT, base + "/anomalies/" + nightId + "/explanation", admin.token(), map("text", " "), 400);
        JsonNode audit = call(HttpMethod.GET, base + "/audit-logs?size=10", admin.token(), null, 200).get("content");
        JsonNode editLog = null;
        for (JsonNode a : audit) {
            if (a.get("action").asText().equals("ANOMALY_EXPLANATION_EDIT")) {
                editLog = a;
            }
        }
        assertThat(editLog).isNotNull();
        assertThat(editLog.get("before").get("text").asText()).isEqualTo(text);
        assertThat(editLog.get("after").get("status").asText()).isEqualTo("EDITED");

        // --- 근거가 바뀌면: 수정한 설명은 그대로 두고 stale 표시, 나머지는 새로 요청
        call(HttpMethod.PUT, base + "/anomaly-settings", admin.token(), map("approvalLimit", "1300000",
                "nearLimitRatio", "0.90", "evidenceMinAmount", "30000", "newVendorMinAmount", "1000000",
                "spikeMultiplier", "5", "nightStartHour", 21, "nightEndHour", 6, "mlEnabled", true), 200);
        before = llmQueue.jobs.size();
        scan(admin, base, SEPT_FROM, SEPT_TO, 200);
        List<Long> requested = jobsAfter(before).stream().map(LlmJobQueue.LlmJob::explanationId).toList();
        JsonNode stale = call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200).get("explanation");
        assertThat(stale.get("status").asText()).isEqualTo("EDITED");
        assertThat(stale.get("meta").get("stale").asBoolean()).isTrue();
        assertThat(requested).doesNotContain(stale.get("id").asLong());
        JsonNode dupExplanation = call(HttpMethod.GET, base + "/anomalies/" + dupId, admin.token(), null, 200).get("explanation");
        assertThat(dupExplanation.get("status").asText()).isEqualTo("PENDING");
        assertThat(dupExplanation.get("attempt").asInt()).isEqualTo(2);
        assertThat(requested).contains(dupExplanation.get("id").asLong());

        // --- 다시 생성: 수정한 설명도 바뀌고, 처리 중에는 다시 요청할 수 없다
        JsonNode regenerated = call(HttpMethod.POST, base + "/anomalies/" + nightId + "/explanation/regenerate",
                admin.token(), null, 200).get("explanation");
        assertThat(regenerated.get("status").asText()).isEqualTo("PENDING");
        assertThat(regenerated.get("attempt").asInt()).isEqualTo(2);
        assertThat(regenerated.get("text").isNull()).isTrue();
        call(HttpMethod.POST, base + "/anomalies/" + nightId + "/explanation/regenerate", admin.token(), null, 409);

        // --- 시간 초과 → FAILED, 늦게 도착한 같은 시도의 결과는 반영
        explanationService.failTimedOut(Instant.now().plusSeconds(1));
        JsonNode failed = call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200).get("explanation");
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("meta").get("error").asText()).contains("시간이 초과");
        explanationCallback(nightExplanationId, TOKEN, llmResult(2, "GENERATED", text), 202);
        assertThat(call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200)
                .get("explanation").get("status").asText()).isEqualTo("GENERATED");

        // --- 작업 큐 장애: 요청은 성공, 설명만 FAILED (다시 생성 가능)
        llmQueue.down = true;
        try {
            call(HttpMethod.POST, base + "/anomalies/" + nightId + "/explanation/regenerate", admin.token(), null, 200);
        } finally {
            llmQueue.down = false;
        }
        JsonNode queueFailed = call(HttpMethod.GET, base + "/anomalies/" + nightId, admin.token(), null, 200).get("explanation");
        assertThat(queueFailed.get("status").asText()).isEqualTo("FAILED");
        assertThat(queueFailed.get("meta").get("error").asText()).contains("Valkey");

        // --- 분석 요약 다시 생성 (저장된 근거를 그대로 사용)
        JsonNode summaryRegen = call(HttpMethod.POST, base + "/anomalies/scans/" + scanId + "/summary/regenerate",
                admin.token(), null, 200);
        assertThat(summaryRegen.get("explanation").get("status").asText()).isEqualTo("PENDING");
        assertThat(summaryRegen.get("explanation").get("attempt").asInt()).isEqualTo(2);

        // --- 권한: VIEWER는 조회만, 다른 회사의 분석은 404
        Login viewer = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);
        call(HttpMethod.GET, base + "/anomalies/" + nightId, viewer.token(), null, 200);
        call(HttpMethod.PUT, base + "/anomalies/" + nightId + "/explanation", viewer.token(), map("text", "x"), 403);
        call(HttpMethod.POST, base + "/anomalies/" + nightId + "/explanation/regenerate", viewer.token(), null, 403);
        Login outsider = signupAndLogin();
        String otherBase = newCompany(outsider);
        call(HttpMethod.POST, otherBase + "/anomalies/scans/" + scanId + "/summary/regenerate", outsider.token(), null, 404);
    }
}
