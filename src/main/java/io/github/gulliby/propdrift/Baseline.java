package io.github.gulliby.propdrift;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses, renders, and applies propdrift finding baselines.
 *
 * <p>Version 1 baselines are earlier plain-text propdrift output. Version 2
 * baselines use a stable, escaped tab-separated format.</p>
 */
public final class Baseline {
    /** The header identifying a version 2 baseline. */
    public static final String HEADER = "# propdrift baseline v2";

    private static final String VERSION_PREFIX = "# propdrift baseline v";
    private static final Pattern VERSION_ONE_ENTRY =
            Pattern.compile("^(.+?):[0-9]+: (PR[0-9]{3}) (.*)$");

    private final int version;
    private final List<Entry> entries;

    private Baseline(int version, List<Entry> entries) {
        this.version = version;
        this.entries = List.copyOf(entries);
    }

    /**
     * Decodes a UTF-8 baseline strictly and parses its entries.
     *
     * @param bytes baseline file bytes
     * @return the parsed baseline
     * @throws CharacterCodingException if the bytes are not valid UTF-8
     * @throws BaselineException if a version 2 header or entry is malformed
     */
    public static Baseline parse(byte[] bytes)
            throws CharacterCodingException, BaselineException {
        Objects.requireNonNull(bytes, "bytes");
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return parse(text);
    }

    /**
     * Parses baseline text, accepting version 1 text output or version 2 data.
     *
     * @param text baseline text
     * @return the parsed baseline
     * @throws BaselineException if a version 2 header or entry is malformed
     */
    public static Baseline parse(String text) throws BaselineException {
        Objects.requireNonNull(text, "text");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<String> lines = splitLines(text);
        if (!lines.isEmpty() && lines.get(0).equals(HEADER)) {
            return parseVersionTwo(lines);
        }
        if (!lines.isEmpty() && lines.get(0).startsWith(VERSION_PREFIX)) {
            throw new BaselineException(0, "unsupported baseline version");
        }
        return parseVersionOne(lines);
    }

    /** Returns the baseline format version, either 1 or 2. */
    public int version() {
        return version;
    }

    /** Returns the number of entries, counting duplicate entries. */
    public int size() {
        return entries.size();
    }

    /**
     * Removes findings matched by baseline entries, preserving the input order.
     *
     * @param findings findings to compare with this baseline
     * @return remaining findings and the number suppressed
     */
    public Applied apply(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        Map<EntryKey, Integer> counts = new HashMap<>();
        for (Entry entry : entries) {
            counts.merge(entry.key(), 1, Integer::sum);
        }

        List<Finding> remaining = new ArrayList<>();
        int suppressed = 0;
        for (Finding finding : findings) {
            Objects.requireNonNull(finding, "finding");
            EntryKey key = new EntryKey(finding.rule(), finding.fileName(), finding.message());
            int count = counts.getOrDefault(key, 0);
            if (count > 0) {
                suppressed++;
                if (count == 1) {
                    counts.remove(key);
                } else {
                    counts.put(key, count - 1);
                }
            } else {
                remaining.add(finding);
            }
        }
        return new Applied(remaining, suppressed);
    }

    /**
     * Renders findings as a version 2 baseline, sorted by file name, rule, and
     * message.
     *
     * @param findings findings to render
     * @return LF-terminated version 2 baseline text
     */
    public static String render(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.forEach(finding -> Objects.requireNonNull(finding, "finding"));
        sorted.sort((left, right) -> {
            int byFileName = left.fileName().compareTo(right.fileName());
            if (byFileName != 0) {
                return byFileName;
            }
            int byRule = left.rule().compareTo(right.rule());
            if (byRule != 0) {
                return byRule;
            }
            return left.message().compareTo(right.message());
        });

        StringBuilder result = new StringBuilder(HEADER).append('\n');
        for (Finding finding : sorted) {
            result.append(finding.rule())
                    .append('\t')
                    .append(escape(finding.fileName()))
                    .append('\t')
                    .append(escape(finding.message()))
                    .append('\n');
        }
        return result.toString();
    }

    private static Baseline parseVersionOne(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = VERSION_ONE_ENTRY.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String path = matcher.group(1);
            int slash = path.lastIndexOf('/');
            String fileName = path.substring(slash + 1);
            if (!fileName.isEmpty()) {
                entries.add(new Entry(matcher.group(2), fileName, matcher.group(3)));
            }
        }
        return new Baseline(1, entries);
    }

    private static Baseline parseVersionTwo(List<String> lines) throws BaselineException {
        List<Entry> entries = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int firstTab = line.indexOf('\t');
            int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
            if (firstTab < 0 || secondTab < 0 || line.indexOf('\t', secondTab + 1) >= 0) {
                throw malformed(index + 1);
            }

            String rule = line.substring(0, firstTab);
            if (!isRule(rule)) {
                throw malformed(index + 1);
            }
            String fileName = unescape(line.substring(firstTab + 1, secondTab), index + 1);
            String message = unescape(line.substring(secondTab + 1), index + 1);
            entries.add(new Entry(rule, fileName, message));
        }
        return new Baseline(2, entries);
    }

    private static BaselineException malformed(int line) {
        return new BaselineException(line, "malformed baseline entry");
    }

    private static boolean isRule(String rule) {
        if (rule.length() != 5 || rule.charAt(0) != 'P' || rule.charAt(1) != 'R') {
            return false;
        }
        for (int index = 2; index < rule.length(); index++) {
            char ch = rule.charAt(index);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static String unescape(String field, int line) throws BaselineException {
        StringBuilder decoded = new StringBuilder(field.length());
        for (int index = 0; index < field.length(); index++) {
            char ch = field.charAt(index);
            if (ch != '\\') {
                decoded.append(ch);
                continue;
            }
            if (index + 1 >= field.length()) {
                throw malformed(line);
            }
            char escaped = field.charAt(++index);
            switch (escaped) {
                case '\\' -> decoded.append('\\');
                case 't' -> decoded.append('\t');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                default -> throw malformed(line);
            }
        }
        return decoded.toString();
    }

    private static String escape(String field) {
        StringBuilder escaped = new StringBuilder(field.length());
        for (int index = 0; index < field.length(); index++) {
            char ch = field.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                int end = index;
                if (end > start && text.charAt(end - 1) == '\r') {
                    end--;
                }
                lines.add(text.substring(start, end));
                start = index + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private record Entry(String rule, String fileName, String message) {
        private EntryKey key() {
            return new EntryKey(rule, fileName, message);
        }
    }

    private record EntryKey(String rule, String fileName, String message) {
    }

    /** The result of applying a baseline to findings. */
    public record Applied(List<Finding> remaining, int suppressed) {
        public Applied {
            remaining = List.copyOf(Objects.requireNonNull(remaining, "remaining"));
        }
    }

    /** A malformed or unsupported version 2 baseline. */
    public static final class BaselineException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int line;

        private BaselineException(int line, String message) {
            super(message);
            this.line = line;
        }

        /** Returns the malformed physical line, or zero for an unsupported version. */
        public int line() {
            return line;
        }
    }
}
