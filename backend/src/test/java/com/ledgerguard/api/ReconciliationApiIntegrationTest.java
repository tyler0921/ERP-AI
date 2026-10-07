package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2 Bank Reconciliation 시나리오: CSV 업로드 → 자동 대사 제안 → 승인/거절/수동 매칭/취소,
 * 그리고 DB 안전망(이중 확정 방지, 확정 매칭 금액 균형)을 실제 PostgreSQL로 검증한다. Docker가 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReconciliationApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    private static final String PASSWORD = "password1234";

    // 가상 은행 거래내역 (실제 금융 데이터 아님)
    private static final String STATEMENT = """
            거래일자,출금액,입금액,잔액,거래처,적요
            2026-10-08,"1,253,000",,"8,747,000",(주)가상물산,매입대금
            2026-10-11,,"5,500,000","14,247,000",한빛상사,외상대금
            2026-10-15,"330,000",,"13,917,000",네이버광고,광고비
            2026-10-20,"99,000",,"13,818,000",알수없는상점,
            """;

    // ------------------------------------------------------------------ helpers

    private record Login(String email, long userId, String token) {
    }

    private JsonNode call(HttpMethod method, String url, String token, Object body, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return read(mvc.perform(builder).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode upload(String url, String token, String fileName, byte[] content, int expectedStatus)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/csv", content);
        return read(mvc.perform(multipart(url).file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode read(String content) throws Exception {
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
                map("email", email, "password", PASSWORD, "name", "테스터"), 201);
        JsonNode tokens = call(HttpMethod.POST, "/api/v1/auth/login", null,
                map("email", email, "password", PASSWORD), 200);
        return new Login(email, user.get("id").asLong(), tokens.get("accessToken").asText());
    }

    private Map<String, Long> accountIds(Login login, String base) throws Exception {
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode a : call(HttpMethod.GET, base + "/accounts", login.token(), null, 200)) {
            ids.put(a.get("code").asText(), a.get("id").asLong());
        }
        return ids;
    }

    private long createEntry(Login login, String base, String date, String description,
                             List<Map<String, Object>> lines) throws Exception {
        return call(HttpMethod.POST, base + "/journal-entries", login.token(),
                map("entryDate", date, "description", description, "lines", lines), 201).get("id").asLong();
    }

    private static Map<String, Object> line(long accountId, String debit, String credit) {
        return map("accountId", accountId, "debit", debit, "credit", credit);
    }

    /** 은행 거래 목록을 거래처 → 거래 JSON 맵으로 */
    private Map<String, JsonNode> bankTxByCounterparty(Login login, String base, long bankAccountId) throws Exception {
        JsonNode page = call(HttpMethod.GET, base + "/bank-transactions?size=50&bankAccountId=" + bankAccountId,
                login.token(), null, 200);
        Map<String, JsonNode> result = new HashMap<>();
        page.get("content").forEach(t -> result.put(t.get("counterparty").asText(), t));
        return result;
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 업로드부터_자동대사_검토_취소까지_전체_흐름() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "대사상사-" + UUID.randomUUID()), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;
        Map<String, Long> acc = accountIds(admin, base);
        long bank = acc.get("1020");

        // --- 은행 계좌: 자산 계정과목만, 계정과목당 하나, 계좌번호는 끝 4자리만
        JsonNode invalidGl = call(HttpMethod.POST, base + "/bank-accounts", admin.token(),
                map("name", "잘못된 계좌", "glAccountId", acc.get("5400")), 422);
        assertThat(invalidGl.get("code").asText()).isEqualTo("BANK_ACCOUNT_GL_INVALID");

        JsonNode account = call(HttpMethod.POST, base + "/bank-accounts", admin.token(),
                map("name", "주거래 계좌", "bankName", "가상은행", "accountNo", "123-456-789012", "glAccountId", bank), 201);
        long bankAccountId = account.get("id").asLong();
        assertThat(account.get("accountNoMasked").asText()).isEqualTo("****9012");
        assertThat(account.get("glAccountCode").asText()).isEqualTo("1020");

        JsonNode dupGl = call(HttpMethod.POST, base + "/bank-accounts", admin.token(),
                map("name", "중복", "glAccountId", bank), 409);
        assertThat(dupGl.get("code").asText()).isEqualTo("BANK_ACCOUNT_GL_DUPLICATE");

        // --- 전표 (은행 계정 1020이 움직인 금액이 대사 기준)
        long e1 = createEntry(admin, base, "2026-10-08", "가상물산 매입대금 지급", List.of(
                line(acc.get("2010"), "1250000", "0"), line(acc.get("5700"), "3000", "0"), line(bank, "0", "1253000")));
        long e2 = createEntry(admin, base, "2026-10-10", "한빛상사 외상대금 회수", List.of(
                line(bank, "5500000", "0"), line(acc.get("1100"), "0", "5500000")));
        long e3 = createEntry(admin, base, "2026-10-12", "네이버 광고비", List.of(
                line(acc.get("5400"), "330000", "0"), line(bank, "0", "330000")));

        // --- CSV 업로드 + 재업로드 중복 방지
        String importUrl = base + "/bank-accounts/" + bankAccountId + "/imports";
        byte[] csv = STATEMENT.getBytes(StandardCharsets.UTF_8);
        JsonNode imported = upload(importUrl, admin.token(), "october.csv", csv, 201);
        assertThat(imported.get("importedRows").asInt()).isEqualTo(4);
        assertThat(imported.get("duplicateRows").asInt()).isZero();

        JsonNode again = upload(importUrl, admin.token(), "october.csv", csv, 201);
        assertThat(again.get("importedRows").asInt()).isZero();
        assertThat(again.get("duplicateRows").asInt()).isEqualTo(4);

        // 잘못된 행이 있으면 전체 거부, 아무것도 저장하지 않는다
        JsonNode badRows = upload(importUrl, admin.token(), "bad.csv",
                "거래일자,출금액,입금액\n2026-10-21,1000,\n2026-10-xx,1000,\n".getBytes(StandardCharsets.UTF_8), 422);
        assertThat(badRows.get("code").asText()).isEqualTo("IMPORT_ROW_INVALID");
        assertThat(badRows.get("details").get(0).get("field").asText()).isEqualTo("3행");
        upload(importUrl, admin.token(), "statement.xlsx", csv, 422);
        Integer stored = jdbc.queryForObject("select count(*) from bank_transactions where bank_account_id = ?",
                Integer.class, bankAccountId);
        assertThat(stored).isEqualTo(4);

        // --- 자동 대사: 금액이 맞는 3건은 제안, 금액이 맞는 전표가 없는 1건은 미매칭
        JsonNode run = call(HttpMethod.POST, base + "/reconciliation/run", admin.token(),
                map("bankAccountId", bankAccountId), 200);
        assertThat(run.get("processed").asInt()).isEqualTo(4);
        assertThat(run.get("suggestedHigh").asInt()).isEqualTo(1);
        assertThat(run.get("suggestedReview").asInt()).isEqualTo(2);
        assertThat(run.get("unmatched").asInt()).isEqualTo(1);

        Map<String, JsonNode> txs = bankTxByCounterparty(admin, base, bankAccountId);
        JsonNode tx1 = txs.get("(주)가상물산");
        assertThat(tx1.get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(tx1.get("match").get("level").asText()).isEqualTo("HIGH");
        assertThat(tx1.get("match").get("journalEntryIds").get(0).asLong()).isEqualTo(e1);
        assertThat(txs.get("한빛상사").get("match").get("journalEntryIds").get(0).asLong()).isEqualTo(e2);
        assertThat(txs.get("네이버광고").get("match").get("journalEntryIds").get(0).asLong()).isEqualTo(e3);
        assertThat(txs.get("알수없는상점").get("status").asText()).isEqualTo("UNMATCHED");
        assertThat(txs.get("알수없는상점").get("match").isNull()).isTrue();

        // 점수 근거가 저장된다
        long m1 = tx1.get("match").get("matchId").asLong();
        JsonNode match1 = call(HttpMethod.GET, base + "/reconciliation/matches/" + m1, admin.token(), null, 200);
        assertThat(match1.get("scoreDetail").get("amount").asInt()).isEqualTo(40);
        assertThat(match1.get("scoreDetail").get("reasons").toString()).contains("금액 일치");

        // --- 승인: 확정된 전표는 수정·취소할 수 없다
        JsonNode confirmed = call(HttpMethod.POST, base + "/reconciliation/matches/" + m1 + "/confirm",
                admin.token(), map("note", "통장 사본 확인"), 200);
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("reviewedBy").asLong()).isEqualTo(admin.userId());
        call(HttpMethod.POST, base + "/reconciliation/matches/" + m1 + "/confirm", admin.token(), null, 409);

        JsonNode voidBlocked = call(HttpMethod.POST, base + "/journal-entries/" + e1 + "/void", admin.token(),
                map("reason", "테스트"), 409);
        assertThat(voidBlocked.get("code").asText()).isEqualTo("JOURNAL_RECONCILED");

        // --- 거절: 같은 쌍은 다시 제안하지 않는다
        long m2 = txs.get("한빛상사").get("match").get("matchId").asLong();
        call(HttpMethod.POST, base + "/reconciliation/matches/" + m2 + "/reject", admin.token(),
                map("note", "입금자 확인 필요"), 200);
        JsonNode rerun = call(HttpMethod.POST, base + "/reconciliation/run", admin.token(),
                map("bankAccountId", bankAccountId), 200);
        assertThat(rerun.get("processed").asInt()).isEqualTo(2);
        assertThat(rerun.get("suggestedHigh").asInt() + rerun.get("suggestedReview").asInt()).isZero();

        long tx2 = txs.get("한빛상사").get("id").asLong();
        JsonNode candidates = call(HttpMethod.GET, base + "/bank-transactions/" + tx2 + "/candidates",
                admin.token(), null, 200);
        assertThat(candidates.get(0).get("journalEntryId").asLong()).isEqualTo(e2);
        assertThat(candidates.get(0).get("rejected").asBoolean()).isTrue();

        // --- 수동 매칭: 금액이 같아야 하고, 이미 확정된 전표는 쓸 수 없다
        JsonNode manual = call(HttpMethod.POST, base + "/reconciliation/matches", admin.token(),
                map("bankTransactionId", tx2, "journalEntryId", e2, "note", "입금자 확인 완료"), 201);
        assertThat(manual.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(manual.get("source").asText()).isEqualTo("MANUAL");

        long tx4 = txs.get("알수없는상점").get("id").asLong();
        JsonNode mismatch = call(HttpMethod.POST, base + "/reconciliation/matches", admin.token(),
                map("bankTransactionId", tx4, "journalEntryId", e3), 422);
        assertThat(mismatch.get("code").asText()).isEqualTo("RECON_AMOUNT_MISMATCH");

        long tx3 = txs.get("네이버광고").get("id").asLong();
        JsonNode taken = call(HttpMethod.POST, base + "/reconciliation/matches", admin.token(),
                map("bankTransactionId", tx3, "journalEntryId", e1), 409);
        assertThat(taken.get("code").asText()).isEqualTo("RECON_ALREADY_MATCHED");

        call(HttpMethod.POST, base + "/reconciliation/matches/"
                + txs.get("네이버광고").get("match").get("matchId").asLong() + "/confirm", admin.token(), null, 200);

        // --- 현황: 4건 중 3건 대사, 자동 제안 3건 중 승인 2 / 거절 1
        JsonNode summary = call(HttpMethod.GET, base + "/reconciliation/summary?bankAccountId=" + bankAccountId,
                admin.token(), null, 200);
        assertThat(summary.get("total").asLong()).isEqualTo(4);
        assertThat(summary.get("matched").asLong()).isEqualTo(3);
        assertThat(summary.get("unmatched").asLong()).isEqualTo(1);
        assertThat(summary.get("matchRate").asDouble()).isEqualTo(75.0);
        assertThat(summary.get("autoConfirmed").asLong()).isEqualTo(2);
        assertThat(summary.get("autoRejected").asLong()).isEqualTo(1);
        assertThat(summary.get("autoSuccessRate").asDouble()).isEqualTo(66.7);

        // --- 매칭 취소: 은행 거래는 미매칭으로, 전표는 다시 취소 가능
        call(HttpMethod.POST, base + "/reconciliation/matches/" + m1 + "/cancel", admin.token(), map(), 400);
        JsonNode cancelled = call(HttpMethod.POST, base + "/reconciliation/matches/" + m1 + "/cancel",
                admin.token(), map("reason", "전표 금액 정정 필요"), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bankTxByCounterparty(admin, base, bankAccountId).get("(주)가상물산").get("status").asText())
                .isEqualTo("UNMATCHED");
        call(HttpMethod.POST, base + "/journal-entries/" + e1 + "/void", admin.token(), map("reason", "정정"), 200);

        // --- Audit Log
        Map<String, Integer> actions = new HashMap<>();
        jdbc.query("select action, count(*) c from audit_logs where company_id = ? group by action",
                rs -> {
                    actions.put(rs.getString("action"), rs.getInt("c"));
                }, cid);
        assertThat(actions).containsEntry("BANK_ACCOUNT_CREATE", 1).containsEntry("BANK_IMPORT", 2)
                .containsEntry("RECON_RUN", 2).containsEntry("RECON_CONFIRM", 2).containsEntry("RECON_REJECT", 1)
                .containsEntry("RECON_MANUAL_MATCH", 1).containsEntry("RECON_CANCEL", 1);

        // --- DB 안전망 1: 이미 확정된 전표(e2)를 다른 확정 매칭에 넣을 수 없다
        long rogue = jdbc.queryForObject("""
                insert into reconciliation_matches (company_id, match_type, status, source, score, score_detail, created_by)
                values (?, 'ONE_TO_ONE', 'CONFIRMED', 'MANUAL', 0, '{}', ?) returning id
                """, Long.class, cid, admin.userId());
        assertThatThrownBy(() -> jdbc.update("insert into reconciliation_match_items "
                + "(match_id, journal_entry_id, amount, confirmed) values (?, ?, 5500000, true)", rogue, e2))
                .isInstanceOf(DataAccessException.class);

        // --- DB 안전망 2: 확정 매칭의 은행 금액과 전표 금액이 다르면 커밋할 수 없다
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("insert into reconciliation_match_items (match_id, bank_transaction_id, amount, confirmed) "
                    + "values (?, ?, 99000, true)", rogue, tx4);
            jdbc.update("insert into reconciliation_match_items (match_id, journal_entry_id, amount, confirmed) "
                    + "values (?, ?, 330000, true)", rogue, e1);
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void 다른_회사의_은행_데이터는_404_조회자는_대사를_실행할_수_없다() throws Exception {
        Login admin = signupAndLogin();
        long cid = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "격리상사-" + UUID.randomUUID()), 201).get("id").asLong();
        String base = "/api/v1/companies/" + cid;
        long bank = accountIds(admin, base).get("1020");
        long bankAccountId = call(HttpMethod.POST, base + "/bank-accounts", admin.token(),
                map("name", "주거래", "glAccountId", bank), 201).get("id").asLong();
        upload(base + "/bank-accounts/" + bankAccountId + "/imports", admin.token(), "a.csv",
                STATEMENT.getBytes(StandardCharsets.UTF_8), 201);
        long txId = call(HttpMethod.GET, base + "/bank-transactions?bankAccountId=" + bankAccountId, admin.token(),
                null, 200).get("content").get(0).get("id").asLong();

        Login outsider = signupAndLogin();
        call(HttpMethod.GET, base + "/bank-accounts", outsider.token(), null, 404);
        call(HttpMethod.GET, base + "/bank-transactions/" + txId + "/candidates", outsider.token(), null, 404);
        upload(base + "/bank-accounts/" + bankAccountId + "/imports", outsider.token(), "a.csv",
                STATEMENT.getBytes(StandardCharsets.UTF_8), 404);

        // 남의 회사 계좌 ID를 내 회사 경로로 써도 찾을 수 없다
        long otherCid = call(HttpMethod.POST, "/api/v1/companies", outsider.token(),
                map("name", "타사-" + UUID.randomUUID()), 201).get("id").asLong();
        call(HttpMethod.POST, "/api/v1/companies/" + otherCid + "/reconciliation/run", outsider.token(),
                map("bankAccountId", bankAccountId), 404);

        Login viewer = signupAndLogin();
        call(HttpMethod.POST, base + "/members", admin.token(), map("email", viewer.email(), "role", "VIEWER"), 201);
        call(HttpMethod.GET, base + "/bank-transactions", viewer.token(), null, 200);
        JsonNode forbidden = call(HttpMethod.POST, base + "/reconciliation/run", viewer.token(),
                map("bankAccountId", bankAccountId), 403);
        assertThat(forbidden.get("code").asText()).isEqualTo("ROLE_FORBIDDEN");
    }
}
