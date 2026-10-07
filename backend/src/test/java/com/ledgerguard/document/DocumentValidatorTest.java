package com.ledgerguard.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.document.Document.DocType;
import com.ledgerguard.document.DocumentValidator.Input;
import com.ledgerguard.document.DocumentValidator.Issue;
import com.ledgerguard.document.DocumentValidator.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private static Input invoice(String supply, String vat, String total, String vendorBiz, String buyerBiz) {
        return new Input(DocType.TAX_INVOICE, "(주)가상소프트", vendorBiz, buyerBiz, LocalDate.of(2026, 10, 5),
                dec(supply), dec(vat), dec(total));
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    private static List<Issue> validate(Input in) {
        return DocumentValidator.validate(in, TODAY, "123-45-67891", List.of(), Map.of());
    }

    @Test
    void 정상_세금계산서는_이슈가_없다() {
        assertThat(validate(invoice("3000000", "300000", "3300000", "314-86-12340", "123-45-67891"))).isEmpty();
    }

    @Test
    void 공급가액_더하기_부가세가_합계와_다르면_오류() {
        List<Issue> issues = validate(invoice("3000000", "300000", "3200000", "314-86-12340", "123-45-67891"));
        assertThat(codes(issues)).containsExactly("SUM_MISMATCH");
        assertThat(issues.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(issues.get(0).message()).contains("3300000").contains("3200000");
    }

    @Test
    void 부가세가_10퍼센트에서_1원_넘게_벗어나면_경고() {
        assertThat(codes(validate(invoice("16364", "1636", "18000", "314-86-12340", "123-45-67891")))).isEmpty();
        assertThat(codes(validate(invoice("3000000", "200000", "3200000", "314-86-12340", "123-45-67891"))))
                .containsExactly("VAT_RATE");
    }

    @Test
    void 사업자번호_체크섬과_공급받는자_확인() {
        assertThat(DocumentValidator.bizNoChecksumOk("314-86-12340")).isTrue();
        assertThat(DocumentValidator.bizNoChecksumOk("123-45-67890")).isFalse();
        assertThat(codes(validate(invoice("3000000", "300000", "3300000", "314-86-12341", "211-88-55554"))))
                .containsExactly("BIZ_NO_INVALID", "BUYER_MISMATCH");
    }

    @Test
    void 세금계산서_필수값_누락() {
        List<Issue> issues = validate(new Input(DocType.TAX_INVOICE, null, null, null, null, null, null, null));
        assertThat(issues).allMatch(i -> i.code().equals("REQUIRED_MISSING") && i.severity() == Severity.ERROR);
        assertThat(issues).extracting(Issue::field).containsExactlyInAnyOrder("vendorName", "issueDate", "totalAmount",
                "supplyAmount", "vatAmount", "vendorBusinessNo", "buyerBusinessNo");
    }

    @Test
    void 영수증은_공급가액_부가세가_없어도_된다() {
        Input receipt = new Input(DocType.RECEIPT, "다이소", null, null, LocalDate.of(2026, 10, 1), null, null,
                new BigDecimal("8000"));
        assertThat(validate(receipt)).isEmpty();
    }

    @Test
    void 미래_날짜_중복_의심_낮은_신뢰도() {
        Input future = new Input(DocType.RECEIPT, "가상커피", "211-88-55554", null, TODAY.plusDays(1), null, null,
                new BigDecimal("18000"));
        List<Issue> issues = DocumentValidator.validate(future, TODAY, null, List.of(7L, 9L),
                Map.of("vendorName", 0.42, "totalAmount", 0.97));
        assertThat(codes(issues)).containsExactly("FUTURE_DATE", "POSSIBLE_DUPLICATE", "LOW_CONFIDENCE");
        assertThat(issues.get(1).message()).contains("#7, #9");
        assertThat(issues.get(2).field()).isEqualTo("vendorName");
        assertThat(DocumentValidator.hasErrors(issues)).isTrue();
    }
}
