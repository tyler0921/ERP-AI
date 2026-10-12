package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.document.infra.FileStorage;
import com.ledgerguard.explanation.infra.LlmJobQueue;
import com.ledgerguard.rules.RagText;
import com.ledgerguard.rules.RuleDocumentService;
import com.ledgerguard.rules.infra.EmbeddingClient;
import com.ledgerguard.rules.infra.RagJobQueue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 8 회계 규정 RAG: 업로드(형식 판별·중복) → 색인 작업(큐) → 색인 콜백(차원 검증·멱등) → pgvector 검색 →
 * 답변 작업(LLM 큐), 관련 없음(NO_MATCH), 임베딩 장애 시 키워드 검색, 보관·다시 색인, 답변 수정, 권한·회사 격리.
 * 실제 임베딩 모델 대신 낱말 해시 기반 가짜 임베딩을 쓴다 (실제 모델은 ai-service 테스트·컨테이너에서 확인).
 */
@SpringBootTest(properties = {"app.documents.timeout-check-ms=3600000", "app.rag.min-score=0.3"})
@AutoConfigureMockMvc
@Testcontainers
class RulesApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final int DIMS = 384;

    static class MemoryStorage implements FileStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] content, String contentType) {
            files.put(key, content);
        }

        @Override
        public byte[] get(String key) {
            byte[] b = files.get(key);
            if (b == null) {
                throw new StorageException("없음", null);
            }
            return b;
        }
    }

    static class CapturingRagQueue implements RagJobQueue {
        final List<RagJob> jobs = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(RagJob job) {
            jobs.add(job);
        }
    }

    static class CapturingLlmQueue implements LlmJobQueue {
        final List<LlmJob> jobs = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(LlmJob job) {
            jobs.add(job);
        }
    }

    /** 낱말(RagText.keywordTerms)을 해시해 384차원에 세고 정규화한다. 같은 낱말이 많을수록 코사인 유사도가 높다 */
    static class FakeEmbedding implements EmbeddingClient {
        volatile boolean down;
        final List<String> kinds = new CopyOnWriteArrayList<>();

        @Override
        public Embeddings embed(List<String> texts, String kind) {
            kinds.add(kind);
            if (down) {
                throw new EmbeddingUnavailableException("AI 서비스(임베딩)에 연결할 수 없습니다.", null);
            }
            return new Embeddings("fake-e5", DIMS, texts.stream().map(FakeEmbedding::vector).toList());
        }

        static List<Double> vector(String text) {
            double[] v = new double[DIMS];
            for (String t : RagText.keywordTerms(text + " ")) {
                v[Math.floorMod(t.hashCode(), DIMS)] += 1;
            }
            // 키워드가 하나도 없으면 영벡터 대신 고정 방향(어떤 문서와도 관련 없음)
            double norm = 0;
            for (double x : v) {
                norm += x * x;
            }
            if (norm == 0) {
                v[DIMS - 1] = 1;
                norm = 1;
            }
            List<Double> out = new ArrayList<>(DIMS);
            for (double x : v) {
                out.add(x / Math.sqrt(norm));
            }
            return out;
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
        CapturingRagQueue capturingRagQueue() {
            return new CapturingRagQueue();
        }

        @Bean
        @Primary
        CapturingLlmQueue capturingLlmQueue() {
            return new CapturingLlmQueue();
        }

        @Bean
        @Primary
        FakeEmbedding fakeEmbedding() {
            return new FakeEmbedding();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    CapturingRagQueue ragQueue;
    @Autowired
    CapturingLlmQueue llmQueue;
    @Autowired
    FakeEmbedding embedding;
    @Autowired
    RuleDocumentService documentService;

    private static final String TOKEN = "dev-internal-token-change-me";

    /** 가상 경비 처리 규정 — 청크 3개 (ai-service가 나눴다고 가정) */
    private static final String[][] CHUNKS = {
            {"제2장 접대비 > 제5조(접대비 한도)", "접대비는 거래처 1회당 300,000원 한도로 집행한다. 한도를 넘으면 사전 승인을 받는다."},
            {"제3장 법인카드 > 제9조(사용 제한)", "법인카드는 주말과 공휴일에 사용할 수 없다. 불가피한 경우 사후 소명서를 제출한다."},
            {"제4장 증빙 > 제12조(적격 증빙)", "30,000원을 넘는 지출은 세금계산서나 카드전표 등 적격 증빙을 받아야 한다."},
    };

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
                map("name", "규정상사-" + UUID.randomUUID()), 201).get("id").asLong();
        return "/api/v1/companies/" + cid;
    }

    private JsonNode upload(Login login, String base, String title, String fileName, byte[] content, int expected) throws Exception {
        MvcResult r = mvc.perform(multipart(base + "/rules/documents")
                        .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                        .param("title", title).param("category", "EXPENSE_POLICY")
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().is(expected)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isBlank() ? null : om.readTree(body);
    }

    private static byte[] regulation(String salt) {
        StringBuilder sb = new StringBuilder("# 경비 처리 규정 (가상) " + salt + "\n\n");
        for (String[] c : CHUNKS) {
            sb.append("## ").append(c[0]).append("\n\n").append(c[1]).append("\n\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> indexPayload(int attempt) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 0; i < CHUNKS.length; i++) {
            chunks.add(map("index", i, "section", CHUNKS[i][0], "page", null, "content", CHUNKS[i][1],
                    "embedding", FakeEmbedding.vector(CHUNKS[i][0] + " " + CHUNKS[i][1])));
        }
        return map("attempt", attempt, "success", true, "model", "fake-e5", "dims", DIMS, "charCount", 180, "chunks", chunks);
    }

    private JsonNode indexCallback(long id, String token, Object body, int expected) throws Exception {
        MockHttpServletRequestBuilder b = request(HttpMethod.POST, "/internal/v1/rules/documents/" + id + "/index")
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (token != null) {
            b.header("X-Internal-Token", token);
        }
        String content = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(content);
    }

    /** 업로드 → 색인 완료까지 */
    private long indexedDocument(Login admin, String base, String salt) throws Exception {
        long id = upload(admin, base, "경비 처리 규정", "경비규정.md", regulation(salt), 201).get("id").asLong();
        indexCallback(id, TOKEN, indexPayload(1), 202);
        return id;
    }

    private JsonNode ask(Login login, String base, String question) throws Exception {
        return call(HttpMethod.POST, base + "/rules/questions", login.token(), map("question", question), 201);
    }

    private JsonNode lastAudit(Login login, String base, String action) throws Exception {
        for (JsonNode a : call(HttpMethod.GET, base + "/audit-logs?size=30", login.token(), null, 200).get("content")) {
            if (a.get("action").asText().equals(action)) {
                return a;
            }
        }
        throw new AssertionError(action + " Audit Log가 없습니다.");
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 업로드부터_색인까지() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);

        // --- 업로드: 형식은 바이트로 판별, 커밋 후 색인 작업
        int before = ragQueue.jobs.size();
        JsonNode doc = upload(admin, base, "  경비 처리 규정  ", "../경비규정.md", regulation("a"), 201);
        long id = doc.get("id").asLong();
        assertThat(doc.get("status").asText()).isEqualTo("PENDING");
        assertThat(doc.get("title").asText()).isEqualTo("경비 처리 규정");
        assertThat(doc.get("fileName").asText()).isEqualTo("경비규정.md");
        assertThat(doc.get("contentType").asText()).isEqualTo("text/markdown");
        RagJobQueue.RagJob job = ragQueue.jobs.get(before);
        assertThat(job.documentId()).isEqualTo(id);
        assertThat(job.fileKey()).matches("companies/\\d+/rules/[0-9a-f\\-]{36}\\.md");
        assertThat(lastAudit(admin, base, "RULE_DOC_UPLOAD").get("after").get("title").asText()).isEqualTo("경비 처리 규정");

        assertThat(upload(admin, base, "같은 파일", "copy.md", regulation("a"), 409).get("code").asText()).isEqualTo("RULE_DUPLICATE");
        upload(admin, base, "이미지", "x.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2}, 422);
        mvc.perform(multipart(base + "/rules/documents")
                        .file(new MockMultipartFile("file", "a.md", "text/markdown", regulation("b")))
                        .param("title", " ").param("category", "EXPENSE_POLICY")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().is(400));

        // --- 색인 콜백: 토큰, 시도 번호, 차원 검증
        indexCallback(id, null, indexPayload(1), 401);
        assertThat(indexCallback(id, TOKEN, indexPayload(9), 202).get("status").asText()).isEqualTo("ignored");
        Map<String, Object> wrongDims = indexPayload(1);
        wrongDims.put("dims", 768);
        assertThat(indexCallback(id, TOKEN, wrongDims, 202).get("status").asText()).isEqualTo("applied");
        JsonNode failed = call(HttpMethod.GET, base + "/rules/documents/" + id, admin.token(), null, 200);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).contains("차원");

        // --- 다시 색인 → 새 시도 번호
        before = ragQueue.jobs.size();
        JsonNode re = call(HttpMethod.POST, base + "/rules/documents/" + id + "/reindex", admin.token(), null, 200);
        assertThat(re.get("status").asText()).isEqualTo("PENDING");
        assertThat(re.get("attempt").asInt()).isEqualTo(2);
        assertThat(ragQueue.jobs.get(before).attempt()).isEqualTo(2);
        call(HttpMethod.POST, base + "/rules/documents/" + id + "/reindex", admin.token(), null, 409);
        assertThat(indexCallback(id, TOKEN, indexPayload(2), 202).get("status").asText()).isEqualTo("applied");
        assertThat(indexCallback(id, TOKEN, indexPayload(2), 202).get("status").asText()).isEqualTo("ignored");
        JsonNode indexed = call(HttpMethod.GET, base + "/rules/documents/" + id, admin.token(), null, 200);
        assertThat(indexed.get("status").asText()).isEqualTo("INDEXED");
        assertThat(indexed.get("chunkCount").asInt()).isEqualTo(3);
        assertThat(indexed.get("embeddingModel").asText()).isEqualTo("fake-e5");
        JsonNode chunks = call(HttpMethod.GET, base + "/rules/documents/" + id + "/chunks", admin.token(), null, 200);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1).get("section").asText()).isEqualTo("제3장 법인카드 > 제9조(사용 제한)");

        // 성공했는데 텍스트가 없으면(스캔 PDF) FAILED
        long empty = upload(admin, base, "스캔본", "scan.md", regulation("scan"), 201).get("id").asLong();
        indexCallback(empty, TOKEN, map("attempt", 1, "success", true, "model", "fake-e5", "dims", DIMS, "chunks", List.of()), 202);
        assertThat(call(HttpMethod.GET, base + "/rules/documents/" + empty, admin.token(), null, 200).get("error").asText())
                .contains("텍스트를 찾지 못했습니다");

        // 원본 내려받기는 항상 attachment
        MvcResult file = mvc.perform(request(HttpMethod.GET, base + "/rules/documents/" + id + "/file")
                .header("Authorization", "Bearer " + admin.token())).andExpect(status().isOk()).andReturn();
        assertThat(file.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(regulation("a"));

        // 시간 초과
        long stuck = upload(admin, base, "멈춘 문서", "stuck.md", regulation("stuck"), 201).get("id").asLong();
        assertThat(documentService.failTimedOut(Instant.now().plusSeconds(1))).isGreaterThanOrEqualTo(1);
        assertThat(call(HttpMethod.GET, base + "/rules/documents/" + stuck, admin.token(), null, 200).get("status").asText())
                .isEqualTo("FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 질문_검색_답변() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);

        // 색인된 문서가 없으면 검색하지 않는다
        JsonNode none = ask(admin, base, "접대비 한도는?");
        assertThat(none.get("status").asText()).isEqualTo("NO_MATCH");
        assertThat(none.get("retrievalMeta").get("reason").asText()).contains("색인된 규정 문서가 없습니다");

        long docId = indexedDocument(admin, base, "q");

        // --- 벡터 검색 → 답변 작업 (질문 임베딩은 query, 근거는 번호 붙은 발췌)
        int before = llmQueue.jobs.size();
        JsonNode q = ask(admin, base, "  접대비 한도는 얼마인가요?  ");
        long qid = q.get("id").asLong();
        assertThat(q.get("question").asText()).isEqualTo("접대비 한도는 얼마인가요?");
        assertThat(q.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(q.get("retrievalMode").asText()).isEqualTo("VECTOR");
        assertThat(embedding.kinds.get(embedding.kinds.size() - 1)).isEqualTo("query");
        JsonNode top = q.get("sources").get(0);
        assertThat(top.get("documentId").asLong()).isEqualTo(docId);
        assertThat(top.get("section").asText()).isEqualTo("제2장 접대비 > 제5조(접대비 한도)");
        assertThat(top.get("score").asDouble()).isGreaterThan(0.3);
        assertThat(q.get("retrievalMeta").get("bestScore").asDouble()).isEqualTo(top.get("score").asDouble());
        assertThat(q.get("answer").get("status").asText()).isEqualTo("PENDING");
        List<LlmJobQueue.LlmJob> jobs = new ArrayList<>(llmQueue.jobs.subList(before, llmQueue.jobs.size()));
        assertThat(jobs).singleElement().satisfies(j -> assertThat(j.targetType()).isEqualTo("RULE_ANSWER"));
        List<Map<String, Object>> excerpts = (List<Map<String, Object>>) jobs.get(0).facts().get("규정 발췌");
        assertThat(excerpts.get(0)).containsEntry("번호", 1)
                .containsEntry("출처", "경비 처리 규정 › 제2장 접대비 > 제5조(접대비 한도)");
        assertThat(lastAudit(admin, base, "RULE_ASK").get("after").get("retrievalMode").asText()).isEqualTo("VECTOR");

        // --- 답변 콜백·수정·다시 생성 (Audit)
        long answerId = jobs.get(0).explanationId();
        mvc.perform(request(HttpMethod.POST, "/internal/v1/explanations/" + answerId + "/result")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(map("attempt", 1, "success", true, "status", "GENERATED",
                                "text", "접대비는 거래처 1회당 300,000원 한도입니다 [1].", "checkpoints", List.of(),
                                "meta", map("promptVersion", "rag-v1")))))
                .andExpect(status().is(202));
        JsonNode answered = call(HttpMethod.GET, base + "/rules/questions/" + qid, admin.token(), null, 200);
        assertThat(answered.get("answer").get("text").asText()).contains("[1]");
        call(HttpMethod.PUT, base + "/rules/questions/" + qid + "/answer", admin.token(),
                map("text", "1회 300,000원까지, 넘으면 사전 승인 [1]"), 200);
        assertThat(lastAudit(admin, base, "RULE_ANSWER_EDIT").get("after").get("status").asText()).isEqualTo("EDITED");
        call(HttpMethod.POST, base + "/rules/questions/" + qid + "/answer/regenerate", admin.token(), null, 200);
        assertThat(lastAudit(admin, base, "RULE_ANSWER_REGENERATE").get("before").get("status").asText()).isEqualTo("EDITED");

        // --- 관련 없는 질문: LLM을 부르지 않는다
        before = llmQueue.jobs.size();
        JsonNode unrelated = ask(admin, base, "주차장 운영 시간");
        assertThat(unrelated.get("status").asText()).isEqualTo("NO_MATCH");
        assertThat(unrelated.get("sources")).isEmpty();
        assertThat(unrelated.get("answer").isNull()).isTrue();
        assertThat(llmQueue.jobs.size()).isEqualTo(before);
        call(HttpMethod.POST, base + "/rules/questions/" + unrelated.get("id").asLong() + "/answer/regenerate",
                admin.token(), null, 409);

        // --- 임베딩 장애 → 키워드 검색
        embedding.down = true;
        try {
            JsonNode keyword = ask(admin, base, "법인카드를 주말에 써도 되나요?");
            assertThat(keyword.get("retrievalMode").asText()).isEqualTo("KEYWORD");
            assertThat(keyword.get("status").asText()).isEqualTo("ANSWERED");
            assertThat(keyword.get("sources").get(0).get("section").asText()).isEqualTo("제3장 법인카드 > 제9조(사용 제한)");
            assertThat(keyword.get("retrievalMeta").get("embeddingError").asText()).contains("임베딩");
            assertThat(keyword.get("retrievalMeta").get("terms").get(0).asText()).isEqualTo("법인카드");
        } finally {
            embedding.down = false;
        }

        // --- 보관하면 검색에서 빠진다 (사유 필수, Audit)
        call(HttpMethod.POST, base + "/rules/documents/" + docId + "/archive", admin.token(), map("reason", " "), 400);
        JsonNode archived = call(HttpMethod.POST, base + "/rules/documents/" + docId + "/archive", admin.token(),
                map("reason", "2027년 개정판으로 교체"), 200);
        assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(archived.get("archiveReason").asText()).isEqualTo("2027년 개정판으로 교체");
        assertThat(lastAudit(admin, base, "RULE_DOC_ARCHIVE").get("after").get("reason").asText()).isEqualTo("2027년 개정판으로 교체");
        call(HttpMethod.POST, base + "/rules/documents/" + docId + "/archive", admin.token(), map("reason", "또"), 409);
        call(HttpMethod.POST, base + "/rules/documents/" + docId + "/reindex", admin.token(), null, 409);
        assertThat(ask(admin, base, "접대비 한도는 얼마인가요?").get("status").asText()).isEqualTo("NO_MATCH");
        // 이전 질문의 근거 스냅샷은 그대로 남는다
        assertThat(call(HttpMethod.GET, base + "/rules/questions/" + qid, admin.token(), null, 200)
                .get("sources").get(0).get("content").asText()).contains("300,000원");

        JsonNode list = call(HttpMethod.GET, base + "/rules/questions", admin.token(), null, 200);
        assertThat(list.get("totalElements").asLong()).isEqualTo(5);
    }

    @Test
    void 권한과_회사_격리() throws Exception {
        Login admin = signupAndLogin();
        String base = newCompany(admin);
        long docId = indexedDocument(admin, base, "iso");
        Login viewer = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);

        // VIEWER: 조회·질문은 가능, 문서 변경은 불가
        call(HttpMethod.GET, base + "/rules/documents", viewer.token(), null, 200);
        call(HttpMethod.GET, base + "/rules/documents/" + docId + "/chunks", viewer.token(), null, 200);
        upload(viewer, base, "x", "x.md", regulation("viewer"), 403);
        call(HttpMethod.POST, base + "/rules/documents/" + docId + "/archive", viewer.token(), map("reason", "x"), 403);
        call(HttpMethod.POST, base + "/rules/documents/" + docId + "/reindex", viewer.token(), null, 403);
        long vq = ask(viewer, base, "접대비 한도").get("id").asLong();
        // 다른 사람의 질문은 보이지 않는다
        call(HttpMethod.GET, base + "/rules/questions/" + vq, admin.token(), null, 404);

        // 다른 회사: 같은 내용을 물어도 이 회사 규정은 검색되지 않는다
        Login outsider = signupAndLogin();
        String otherBase = newCompany(outsider);
        assertThat(ask(outsider, otherBase, "접대비 한도는 얼마인가요?").get("status").asText()).isEqualTo("NO_MATCH");
        indexedDocument(outsider, otherBase, "other");
        JsonNode otherAnswer = ask(outsider, otherBase, "접대비 한도는 얼마인가요?");
        assertThat(otherAnswer.get("sources")).allSatisfy(s -> assertThat(s.get("documentId").asLong()).isNotEqualTo(docId));
        call(HttpMethod.GET, otherBase + "/rules/documents/" + docId, outsider.token(), null, 404);
        call(HttpMethod.GET, otherBase + "/rules/documents/" + docId + "/file", outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/rules/documents", outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/rules/documents", null, null, 401);
    }
}
