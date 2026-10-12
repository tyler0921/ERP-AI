package com.ledgerguard.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 규정 청크 (pgvector). JPA 대신 JDBC로 다룬다. 모든 검색에 회사 조건과 "색인 완료 문서만" 조건이 들어간다.
 * 데이터가 작아 정확 검색(ANN 인덱스 없음)을 쓴다 — 회사 조건으로 걸러도 재현율이 떨어지지 않는다.
 */
@Repository
public class RuleChunkRepository {

    /** 색인 콜백의 청크 한 개 (임베딩은 pgvector 리터럴) */
    public record NewChunk(int index, String section, Integer page, String content, String vector) {
    }

    /** 검색 결과 / 미리보기 */
    public record Hit(Long chunkId, Long documentId, String title, String category, String section, Integer page,
                      String content, double score) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", chunkId);
            m.put("documentId", documentId);
            m.put("title", title);
            m.put("category", category);
            m.put("section", section);
            m.put("page", page);
            m.put("content", content);
            m.put("score", Math.round(score * 1000) / 1000.0);
            return m;
        }
    }

    private static final String HIT_COLUMNS = """
            c.id, c.document_id, d.title, d.category, c.section, c.page, c.content""";

    private final NamedParameterJdbcTemplate jdbc;

    public RuleChunkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 문서의 청크를 새 색인으로 바꾼다 (파생 데이터라 지우고 다시 만든다) */
    public void replace(Long documentId, Long companyId, List<NewChunk> chunks) {
        jdbc.update("DELETE FROM rule_chunks WHERE document_id = :documentId",
                Map.of("documentId", documentId));
        MapSqlParameterSource[] batch = chunks.stream().map(c -> new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("companyId", companyId)
                .addValue("index", c.index())
                .addValue("section", c.section())
                .addValue("page", c.page())
                .addValue("content", c.content())
                .addValue("vector", c.vector())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO rule_chunks (document_id, company_id, chunk_index, section, page, content, embedding)
                VALUES (:documentId, :companyId, :index, :section, :page, :content, CAST(:vector AS vector))
                """, batch);
    }

    /** 코사인 유사도(1 − 코사인 거리) 높은 순 */
    public List<Hit> searchByVector(Long companyId, String queryVector, int limit) {
        Map<String, Object> p = Map.of("companyId", companyId, "q", queryVector, "limit", limit);
        return jdbc.query("SELECT " + HIT_COLUMNS + ", 1 - (c.embedding <=> CAST(:q AS vector)) AS score"
                        + """
                         FROM rule_chunks c JOIN rule_documents d ON d.id = c.document_id
                        WHERE c.company_id = :companyId AND d.company_id = :companyId AND d.status = 'INDEXED'
                        ORDER BY c.embedding <=> CAST(:q AS vector), c.id
                        LIMIT :limit
                        """, p, (rs, i) -> hit(rs, rs.getDouble("score")));
    }

    /** 키워드 대체 검색: 검색어를 많이 포함한 청크 순 (점수 = 포함한 검색어 비율) */
    public List<Hit> searchByKeywords(Long companyId, List<String> terms, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("companyId", companyId).addValue("limit", limit);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            p.addValue("t" + i, "%" + terms.get(i).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            parts.add("(CASE WHEN c.content ILIKE :t" + i + " OR c.section ILIKE :t" + i + " OR d.title ILIKE :t" + i
                    + " THEN 1 ELSE 0 END)");
        }
        String matched = "(" + String.join(" + ", parts) + ")";
        return jdbc.query("SELECT * FROM (SELECT " + HIT_COLUMNS + ", " + matched + " AS matched"
                        + """
                         FROM rule_chunks c JOIN rule_documents d ON d.id = c.document_id
                        WHERE c.company_id = :companyId AND d.company_id = :companyId AND d.status = 'INDEXED') x
                        WHERE x.matched > 0
                        ORDER BY x.matched DESC, x.id
                        LIMIT :limit
                        """, p, (rs, i) -> hit(rs, rs.getInt("matched") / (double) terms.size()));
    }

    /** 문서의 청크 미리보기 (임베딩 제외) */
    public List<Hit> listByDocument(Long companyId, Long documentId) {
        return jdbc.query("SELECT " + HIT_COLUMNS + """
                 FROM rule_chunks c JOIN rule_documents d ON d.id = c.document_id
                WHERE c.company_id = :companyId AND c.document_id = :documentId
                ORDER BY c.chunk_index
                """, Map.of("companyId", companyId, "documentId", documentId), (rs, i) -> hit(rs, 0));
    }

    private static Hit hit(java.sql.ResultSet rs, double score) throws java.sql.SQLException {
        int pageValue = rs.getInt("page");
        Integer page = rs.wasNull() ? null : pageValue;
        return new Hit(rs.getLong("id"), rs.getLong("document_id"), rs.getString("title"), rs.getString("category"),
                rs.getString("section"), page, rs.getString("content"), score);
    }
}
