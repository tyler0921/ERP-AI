package com.ledgerguard.rules;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 규정 문서 형식 판별. 순수 클래스. 확장자·Content-Type이 아니라 실제 바이트로 판별한다.
 * PDF(%PDF), DOCX(ZIP + word/document.xml), 텍스트(UTF-8 또는 CP949로 깨짐 없이 읽히고 NUL 없음).
 */
public enum RuleFileKind {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain");

    public final String extension;
    public final String contentType;

    RuleFileKind(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    private static final byte[] DOCX_ENTRY = "word/document.xml".getBytes(StandardCharsets.US_ASCII);

    /** @return 형식, 지원하지 않으면 null */
    public static RuleFileKind sniff(byte[] b, String fileName) {
        if (b == null || b.length == 0) {
            return null;
        }
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return PDF;
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return indexOf(b, DOCX_ENTRY) >= 0 ? DOCX : null;
        }
        if (isText(b)) {
            String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            return name.endsWith(".md") || name.endsWith(".markdown") ? MARKDOWN : TEXT;
        }
        return null;
    }

    static boolean isText(byte[] b) {
        for (byte x : b) {
            if (x == 0) {
                return false;
            }
        }
        return decodes(b, StandardCharsets.UTF_8) || decodes(b, Charset.forName("MS949"));
    }

    private static boolean decodes(byte[] b, Charset charset) {
        try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
