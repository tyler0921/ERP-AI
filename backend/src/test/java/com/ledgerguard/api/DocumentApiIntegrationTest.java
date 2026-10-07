package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.document.DocumentService;
import com.ledgerguard.document.infra.FileStorage;
import com.ledgerguard.document.infra.OcrJobQueue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 3 Document AI: 업로드 → (AI 콜백 시뮬레이션) → 검증·추천 → 수정·승인 → 전표 생성, 실패·재시도·멱등성.
 * 파일 저장소와 작업 큐는 메모리 구현으로 바꾸고, AI worker는 내부 콜백 API 호출로 흉내 낸다.
 * (실제 OCR은 ai-service의 pytest가 검증한다)
 */
@SpringBootTest(properties = "app.documents.timeout-check-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
class DocumentApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String TOKEN = "dev-internal-token-change-me";

    static class MemoryStorage implements FileStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] content, String contentType) {
            files.put(key, content);
        }

        @Override
        public byte[] get(String key) {
            return files.get(key);
        }
    }

    static class CapturingQueue implements OcrJobQueue {
        final List<OcrJob> jobs = new CopyOnWriteArrayList<>();
        volatile boolean down;

        @Override
        public void enqueue(OcrJob job) {
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
        MemoryStorage memoryStorage() {
            return new MemoryStorage();
        }

        @Bean
        @Primary
        CapturingQueue capturingQueue() {
            return new CapturingQueue();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingQueue queue;
    @Autowired
    DocumentService documentService;

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

    private JsonNode callback(long documentId, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.POST, "/internal/v1/documents/" + documentId + "/ai-result")
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        String content = mvc.perform(builder).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(content);
    }

    private JsonNode upload(String base, String token, String docType, String fileName, byte[] content, int expected)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "application/octet-stream", content);
        String body = mvc.perform(multipart(base + "/documents").file(file).param("docType", docType)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body);
    }

    /** PNG 매직 바이트 + 고유 내용 (파일 해시가 매번 다르도록) */
    private static byte[] png(String seed) {
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        byte[] tail = seed.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[magic.length + tail.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(tail, 0, out, magic.length, tail.length);
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> field(Object value, double confidence) {
        return map("value", value, "confidence", confidence, "source", "라벨 일치");
    }

    /** AI worker가 보내는 성공 결과 */
    private static Map<String, Object> aiResult(int attempt, Map<String, Object> fields, double overall, String suggestedCode) {
        Map<String, Object> result = map(
                "fields", fields,
                "overallConfidence", overall,
                "accountSuggestions", suggestedCode == null ? List.of()
                        : List.of(map("code", suggestedCode, "name", "추천", "confidence", 0.65, "reasons", List.of("거래처명에 '커피'"))),
                "ocr", map("engine", "paddleocr", "model", "test", "durationMs", 1200, "pages", 1,
                        "lines", List.of(map("text", "합계금액 18,000", "score", 0.97, "page", 0))));
        return map("attempt", attempt, "success", true, "result", result);
    }

    private static Map<String, Object> receiptFields(String date, String total) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("vendorName", field("가상커피 강남점", 0.97));
        f.put("vendorBusinessNo", field("211-88-55554", 0.96));
        f.put("issueDate", field(date, 0.95));
        f.put("supplyAmount", field("16364.00", 0.95));
        f.put("vatAmount", field("1636.00", 0.95));
        f.put("totalAmount", field(total, 0.98));
        f.put("paymentMethod", field("CARD", 0.9));
        f.put("itemsSummary", field("아메리카노, 카페라떼 외 1건", 0.57));
        return f;
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

    private OcrJobQueue.OcrJob lastJobFor(long documentId) {
        return queue.jobs.stream().filter(j -> j.documentId() == documentId).reduce((a, b) -> b).orElseThrow();
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 영수증_업로드부터_전표_생성까지() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "증빙상사-" + UUID.randomUUID(), "businessNo", "123-45-67891"), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;
        Map<String, Long> acc = accountIds(admin, base);

        // --- 업로드: 저장 후 커밋되면 큐에 작업이 들어간다
        byte[] content = png("receipt-1-" + UUID.randomUUID());
        JsonNode doc = upload(base, admin.token(), "RECEIPT", "../../etc/receipt.png", content, 201);
        long id = doc.get("id").asLong();
        assertThat(doc.get("status").asText()).isEqualTo("PENDING");
        assertThat(doc.get("fileName").asText()).isEqualTo("receipt.png"); // 경로 제거
        OcrJobQueue.OcrJob job = lastJobFor(id);
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.fileKey()).matches("companies/" + cid + "/documents/[0-9a-f-]{36}\\.png");

        // 같은 파일은 다시 올릴 수 없고, 이미지/PDF가 아니면 거부
        JsonNode dup = upload(base, admin.token(), "RECEIPT", "again.png", content, 409);
        assertThat(dup.get("code").asText()).isEqualTo("DOCUMENT_DUPLICATE");
        upload(base, admin.token(), "RECEIPT", "fake.png", "<script>".getBytes(StandardCharsets.UTF_8), 422);

        // --- 내부 콜백: 토큰이 없거나 틀리면 거부, 다른 시도 번호의 결과는 무시
        callback(id, null, aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, "5200"), 401);
        callback(id, "wrong", aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, "5200"), 401);
        assertThat(callback(id, TOKEN, aiResult(9, receiptFields("2026-10-07", "18000.00"), 0.95, "5200"), 202)
                .get("status").asText()).isEqualTo("ignored");

        JsonNode applied = callback(id, TOKEN, aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, "5200"), 202);
        assertThat(applied.get("status").asText()).isEqualTo("applied");
        // 같은 결과가 다시 와도 한 번만 반영된다
        assertThat(callback(id, TOKEN, aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, "5200"), 202)
                .get("status").asText()).isEqualTo("ignored");

        JsonNode reviewed = call(HttpMethod.GET, base + "/documents/" + id, admin.token(), null, 200);
        // 품목 confidence 0.57 → 낮은 신뢰도 경고로 검토 필요
        assertThat(reviewed.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reviewed.get("validation").toString()).contains("LOW_CONFIDENCE").doesNotContain("ERROR");
        assertThat(reviewed.get("totalAmount").asText()).isEqualTo("18000.00");
        assertThat(reviewed.get("aiFields").get("vendorName").get("confidence").asDouble()).isEqualTo(0.97);
        assertThat(reviewed.get("ocrLines").get(0).get("text").asText()).isEqualTo("합계금액 18,000");
        assertThat(reviewed.get("recommendedAccountId").asLong()).isEqualTo(acc.get("5200"));
        assertThat(reviewed.get("recommendation").get(0).get("source").asText()).isEqualTo("RULE");

        // --- 원본 파일은 권한 확인 후 저장된 형식으로만 내려준다
        MockHttpServletResponse file = mvc.perform(request(HttpMethod.GET, base + "/documents/" + id + "/file")
                .header("Authorization", "Bearer " + admin.token())).andReturn().getResponse();
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).isEqualTo("image/png");
        assertThat(file.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(file.getContentAsByteArray()).isEqualTo(content);

        // --- 사람이 품목을 고치면 재검증 (사람이 확인한 값에는 신뢰도 경고 없음) → AI_REVIEWED
        Map<String, Object> fields = map("vendorName", "가상커피 강남점", "vendorBusinessNo", "2118855554",
                "issueDate", "2026-10-07", "supplyAmount", "16364", "vatAmount", "1636", "totalAmount", "18000",
                "paymentMethod", "CARD", "itemsSummary", "아메리카노 2, 카페라떼, 베이글");
        JsonNode edited = call(HttpMethod.PUT, base + "/documents/" + id + "/fields", admin.token(), fields, 200);
        assertThat(edited.get("status").asText()).isEqualTo("AI_REVIEWED");
        assertThat(edited.get("vendorBusinessNo").asText()).isEqualTo("211-88-55554");

        // --- 승인 → 전표 생성 (차변 복리후생비 공급가액 + 부가세대급금 / 대변 미지급금 합계)
        call(HttpMethod.POST, base + "/documents/" + id + "/journal-entry", admin.token(),
                map("creditAccountId", acc.get("2020")), 409); // 승인 전
        JsonNode approved = call(HttpMethod.POST, base + "/documents/" + id + "/approve", admin.token(),
                map("accountId", acc.get("5200"), "note", "회의 다과"), 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        call(HttpMethod.PUT, base + "/documents/" + id + "/fields", admin.token(), fields, 409); // 승인 후 수정 불가

        JsonNode entry = call(HttpMethod.POST, base + "/documents/" + id + "/journal-entry", admin.token(),
                map("creditAccountId", acc.get("2020")), 201);
        assertThat(entry.get("entryDate").asText()).isEqualTo("2026-10-07");
        assertThat(entry.get("totalDebit").asText()).isEqualTo("18000.00");
        Map<String, String> lines = new HashMap<>();
        entry.get("lines").forEach(l -> lines.put(l.get("accountCode").asText(),
                l.get("debit").asText() + "/" + l.get("credit").asText()));
        assertThat(lines).containsEntry("5200", "16364.00/0.00").containsEntry("1350", "1636.00/0.00")
                .containsEntry("2020", "0.00/18000.00");
        JsonNode linked = call(HttpMethod.POST, base + "/documents/" + id + "/journal-entry", admin.token(),
                map("creditAccountId", acc.get("2020")), 409);
        assertThat(linked.get("code").asText()).isEqualTo("DOCUMENT_ALREADY_LINKED");

        // --- 같은 거래처의 다음 영수증: 과거 승인 이력이 키워드 규칙보다 우선, 같은 날·같은 금액이면 중복 의심
        long id2 = upload(base, admin.token(), "RECEIPT", "r2.png", png("receipt-2-" + UUID.randomUUID()), 201)
                .get("id").asLong();
        callback(id2, TOKEN, aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, "5400"), 202);
        JsonNode second = call(HttpMethod.GET, base + "/documents/" + id2, admin.token(), null, 200);
        assertThat(second.get("recommendedAccountId").asLong()).isEqualTo(acc.get("5200"));
        assertThat(second.get("recommendation").get(0).get("source").asText()).isEqualTo("HISTORY");
        assertThat(second.get("recommendation").get(1).get("code").asText()).isEqualTo("5400");
        assertThat(second.get("validation").toString()).contains("POSSIBLE_DUPLICATE").contains("#" + id);

        // --- Audit Log
        Map<String, Integer> actions = new HashMap<>();
        jdbc.query("select action, count(*) c from audit_logs where company_id = ? group by action",
                rs -> {
                    actions.put(rs.getString("action"), rs.getInt("c"));
                }, cid);
        assertThat(actions).containsEntry("DOC_UPLOAD", 2).containsEntry("DOC_AI_RESULT", 2)
                .containsEntry("DOC_UPDATE", 1).containsEntry("DOC_APPROVE", 1).containsEntry("DOC_JOURNAL_LINK", 1)
                .containsEntry("JE_CREATE", 1);
        String approveAudit = jdbc.queryForObject("select after_value::text from audit_logs where company_id = ? "
                + "and action = 'DOC_APPROVE'", String.class, cid);
        assertThat(approveAudit).contains("\"followedRecommendation\": true");
    }

    @Test
    void 세금계산서_합계_불일치는_승인을_막고_수정하면_승인된다() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "세금상사-" + UUID.randomUUID(), "businessNo", "123-45-67890"), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;
        long expense = accountIds(admin, base).get("5700");

        long id = upload(base, admin.token(), "TAX_INVOICE", "invoice.pdf",
                ("%PDF-1.7 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), 201).get("id").asLong();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("vendorName", field("(주)가상소프트", 0.95));
        f.put("vendorBusinessNo", field("314-86-12340", 0.95));
        f.put("buyerName", field("가상상사", 0.95));
        f.put("buyerBusinessNo", field("123-45-67891", 0.95));
        f.put("issueDate", field("2026-10-05", 0.95));
        f.put("supplyAmount", field("3000000.00", 0.95));
        f.put("vatAmount", field("300000.00", 0.95));
        f.put("totalAmount", field("3200000.00", 0.95)); // 오인식
        callback(id, TOKEN, aiResult(1, f, 0.95, null), 202);

        JsonNode doc = call(HttpMethod.GET, base + "/documents/" + id, admin.token(), null, 200);
        assertThat(doc.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(doc.get("contentType").asText()).isEqualTo("application/pdf");
        String validation = doc.get("validation").toString();
        assertThat(validation).contains("SUM_MISMATCH").contains("BUYER_MISMATCH");

        JsonNode blocked = call(HttpMethod.POST, base + "/documents/" + id + "/approve", admin.token(),
                map("accountId", expense), 422);
        assertThat(blocked.get("code").asText()).isEqualTo("DOCUMENT_VALIDATION_FAILED");
        assertThat(blocked.get("details").get(0).get("field").asText()).isEqualTo("totalAmount");

        Map<String, Object> fixed = map("vendorName", "(주)가상소프트", "vendorBusinessNo", "314-86-12340",
                "buyerName", "가상상사", "buyerBusinessNo", "123-45-67891", "issueDate", "2026-10-05",
                "supplyAmount", "3000000", "vatAmount", "300000", "totalAmount", "3300000");
        JsonNode edited = call(HttpMethod.PUT, base + "/documents/" + id + "/fields", admin.token(), fixed, 200);
        // 경고(공급받는자 불일치)는 남지만 승인을 막지는 않는다
        assertThat(edited.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(edited.get("validation").toString()).doesNotContain("SUM_MISMATCH").contains("BUYER_MISMATCH");
        call(HttpMethod.POST, base + "/documents/" + id + "/approve", admin.token(), map("accountId", expense), 200);

        String audit = jdbc.queryForObject("select before_value::text || after_value::text from audit_logs "
                + "where company_id = ? and action = 'DOC_UPDATE'", String.class, cid);
        assertThat(audit).contains("3200000.00").contains("3300000.00"); // 무엇을 어떤 값에서 어떤 값으로
    }

    @Test
    void AI_실패_재시도_큐_장애_시간_초과() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "실패상사-" + UUID.randomUUID()), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;

        // OCR 실패 → FAILED → 재시도하면 attempt 2로 다시 큐에
        long id = upload(base, admin.token(), "RECEIPT", "blurry.png", png("blurry-" + UUID.randomUUID()), 201)
                .get("id").asLong();
        callback(id, TOKEN, map("attempt", 1, "success", false, "error", "RuntimeError: OCR 실패"), 202);
        JsonNode failed = call(HttpMethod.GET, base + "/documents/" + id, admin.token(), null, 200);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("errorMessage").asText()).contains("OCR 실패");

        JsonNode retried = call(HttpMethod.POST, base + "/documents/" + id + "/retry", admin.token(), null, 200);
        assertThat(retried.get("status").asText()).isEqualTo("PENDING");
        assertThat(lastJobFor(id).attempt()).isEqualTo(2);
        call(HttpMethod.POST, base + "/documents/" + id + "/retry", admin.token(), null, 409);
        // 늦게 도착한 1차 시도 결과는 무시
        assertThat(callback(id, TOKEN, aiResult(1, receiptFields("2026-10-07", "18000.00"), 0.95, null), 202)
                .get("status").asText()).isEqualTo("ignored");

        // 큐 장애: 업로드는 성공하지만 문서는 FAILED로 남아 재시도할 수 있다
        queue.down = true;
        long id2;
        try {
            id2 = upload(base, admin.token(), "RECEIPT", "q.png", png("queue-" + UUID.randomUUID()), 201).get("id").asLong();
        } finally {
            queue.down = false;
        }
        JsonNode queueFailed = call(HttpMethod.GET, base + "/documents/" + id2, admin.token(), null, 200);
        assertThat(queueFailed.get("status").asText()).isEqualTo("FAILED");
        assertThat(queueFailed.get("errorMessage").asText()).contains("Valkey");

        // 시간 초과: 응답 없는 PENDING 문서는 FAILED로
        assertThat(documentService.failTimedOut(Instant.now().plusSeconds(1))).isGreaterThanOrEqualTo(1);
        assertThat(call(HttpMethod.GET, base + "/documents/" + id, admin.token(), null, 200).get("status").asText())
                .isEqualTo("FAILED");

        // OCR이 실패해도 사람이 직접 입력해 진행할 수 있다
        JsonNode manual = call(HttpMethod.PUT, base + "/documents/" + id + "/fields", admin.token(),
                map("vendorName", "가상문구", "issueDate", "2026-10-01", "totalAmount", "8000"), 200);
        assertThat(manual.get("status").asText()).isEqualTo("AI_REVIEWED");

        JsonNode summary = call(HttpMethod.GET, base + "/documents/summary", admin.token(), null, 200);
        assertThat(summary.get("total").asLong()).isEqualTo(2);
        assertThat(summary.get("unprocessed").asLong()).isEqualTo(2);
    }

    @Test
    void 다른_회사_증빙은_404_조회자는_업로드할_수_없다() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "격리상사-" + UUID.randomUUID()), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;
        long id = upload(base, admin.token(), "RECEIPT", "a.png", png("iso-" + UUID.randomUUID()), 201).get("id").asLong();

        Login outsider = signupAndLogin();
        call(HttpMethod.GET, base + "/documents/" + id, outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/documents/" + id + "/file", outsider.token(), null, 404);
        long other = call(HttpMethod.POST, "/api/v1/companies", outsider.token(),
                map("name", "타사-" + UUID.randomUUID()), 201).get("id").asLong();
        call(HttpMethod.GET, "/api/v1/companies/" + other + "/documents/" + id, outsider.token(), null, 404);

        Login viewer = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);
        call(HttpMethod.GET, base + "/documents/" + id, viewer.token(), null, 200);
        upload(base, viewer.token(), "RECEIPT", "v.png", png("viewer-" + UUID.randomUUID()), 403);
    }
}
