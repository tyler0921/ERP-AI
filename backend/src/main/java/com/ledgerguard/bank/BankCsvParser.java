package com.ledgerguard.bank;

import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.transaction.Direction;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * 은행 거래내역 CSV를 정규화된 행 목록으로 변환한다. DB/Spring 의존성이 없는 순수 로직이다.
 *
 * <ul>
 *   <li>인코딩: UTF-8(BOM 허용)을 먼저 시도하고, 실패하면 CP949(국내 은행 기본)로 읽는다.</li>
 *   <li>헤더: 은행마다 다른 컬럼명을 별칭으로 인식한다. 헤더 앞의 제목·계좌정보 행은 건너뛴다.</li>
 *   <li>금액 형식: 입금/출금 분리 컬럼, 또는 금액 + 구분 컬럼, 또는 부호 있는 금액 컬럼.</li>
 *   <li>전부 성공 아니면 전부 실패: 잘못된 행이 하나라도 있으면 행 번호와 사유를 담아 예외를 던진다.</li>
 * </ul>
 */
@Component
public class BankCsvParser {

    public static final int MAX_ROWS = 5000;
    private static final int MAX_ERRORS = 50;
    private static final int HEADER_SEARCH_LIMIT = 20;
    private static final Charset CP949 = Charset.forName("x-windows-949");

    public record ParsedRow(int rowNo, LocalDate date, LocalTime time, Direction direction, BigDecimal amount,
                            BigDecimal balance, String counterparty, String memo) {
    }

    public record ParseResult(String encoding, List<ParsedRow> rows) {
    }

