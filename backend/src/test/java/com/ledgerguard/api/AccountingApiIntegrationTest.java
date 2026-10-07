package com.ledgerguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 1 핵심 시나리오를 실제 PostgreSQL(Testcontainers)과 Flyway 마이그레이션으로 검증한다.
 * 감사 로그는 삭제할 수 없으므로 테스트마다 고유한 이메일/회사를 만들어 서로 간섭하지 않게 한다.
 * 실행하려면 Docker가 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountingApiIntegrationTest {

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

    // ------------------------------------------------------------------ helpers

    private JsonNode call(HttpMethod method, String url, String token, Object body, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        String content = mvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isBlank() ? null : om.readTree(content);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String PASSWORD = "password1234";

    private record Login(String email, long userId, String token) {
    }

    private Login signupAndLogin() throws Exception {
        String email = uniqueEmail();
        JsonNode user = call(HttpMethod.POST, "/api/v1/auth/signup", null,
                map("email", email, "password", PASSWORD, "name", "테스터"), 201);
        JsonNode tokens = call(HttpMethod.POST, "/api/v1/auth/login", null,
                map("email", email, "password", PASSWORD), 200);
        return new Login(email, user.get("id").asLong(), tokens.get("accessToken").asText());
    }

    private long createCompany(Login admin) throws Exception {
        JsonNode company = call(HttpMethod.POST, "/api/v1/companies", admin.token(),
                map("name", "테스트상사-" + UUID.randomUUID(), "businessNo", "123-45-67890"), 201);
        return company.get("id").asLong();
    }

    private long accountId(Login login, long companyId, String code) throws Exception {
        JsonNode accounts = call(HttpMethod.GET, "/api/v1/companies/" + companyId + "/accounts", login.token(),
                null, 200);
        for (JsonNode a : accounts) {
            if (code.equals(a.get("code").asText())) {
                return a.get("id").asLong();
            }
        }
        throw new AssertionError("계정과목 없음: " + code);
    }

    private Map<String, Object> line(long accountId, String debit, String credit) {
        return map("accountId", accountId, "debit", debit, "credit", credit);
    }

    private Map<String, Object> entryBody(Long transactionId, List<Map<String, Object>> lines) {
        return map("entryDate", "2026-10-08", "description", "10월 매입대금 지급",
                "transactionId", transactionId, "lines", lines);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void 인증_없이_접근하면_401() throws Exception {
        JsonNode error = call(HttpMethod.GET, "/api/v1/companies", null, null, 401);
        assertThat(error.get("code").asText()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void 잘못된_비밀번호는_거부되고_이력이_남는다() throws Exception {
        Login user = signupAndLogin();
        JsonNode error = call(HttpMethod.POST, "/api/v1/auth/login", null,
                map("email", user.email(), "password", "wrong-password"), 401);
        assertThat(error.get("code").asText()).isEqualTo("AUTH_INVALID_CREDENTIALS");

        Integer failures = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'LOGIN_FAILURE' and actor_id = ?",
                Integer.class, user.userId());
        assertThat(failures).isEqualTo(1);
    }

    @Test
    void refresh_토큰으로_재발급하고_access_토큰은_refresh에_쓸_수_없다() throws Exception {
        String email = uniqueEmail();
        call(HttpMethod.POST, "/api/v1/auth/signup", null,
                map("email", email, "password", PASSWORD, "name", "테스터"), 201);
        JsonNode tokens = call(HttpMethod.POST, "/api/v1/auth/login", null,
                map("email", email, "password", PASSWORD), 200);

        JsonNode refreshed = call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                map("refreshToken", tokens.get("refreshToken").asText()), 200);
        assertThat(refreshed.get("accessToken").asText()).isNotBlank();

        call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                map("refreshToken", tokens.get("accessToken").asText()), 401);
        // refresh 토큰으로는 API를 호출할 수 없다.
        call(HttpMethod.GET, "/api/v1/me", tokens.get("refreshToken").asText(), null, 401);
    }

    @Test
    void 회사를_만들면_기본_계정과목이_채워지고_내_회사로_조회된다() throws Exception {
        Login admin = signupAndLogin();
        long companyId = createCompany(admin);

        JsonNode accounts = call(HttpMethod.GET, "/api/v1/companies/" + companyId + "/accounts", admin.token(),
                null, 200);
        assertThat(accounts.size()).isGreaterThan(10);

        JsonNode me = call(HttpMethod.GET, "/api/v1/me", admin.token(), null, 200);
        assertThat(me.get("companies").get(0).get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void 거래와_전표를_등록하고_연결_조회할_수_있다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        String base = "/api/v1/companies/" + cid;

        JsonNode tx = call(HttpMethod.POST, base + "/transactions", admin.token(),
                map("txDate", "2026-10-08", "counterparty", "대한상사", "amount", "1253000.00",
                        "direction", "OUT", "memo", "10월 매입대금"), 201);
        long txId = tx.get("id").asLong();
        assertThat(tx.get("amount").asText()).isEqualTo("1253000.00");
        assertThat(tx.get("status").asText()).isEqualTo("PENDING");

        long purchase = accountId(admin, cid, "2010");   // 외상매입금
        long fee = accountId(admin, cid, "5700");        // 지급수수료
        long bank = accountId(admin, cid, "1020");       // 보통예금

        JsonNode entry = call(HttpMethod.POST, base + "/journal-entries", admin.token(),
                entryBody(txId, List.of(
                        line(purchase, "1250000.00", "0"),
                        line(fee, "3000.00", "0"),
                        line(bank, "0", "1253000.00"))), 201);
        long entryId = entry.get("id").asLong();
        assertThat(entry.get("totalDebit").asText()).isEqualTo("1253000.00");
        assertThat(entry.get("totalCredit").asText()).isEqualTo("1253000.00");
        assertThat(entry.get("lines").get(0).get("accountName").asText()).isEqualTo("외상매입금");

        JsonNode txDetail = call(HttpMethod.GET, base + "/transactions/" + txId, admin.token(), null, 200);
        assertThat(txDetail.get("journalEntryIds").get(0).asLong()).isEqualTo(entryId);

        // 전표가 연결된 거래는 수정할 수 없다.
        JsonNode error = call(HttpMethod.PATCH, base + "/transactions/" + txId, admin.token(),
                map("amount", "1.00"), 409);
        assertThat(error.get("code").asText()).isEqualTo("TX_ALREADY_LINKED");

        // 계정과목 필터로 전표를 찾을 수 있다.
        JsonNode filtered = call(HttpMethod.GET, base + "/journal-entries?accountId=" + fee, admin.token(),
                null, 200);
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void 차대변이_다른_전표는_422로_거부된다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        long purchase = accountId(admin, cid, "2010");
        long bank = accountId(admin, cid, "1020");

        JsonNode error = call(HttpMethod.POST, "/api/v1/companies/" + cid + "/journal-entries", admin.token(),
                entryBody(null, List.of(
                        line(purchase, "1250000.00", "0"),
                        line(bank, "0", "1200000.00"))), 422);

        assertThat(error.get("code").asText()).isEqualTo("JOURNAL_NOT_BALANCED");
        assertThat(error.get("details").get(0).get("reason").asText())
                .isEqualTo("debit=1250000.00, credit=1200000.00");
    }

    @Test
    void 다른_회사_리소스는_404로_숨겨진다() throws Exception {
        Login owner = signupAndLogin();
        long cid = createCompany(owner);
        Login outsider = signupAndLogin();

        JsonNode error = call(HttpMethod.GET, "/api/v1/companies/" + cid + "/accounts", outsider.token(), null, 404);
        assertThat(error.get("code").asText()).isEqualTo("COMPANY_ACCESS_DENIED");

        // 내 회사의 경로로 다른 회사의 거래 id를 넣어도 찾을 수 없다.
        long otherCompany = createCompany(outsider);
        JsonNode tx = call(HttpMethod.POST, "/api/v1/companies/" + cid + "/transactions", owner.token(),
                map("txDate", "2026-10-08", "amount", "1000.00", "direction", "IN"), 201);
        call(HttpMethod.GET, "/api/v1/companies/" + otherCompany + "/transactions/" + tx.get("id").asLong(),
                outsider.token(), null, 404);
    }

    @Test
    void 다른_회사의_계정과목으로는_전표를_만들_수_없다() throws Exception {
        Login owner = signupAndLogin();
        long cid = createCompany(owner);
        Login other = signupAndLogin();
        long otherCid = createCompany(other);

        long mine = accountId(owner, cid, "1020");
        long foreign = accountId(other, otherCid, "2010");

        JsonNode error = call(HttpMethod.POST, "/api/v1/companies/" + cid + "/journal-entries", owner.token(),
                entryBody(null, List.of(line(mine, "100.00", "0"), line(foreign, "0", "100.00"))), 422);
        assertThat(error.get("code").asText()).isEqualTo("JOURNAL_LINE_INVALID");
    }

    @Test
    void VIEWER는_조회만_가능하다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        Login viewer = signupAndLogin();
        call(HttpMethod.POST, "/api/v1/companies/" + cid + "/members", admin.token(),
                map("email", viewer.email(), "role", "VIEWER"), 201);

        call(HttpMethod.GET, "/api/v1/companies/" + cid + "/accounts", viewer.token(), null, 200);

        JsonNode error = call(HttpMethod.POST, "/api/v1/companies/" + cid + "/transactions", viewer.token(),
                map("txDate", "2026-10-08", "amount", "1000.00", "direction", "IN"), 403);
        assertThat(error.get("code").asText()).isEqualTo("ROLE_FORBIDDEN");

        // 감사 로그와 멤버 관리는 VIEWER에게 허용되지 않는다.
        call(HttpMethod.GET, "/api/v1/companies/" + cid + "/audit-logs", viewer.token(), null, 403);
        call(HttpMethod.POST, "/api/v1/companies/" + cid + "/members", viewer.token(),
                map("email", admin.email(), "role", "ADMIN"), 403);
    }

    @Test
    void 마지막_ADMIN은_강등하거나_제거할_수_없다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        String members = "/api/v1/companies/" + cid + "/members";

        JsonNode demote = call(HttpMethod.PATCH, members + "/" + admin.userId(), admin.token(),
                map("role", "VIEWER"), 409);
        assertThat(demote.get("code").asText()).isEqualTo("LAST_ADMIN_REQUIRED");

        JsonNode remove = call(HttpMethod.DELETE, members + "/" + admin.userId(), admin.token(), null, 409);
        assertThat(remove.get("code").asText()).isEqualTo("LAST_ADMIN_REQUIRED");

        // ADMIN이 둘이면 한 명은 제거할 수 있다.
        Login second = signupAndLogin();
        call(HttpMethod.POST, members, admin.token(), map("email", second.email(), "role", "ADMIN"), 201);
        call(HttpMethod.DELETE, members + "/" + second.userId(), admin.token(), null, 204);
    }

    @Test
    void 취소된_전표는_수정과_재취소가_불가능하다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        String base = "/api/v1/companies/" + cid + "/journal-entries";
        long expense = accountId(admin, cid, "5600");
        long cash = accountId(admin, cid, "1010");
        List<Map<String, Object>> lines = List.of(line(expense, "5000.00", "0"), line(cash, "0", "5000.00"));

        long id = call(HttpMethod.POST, base, admin.token(), entryBody(null, lines), 201).get("id").asLong();

        // 수정(PUT)은 라인 전체를 교체한다.
        JsonNode updated = call(HttpMethod.PUT, base + "/" + id, admin.token(),
                entryBody(null, List.of(line(expense, "7000.00", "0"), line(cash, "0", "7000.00"))), 200);
        assertThat(updated.get("totalDebit").asText()).isEqualTo("7000.00");

        JsonNode voided = call(HttpMethod.POST, base + "/" + id + "/void", admin.token(),
                map("reason", "중복 입력"), 200);
        assertThat(voided.get("status").asText()).isEqualTo("VOID");

        assertThat(call(HttpMethod.PUT, base + "/" + id, admin.token(), entryBody(null, lines), 409)
                .get("code").asText()).isEqualTo("JOURNAL_ALREADY_VOID");
        assertThat(call(HttpMethod.POST, base + "/" + id + "/void", admin.token(), map("reason", "또"), 409)
                .get("code").asText()).isEqualTo("JOURNAL_ALREADY_VOID");
    }

    @Test
    void 비활성_계정과목은_전표에_쓸_수_없고_사용_중인_코드는_바꿀_수_없다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        String base = "/api/v1/companies/" + cid;
        long expense = accountId(admin, cid, "5600");
        long cash = accountId(admin, cid, "1010");

        call(HttpMethod.POST, base + "/journal-entries", admin.token(),
                entryBody(null, List.of(line(expense, "100.00", "0"), line(cash, "0", "100.00"))), 201);

        JsonNode inUse = call(HttpMethod.PATCH, base + "/accounts/" + expense, admin.token(),
                map("code", "5699"), 409);
        assertThat(inUse.get("code").asText()).isEqualTo("ACCOUNT_IN_USE");

        call(HttpMethod.PATCH, base + "/accounts/" + expense, admin.token(), map("active", false), 200);
        JsonNode inactive = call(HttpMethod.POST, base + "/journal-entries", admin.token(),
                entryBody(null, List.of(line(expense, "100.00", "0"), line(cash, "0", "100.00"))), 422);
        assertThat(inactive.get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");

        JsonNode duplicate = call(HttpMethod.POST, base + "/accounts", admin.token(),
                map("code", "1010", "name", "중복", "type", "ASSET"), 409);
        assertThat(duplicate.get("code").asText()).isEqualTo("ACCOUNT_CODE_DUPLICATE");
    }

    @Test
    void 입력_검증_오류는_VALIDATION_FAILED로_응답한다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);

        JsonNode error = call(HttpMethod.POST, "/api/v1/companies/" + cid + "/transactions", admin.token(),
                map("txDate", "2026-10-08", "amount", "-5", "direction", "IN"), 400);
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("details").size()).isGreaterThan(0);
    }

    @Test
    void 쓰기_작업은_감사_로그를_남기고_감사_로그는_수정_삭제할_수_없다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        String base = "/api/v1/companies/" + cid;

        JsonNode tx = call(HttpMethod.POST, base + "/transactions", admin.token(),
                map("txDate", "2026-10-08", "amount", "1000.00", "direction", "IN"), 201);
        long txId = tx.get("id").asLong();
        call(HttpMethod.PATCH, base + "/transactions/" + txId, admin.token(), map("amount", "2000.00"), 200);

        JsonNode logs = call(HttpMethod.GET, base + "/audit-logs?targetType=TRANSACTION&targetId=" + txId,
                admin.token(), null, 200);
        assertThat(logs.get("totalElements").asLong()).isEqualTo(2);
        JsonNode update = logs.get("content").get(0); // 최신순
        assertThat(update.get("action").asText()).isEqualTo("TX_UPDATE");
        assertThat(update.get("before").get("amount").asText()).isEqualTo("1000.00");
        assertThat(update.get("after").get("amount").asText()).isEqualTo("2000.00");

        Long logId = jdbc.queryForObject("select id from audit_logs where company_id = ? limit 1", Long.class, cid);
        assertThatThrownBy(() -> jdbc.update("update audit_logs set action = 'HACKED' where id = ?", logId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from audit_logs where id = ?", logId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void DB는_서비스_검증을_우회한_불균형_전표도_커밋_시점에_거부한다() throws Exception {
        Login admin = signupAndLogin();
        long cid = createCompany(admin);
        long expense = accountId(admin, cid, "5600");
        long cash = accountId(admin, cid, "1010");

        TransactionTemplate template = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            Long entryId = jdbc.queryForObject(
                    "insert into journal_entries(company_id, entry_date, created_by) values (?, '2026-10-08', ?) returning id",
                    Long.class, cid, admin.userId());
            jdbc.update("insert into journal_entry_lines(entry_id, account_id, debit, credit) values (?, ?, 100, 0)",
                    entryId, expense);
            jdbc.update("insert into journal_entry_lines(entry_id, account_id, debit, credit) values (?, ?, 0, 90)",
                    entryId, cash);
        })).isInstanceOf(RuntimeException.class);

        Integer leftover = jdbc.queryForObject(
                "select count(*) from journal_entries where company_id = ?", Integer.class, cid);
        assertThat(leftover).isZero();
    }
}
