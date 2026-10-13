package io.github.gulliby.propdrift;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses the supported subset of Java properties files while retaining the
 * source line of each entry.
 */
public final class PropertiesParser {
    private PropertiesParser() {
    }

    /**
     * Decodes UTF-8 strictly, removes one optional leading BOM, and parses the
     * resulting properties text.
     *
     * @throws CharacterCodingException if the byte sequence is not valid UTF-8
     * @throws MalformedUnicodeEscapeException if a Unicode escape is malformed
     */
    public static List<Entry> parse(byte[] bytes)
            throws CharacterCodingException, MalformedUnicodeEscapeException {
        Objects.requireNonNull(bytes, "bytes");
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return parse(text);
    }

    /**
     * Parses properties text and retains duplicate entries for later analysis.
     *
     * @throws MalformedUnicodeEscapeException if a Unicode escape is malformed
     */
    public static List<Entry> parse(String text) throws MalformedUnicodeEscapeException {
        Objects.requireNonNull(text, "text");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<String> physicalLines = splitPhysicalLines(text);
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        while (index < physicalLines.size()) {
            String physical = physicalLines.get(index);
            String logical = stripLeadingWhitespace(physical);
            int firstLine = index + 1;
            index++;

            if (logical.isEmpty() || logical.charAt(0) == '#' || logical.charAt(0) == '!') {
                continue;
            }

            while (hasOddTrailingBackslashes(logical)) {
                logical = logical.substring(0, logical.length() - 1);
                if (index >= physicalLines.size()) {
                    break;
                }
                logical += stripLeadingWhitespace(physicalLines.get(index));
                index++;
            }

            entries.add(parseEntry(logical, firstLine));
        }
        return List.copyOf(entries);
    }

    private static List<String> splitPhysicalLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                lines.add(text.substring(start, i));
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static String stripLeadingWhitespace(String text) {
        int start = 0;
        while (start < text.length() && isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private static boolean isWhitespace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\f';
    }

    private static boolean hasOddTrailingBackslashes(String text) {
        int count = 0;
        for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    private static Entry parseEntry(String text, int line) throws MalformedUnicodeEscapeException {
        int delimiter = 0;
        while (delimiter < text.length()) {
            char ch = text.charAt(delimiter);
            if (ch == '=' || ch == ':' || isWhitespace(ch)) {
                break;
            }
            if (ch == '\\' && delimiter + 1 < text.length()) {
                delimiter += 2;
            } else {
                delimiter++;
            }
        }

        String rawKey = text.substring(0, delimiter);
        int valueStart = delimiter;
        while (valueStart < text.length() && isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart < text.length()
                && (text.charAt(valueStart) == '=' || text.charAt(valueStart) == ':')) {
            valueStart++;
        }
        while (valueStart < text.length() && isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }

        String key = decodeEscapes(rawKey, line);
        String value = decodeEscapes(text.substring(valueStart), line);
        return new Entry(key, value, line);
    }

    private static String decodeEscapes(String text, int line)
            throws MalformedUnicodeEscapeException {
        StringBuilder decoded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\\') {
                decoded.append(ch);
                continue;
            }

            // A trailing backslash is normally consumed as a continuation marker.
            // Treat a remaining one as a literal backslash to keep decoding total.
            if (i + 1 >= text.length()) {
                decoded.append('\\');
                continue;
            }

            char escaped = text.charAt(++i);
            switch (escaped) {
                case 't' -> decoded.append('\t');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 'f' -> decoded.append('\f');
                case 'u' -> {
                    if (i + 4 >= text.length()) {
                        throw new MalformedUnicodeEscapeException(line);
                    }
                    int value = 0;
                    for (int digitIndex = 1; digitIndex <= 4; digitIndex++) {
                        int digit = hexValue(text.charAt(i + digitIndex));
                        if (digit < 0) {
                            throw new MalformedUnicodeEscapeException(line);
                        }
                        value = (value << 4) | digit;
                    }
                    decoded.append((char) value);
                    i += 4;
                }
                default -> decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    private static int hexValue(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        return -1;
    }

    /** A parsed key/value pair and its first physical source line. */
    public record Entry(String key, String value, int line) {
    }

    /** Thrown when a {@code \\u} escape is not followed by four hex digits. */
    public static final class MalformedUnicodeEscapeException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int line;

        public MalformedUnicodeEscapeException(int line) {
            super("malformed \\u escape");
            this.line = line;
        }

        public int line() {
            return line;
        }

        public int getLine() {
            return line;
        }
    }
}
