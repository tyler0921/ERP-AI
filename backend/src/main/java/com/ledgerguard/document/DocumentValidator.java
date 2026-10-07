package com.ledgerguard.document;

import com.ledgerguard.document.Document.DocType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 증빙 필드의 규칙 기반 검증. DB/Spring 의존성이 없는 순수 로직이다.
 * 금액 계산·정합성 판단은 여기서만 하고 AI(LLM)는 하지 않는다.
 */
public final class DocumentValidator {

    private DocumentValidator() {
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Issue(String code, Severity severity, String field, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("severity", severity.name());
            m.put("field", field);
            m.put("message", message);
            return m;
        }
    }

    /** 검증 입력 */
    public record Input(DocType docType, String vendorName, String vendorBusinessNo, String buyerBusinessNo,
                        LocalDate issueDate, BigDecimal supplyAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
    }

    /**
     * @param companyBusinessNo 우리 회사 사업자번호 (세금계산서 공급받는자 확인용, 없으면 null)
     * @param duplicateIds      같은 거래처·거래일·합계의 다른 문서 ID
     * @param confidences       AI 필드별 confidence. 사람이 수정한 뒤의 재검증에서는 빈 맵을 넘긴다.
     */
    public static List<Issue> validate(Input in, LocalDate today, String companyBusinessNo, List<Long> duplicateIds,
                                       Map<String, Double> confidences) {
        List<Issue> issues = new ArrayList<>();
        boolean invoice = in.docType() == DocType.TAX_INVOICE;

        required(issues, "vendorName", in.vendorName(), "거래처(공급자)");
        required(issues, "issueDate", in.issueDate(), "거래일(작성일자)");
        required(issues, "totalAmount", in.totalAmount(), "합계금액");
        if (invoice) {
            required(issues, "supplyAmount", in.supplyAmount(), "공급가액");
            required(issues, "vatAmount", in.vatAmount(), "세액");
            required(issues, "vendorBusinessNo", in.vendorBusinessNo(), "공급자 사업자등록번호");
            required(issues, "buyerBusinessNo", in.buyerBusinessNo(), "공급받는자 사업자등록번호");
        }

        if (in.supplyAmount() != null && in.vatAmount() != null && in.totalAmount() != null
                && in.supplyAmount().add(in.vatAmount()).compareTo(in.totalAmount()) != 0) {
            issues.add(new Issue("SUM_MISMATCH", Severity.ERROR, "totalAmount",
                    "공급가액(" + plain(in.supplyAmount()) + ") + 부가세(" + plain(in.vatAmount()) + ") = "
                            + plain(in.supplyAmount().add(in.vatAmount())) + " 이(가) 합계금액(" + plain(in.totalAmount())
                            + ")과 다릅니다."));
        }

        if (in.supplyAmount() != null && in.vatAmount() != null && in.vatAmount().signum() > 0) {
            BigDecimal expected = in.supplyAmount().multiply(new BigDecimal("0.1"));
            if (in.vatAmount().subtract(expected).abs().compareTo(BigDecimal.ONE) > 0) {
                issues.add(new Issue("VAT_RATE", Severity.WARNING, "vatAmount",
                        "부가세가 공급가액의 10%(" + plain(expected) + ")와 다릅니다."));
            }
        }

        bizNo(issues, "vendorBusinessNo", in.vendorBusinessNo());
        bizNo(issues, "buyerBusinessNo", in.buyerBusinessNo());

        if (in.issueDate() != null && in.issueDate().isAfter(today)) {
            issues.add(new Issue("FUTURE_DATE", Severity.ERROR, "issueDate", "거래일이 오늘 이후입니다."));
        }

        if (invoice && companyBusinessNo != null && in.buyerBusinessNo() != null
                && !digits(companyBusinessNo).equals(digits(in.buyerBusinessNo()))) {
            issues.add(new Issue("BUYER_MISMATCH", Severity.WARNING, "buyerBusinessNo",
                    "공급받는자 사업자번호가 우리 회사(" + companyBusinessNo + ")와 다릅니다."));
        }

        if (!duplicateIds.isEmpty()) {
            issues.add(new Issue("POSSIBLE_DUPLICATE", Severity.WARNING, null,
                    "같은 거래처·거래일·합계금액의 증빙이 이미 있습니다: #"
                            + String.join(", #", duplicateIds.stream().map(String::valueOf).toList())));
        }

        confidences.forEach((field, confidence) -> {
            if (confidence != null && confidence > 0 && confidence < 0.6) {
                issues.add(new Issue("LOW_CONFIDENCE", Severity.WARNING, field,
                        "인식 신뢰도가 낮습니다(" + Math.round(confidence * 100) + "%). 원본과 비교해 확인하세요."));
            }
        });
        return issues;
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    /** 국세청 사업자등록번호 검증 알고리즘 */
    public static boolean bizNoChecksumOk(String bizNo) {
        String d = digits(bizNo);
        if (d.length() != 10) {
            return false;
        }
        int[] weights = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (d.charAt(i) - '0') * weights[i];
        }
        sum += ((d.charAt(8) - '0') * 5) / 10;
        return (10 - sum % 10) % 10 == d.charAt(9) - '0';
    }

    private static void required(List<Issue> issues, String field, Object value, String label) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            issues.add(new Issue("REQUIRED_MISSING", Severity.ERROR, field, label + "이(가) 비어 있습니다."));
        }
    }

    private static void bizNo(List<Issue> issues, String field, String value) {
        if (value != null && !bizNoChecksumOk(value)) {
            issues.add(new Issue("BIZ_NO_INVALID", Severity.ERROR, field,
                    "사업자등록번호(" + value + ")의 검증번호가 맞지 않습니다. 오인식 여부를 확인하세요."));
        }
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
