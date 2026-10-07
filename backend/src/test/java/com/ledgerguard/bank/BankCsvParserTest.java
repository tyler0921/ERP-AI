package com.ledgerguard.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.bank.BankCsvParser.ParseResult;
import com.ledgerguard.bank.BankCsvParser.ParsedRow;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.transaction.Direction;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class BankCsvParserTest {

    private final BankCsvParser parser = new BankCsvParser();

    private ParseResult parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 입금_출금_분리_컬럼을_정규화한다() {
        ParseResult result = parse("""
                거래일자,거래시간,출금액(원),입금액(원),잔액,거래처,적요
                2026-10-08,14:32:11,"1,253,000",,"8,747,000",(주)가상물산,매입대금
                2026.10.10,,0,"5,500,000원",14247000,한빛상사,
                """);

        assertThat(result.encoding()).isEqualTo("UTF-8");
        assertThat(result.rows()).hasSize(2);

        ParsedRow out = result.rows().get(0);
        assertThat(out.date()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(out.time()).isEqualTo(LocalTime.of(14, 32, 11));
        assertThat(out.direction()).isEqualTo(Direction.OUT);
        assertThat(out.amount()).isEqualByComparingTo("1253000");
        assertThat(out.balance()).isEqualByComparingTo("8747000");
        assertThat(out.counterparty()).isEqualTo("(주)가상물산");
        assertThat(out.memo()).isEqualTo("매입대금"); // 거래처 컬럼이 따로 있으면 적요는 메모로

        ParsedRow in = result.rows().get(1);
        assertThat(in.direction()).isEqualTo(Direction.IN);
        assertThat(in.amount()).isEqualByComparingTo("5500000");
        assertThat(in.time()).isNull();
    }

    @Test
    void CP949_인코딩과_헤더_앞의_제목행을_처리한다() {
        String csv = """
                거래내역조회
                계좌번호,123-456-789012
                거래일시,찾으신금액,맡기신금액,내용
                20261008 0930,"3,000",,송금수수료
                """;
        ParseResult result = parser.parse(csv.getBytes(Charset.forName("x-windows-949")));

        assertThat(result.encoding()).isEqualTo("CP949");
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNo()).isEqualTo(4);
            assertThat(row.time()).isEqualTo(LocalTime.of(9, 30));
            assertThat(row.direction()).isEqualTo(Direction.OUT);
            assertThat(row.counterparty()).isEqualTo("송금수수료"); // 거래처 컬럼이 없으면 적요/내용을 거래처로
        });
    }

    @Test
    void 금액과_구분_컬럼_또는_부호있는_금액을_지원한다() {
        ParseResult typed = parse("""
                date,type,amount,counterparty
                2026/10/08,출금,330000,네이버광고
                2026/10/09,입금,1000,환불
                """);
        assertThat(typed.rows()).extracting(ParsedRow::direction).containsExactly(Direction.OUT, Direction.IN);

        ParseResult signed = parse("""
                날짜,금액,적요
                2026-10-08,-330000,네이버광고
                2026-10-09,1000.50,이자
                """);
        assertThat(signed.rows().get(0).direction()).isEqualTo(Direction.OUT);
        assertThat(signed.rows().get(0).amount()).isEqualByComparingTo("330000");
        assertThat(signed.rows().get(1).amount()).isEqualByComparingTo(new BigDecimal("1000.50"));
    }

    @Test
    void 잘못된_행이_있으면_전체를_거부하고_행번호를_알려준다() {
        assertThatThrownBy(() -> parse("""
                거래일자,출금액,입금액,거래처
                2026-10-08,1000,,A
                2026-13-01,1000,,B
                2026-10-09,1000,2000,C
                2026-10-10,,,D
                2026-10-11,1.234,,E
                """))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_ROW_INVALID);
                    assertThat(e.getDetails()).extracting(d -> d.field())
                            .containsExactly("3행", "4행", "5행", "6행");
                });
    }

    @Test
    void 필수_헤더가_없거나_빈_파일이면_파일_오류() {
        assertThatThrownBy(() -> parse("이름,나이\n홍길동,30\n"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID));
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID));
        assertThatThrownBy(() -> parse("거래일자,출금액,입금액\n"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID));
    }

    @Test
    void UTF8_BOM을_제거한다() {
        ParseResult result = parse("﻿거래일자,출금액,입금액\n2026-10-08,100,\n");
        assertThat(result.rows()).hasSize(1);
    }
}
