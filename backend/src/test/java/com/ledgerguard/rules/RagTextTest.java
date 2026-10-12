package com.ledgerguard.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 규정 파일 형식 판별(RuleFileKind)과 RAG 보조 계산(RagText) */
class RagTextTest {

    @Test
    void 파일_형식은_바이트로_판별한다() {
        assertThat(RuleFileKind.sniff("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII), "a.txt")).isEqualTo(RuleFileKind.PDF);
        byte[] docx = ("PK\u0003\u0004 ... [Content_Types].xml ... word/document.xml ...").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(RuleFileKind.sniff(docx, "규정.docx")).isEqualTo(RuleFileKind.DOCX);
        byte[] zip = "PK\u0003\u0004 ... other.xml".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(RuleFileKind.sniff(zip, "a.docx")).isNull(); // 워드 문서가 아닌 ZIP
        assertThat(RuleFileKind.sniff("# 경비 규정\n제1조".getBytes(StandardCharsets.UTF_8), "경비.md")).isEqualTo(RuleFileKind.MARKDOWN);
        assertThat(RuleFileKind.sniff("제1조 목적".getBytes(Charset.forName("MS949")), "old.txt")).isEqualTo(RuleFileKind.TEXT);
        assertThat(RuleFileKind.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0}, "x.png")).isNull();
        assertThat(RuleFileKind.sniff(new byte[] {'a', 0, 'b'}, "x.txt")).isNull();
        assertThat(RuleFileKind.sniff(new byte[0], "x.txt")).isNull();
    }

    @Test
    void 파일_이름은_경로를_지우고_실제_형식으로_확장자를_맞춘다() {
        assertThat(RuleDocumentService.sanitizeFileName("../../etc/경비 규정.exe", RuleFileKind.PDF)).isEqualTo("경비 규정.pdf");
        assertThat(RuleDocumentService.sanitizeFileName("C:\\docs\\카드\u0000규정.md", RuleFileKind.MARKDOWN)).isEqualTo("카드규정.md");
        assertThat(RuleDocumentService.sanitizeFileName(null, RuleFileKind.TEXT)).isEqualTo("rule.txt");
    }

    @Test
    void 벡터_리터럴은_차원과_값을_검증한다() {
        assertThat(RagText.vectorLiteral(List.of(0.5, -0.25, 1), 3)).isEqualTo("[0.5,-0.25,1.0]");
        assertThatThrownBy(() -> RagText.vectorLiteral(List.of(0.1, 0.2), 3)).hasMessageContaining("차원");
        assertThatThrownBy(() -> RagText.vectorLiteral(List.of(0.1, Double.NaN, 0.2), 3)).hasMessageContaining("올바르지 않은 값");
        List<Double> withNull = new ArrayList<>(Collections.nCopies(3, 0.1));
        withNull.set(1, null);
        assertThatThrownBy(() -> RagText.vectorLiteral(withNull, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagText.vectorLiteral(null, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 키워드는_조사와_불용어를_뺀다() {
        assertThat(RagText.keywordTerms("법인카드로 주말에 회식비를 결제해도 되나요?"))
                .containsExactly("법인카드", "주말", "회식비", "결제해");
        assertThat(RagText.keywordTerms("접대비 한도는 얼마인가요? 접대비 증빙은?")).containsExactly("접대비", "한도", "얼마인가요", "증빙");
        assertThat(RagText.keywordTerms("어떻게 해야 하나요")).isEmpty();
        assertThat(RagText.keywordTerms("가 나 다 라 마 바 사 아 자 차 카 타 파 하 거 너 더 러 머")).isEmpty(); // 1글자
    }

    @Test
    @SuppressWarnings("unchecked")
    void 답변_근거는_번호_붙은_발췌() {
        Map<String, Object> facts = RagText.facts("접대비 한도는?", List.of(
                Map.of("title", "경비 처리 규정", "section", "제3장 경비 > 제12조(접대비)", "page", 3, "content", "1회 300,000원을 넘을 수 없다."),
                Map.of("title", "계정과목 가이드", "content", "접대비는 거래처 접대에 쓴 비용이다.")));
        assertThat(facts.get("질문")).isEqualTo("접대비 한도는?");
        List<Map<String, Object>> excerpts = (List<Map<String, Object>>) facts.get("규정 발췌");
        assertThat(excerpts.get(0)).containsEntry("번호", 1)
                .containsEntry("출처", "경비 처리 규정 › 제3장 경비 > 제12조(접대비) (p.3)")
                .containsEntry("본문", "1회 300,000원을 넘을 수 없다.");
        assertThat(excerpts.get(1)).containsEntry("번호", 2).containsEntry("출처", "계정과목 가이드");
    }
}