    // 컬럼 별칭 (정규화된 헤더 기준: 소문자, 공백·괄호내용·기호 제거)
    private static final List<String> DATE = List.of("거래일자", "거래일", "거래일시", "날짜", "일자", "date", "transactiondate");
    private static final List<String> TIME = List.of("거래시간", "시간", "time");
    private static final List<String> OUT = List.of("출금액", "출금", "찾으신금액", "지급액", "withdrawal", "out");
    private static final List<String> IN = List.of("입금액", "입금", "맡기신금액", "deposit", "in");
    private static final List<String> AMOUNT = List.of("금액", "거래금액", "amount");
    private static final List<String> TYPE = List.of("구분", "입출금구분", "거래구분", "입출구분", "type", "direction");
    private static final List<String> BALANCE = List.of("잔액", "거래후잔액", "balance");
    private static final List<String> COUNTERPARTY = List.of("거래처", "받는분", "보낸분", "의뢰인", "상대방", "counterparty");
    private static final List<String> DESCRIPTION = List.of("적요", "내용", "거래내용", "description");
    private static final List<String> MEMO = List.of("메모", "비고", "memo", "note");

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^(\\d{4})[-./]?(\\d{1,2})[-./]?(\\d{1,2})(?:[ T](\\d{1,2}):?(\\d{2})(?::?(\\d{2}))?)?$");
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):?(\\d{2})(?::?(\\d{2}))?$");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^-?\\d{1,17}(\\.\\d{1,2})?$");

    private record Columns(int date, int time, int out, int in, int amount, int type, int balance,
                           int counterparty, int description, int memo) {
    }

    public ParseResult parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw fileInvalid("빈 파일입니다.");
        }
        Decoded decoded = decode(content);
        List<CSVRecord> records = readRecords(decoded.text());

        int headerIndex = -1;
        Columns columns = null;
        for (int i = 0; i < Math.min(records.size(), HEADER_SEARCH_LIMIT); i++) {
            columns = detectColumns(records.get(i));
            if (columns != null) {
                headerIndex = i;
                break;
            }
        }
        if (columns == null) {
            throw fileInvalid("거래일자와 입금/출금(또는 금액) 컬럼이 있는 헤더 행을 찾을 수 없습니다.");
        }

        List<CSVRecord> dataRecords = records.subList(headerIndex + 1, records.size());
        if (dataRecords.isEmpty()) {
            throw fileInvalid("거래 행이 없습니다.");
        }
        if (dataRecords.size() > MAX_ROWS) {
            throw fileInvalid("한 번에 최대 " + MAX_ROWS + "행까지 업로드할 수 있습니다. (현재 " + dataRecords.size() + "행)");
        }

        List<ParsedRow> rows = new ArrayList<>();
        List<ApiError.Detail> errors = new ArrayList<>();
        for (CSVRecord record : dataRecords) {
            int rowNo = (int) record.getRecordNumber();
            if (isBlankRecord(record)) {
                continue;
            }
            try {
                rows.add(parseRow(rowNo, record, columns));
            } catch (RowException e) {
                if (errors.size() < MAX_ERRORS) {
                    errors.add(new ApiError.Detail(rowNo + "행", e.getMessage()));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_ROW_INVALID, ErrorCode.IMPORT_ROW_INVALID.defaultMessage(), errors);
        }
        if (rows.isEmpty()) {
            throw fileInvalid("거래 행이 없습니다.");
        }
        return new ParseResult(decoded.encoding(), rows);
    }

    // ------------------------------------------------------------------ decoding

    private record Decoded(String text, String encoding) {
    }

    private Decoded decode(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            return new Decoded(stripBom(text), "UTF-8");
        } catch (CharacterCodingException notUtf8) {
            try {
                String text = CP949.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content))
                        .toString();
                return new Decoded(text, "CP949");
            } catch (CharacterCodingException e) {
                throw fileInvalid("UTF-8 또는 CP949(EUC-KR) 인코딩의 CSV 파일만 지원합니다.");
            }
        }
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private List<CSVRecord> readRecords(String text) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            return parser.getRecords();
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException e) {
            throw fileInvalid("CSV 형식이 올바르지 않습니다. (따옴표가 닫히지 않았는지 확인하세요)");
        }
    }

    // ------------------------------------------------------------------- columns

    /** 헤더 후보 행에서 컬럼 위치를 찾는다. 필수 컬럼이 없으면 null. */
    private Columns detectColumns(CSVRecord record) {
        List<String> headers = new ArrayList<>();
        record.forEach(h -> headers.add(normalizeHeader(h)));

        int date = find(headers, DATE);
        int out = find(headers, OUT);
        int in = find(headers, IN);
        int amount = find(headers, AMOUNT);
        if (date < 0 || ((out < 0 || in < 0) && amount < 0)) {
            return null;
        }
        return new Columns(date, find(headers, TIME), out, in, amount, find(headers, TYPE), find(headers, BALANCE),
                find(headers, COUNTERPARTY), find(headers, DESCRIPTION), find(headers, MEMO));
    }

    /** 별칭 우선순위대로 정확히 일치하는 첫 컬럼 */
    private static int find(List<String> headers, List<String> aliases) {
        for (String alias : aliases) {
            int index = headers.indexOf(alias);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    static String normalizeHeader(String header) {
        return header.toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("[\\s_\\-./]", "")
                .trim();
    }

    // ----------------------------------------------------------------------- row

    private static final class RowException extends Exception {
        RowException(String message) {
            super(message);
        }
    }

    private ParsedRow parseRow(int rowNo, CSVRecord record, Columns c) throws RowException {
        String dateText = get(record, c.date());
        if (dateText == null) {
            throw new RowException("거래일자가 비어 있습니다.");
        }
        Matcher m = DATE_PATTERN.matcher(dateText);
        if (!m.matches()) {
            throw new RowException("거래일자 형식을 알 수 없습니다: " + dateText);
        }
        LocalDate date;
        LocalTime time = null;
        try {
            date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (m.group(4) != null) {
                time = LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                        m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            }
        } catch (DateTimeException e) {
            throw new RowException("존재하지 않는 날짜입니다: " + dateText);
        }
        if (time == null && c.time() >= 0) {
            time = parseTime(get(record, c.time()));
        }

        Direction direction;
        BigDecimal amount;
        if (c.out() >= 0 && c.in() >= 0) {
            BigDecimal out = parseAmount(get(record, c.out()), "출금액", false);
            BigDecimal in = parseAmount(get(record, c.in()), "입금액", false);
            boolean hasOut = out.signum() > 0;
            boolean hasIn = in.signum() > 0;
            if (hasOut == hasIn) {
                throw new RowException("입금액과 출금액 중 정확히 하나만 0보다 커야 합니다.");
            }
            direction = hasOut ? Direction.OUT : Direction.IN;
            amount = hasOut ? out : in;
        } else {
            BigDecimal signed = parseAmount(get(record, c.amount()), "금액", true);
            if (signed.signum() == 0) {
                throw new RowException("금액이 0입니다.");
            }
            Direction byType = c.type() >= 0 ? parseType(get(record, c.type())) : null;
            if (byType != null) {
                direction = byType;
            } else if (c.type() >= 0 && get(record, c.type()) != null) {
                throw new RowException("입출금 구분을 알 수 없습니다: " + get(record, c.type()));
            } else {
                direction = signed.signum() < 0 ? Direction.OUT : Direction.IN;
            }
            amount = signed.abs();
        }

        BigDecimal balance = c.balance() >= 0 && get(record, c.balance()) != null
                ? parseAmount(get(record, c.balance()), "잔액", true)
                : null;

        String counterparty = c.counterparty() >= 0 ? get(record, c.counterparty()) : null;
        String description = c.description() >= 0 ? get(record, c.description()) : null;
        String memo = c.memo() >= 0 ? get(record, c.memo()) : null;
        if (counterparty == null) {
            counterparty = description;
        } else if (memo == null) {
            memo = description;
        }

        return new ParsedRow(rowNo, date, time, direction, amount, balance,
                truncate(counterparty, 200), truncate(memo, 500));
    }

    private static LocalTime parseTime(String text) throws RowException {
        if (text == null) {
            return null;
        }
        Matcher m = TIME_PATTERN.matcher(text);
        if (!m.matches()) {
            throw new RowException("거래시간 형식을 알 수 없습니다: " + text);
        }
        try {
            return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            throw new RowException("존재하지 않는 시간입니다: " + text);
        }
    }

    /** 쉼표·공백·통화 기호를 제거한다. 빈 값은 0. */
    private static BigDecimal parseAmount(String text, String label, boolean allowNegative) throws RowException {
        if (text == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        String cleaned = text.replaceAll("[,\\s원₩]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (!AMOUNT_PATTERN.matcher(cleaned).matches()) {
            throw new RowException(label + " 형식이 올바르지 않습니다: " + text);
        }
        BigDecimal value = new BigDecimal(cleaned).setScale(2);
        if (!allowNegative && value.signum() < 0) {
            throw new RowException(label + "은(는) 음수일 수 없습니다: " + text);
        }
        return value;
    }

    private static Direction parseType(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (t.contains("출금") || t.contains("지급") || t.equals("out") || t.equals("withdrawal")) {
            return Direction.OUT;
        }
        if (t.contains("입금") || t.equals("in") || t.equals("deposit")) {
            return Direction.IN;
        }
        return null;
    }

    private static String get(CSVRecord record, int index) {
        if (index < 0 || index >= record.size()) {
            return null;
        }
        String value = record.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlankRecord(CSVRecord record) {
        for (String value : record) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static ApiException fileInvalid(String reason) {
        return new ApiException(ErrorCode.IMPORT_FILE_INVALID, ErrorCode.IMPORT_FILE_INVALID.defaultMessage(),
                List.of(new ApiError.Detail("file", reason)));
    }
}
