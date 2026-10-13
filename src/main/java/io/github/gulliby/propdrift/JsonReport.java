package io.github.gulliby.propdrift;

import java.util.List;
import java.util.Objects;

/** Renders checker findings as a compact JSON report. */
public final class JsonReport {
    private JsonReport() {
    }

    /**
     * Renders findings as one compact JSON object without a trailing line feed.
     *
     * @param findings findings in output order
     * @param files number of distinct files represented by the findings
     * @return a compact JSON report
     */
    public static String render(List<Finding> findings, int files) {
        return render(findings, files, false, 0);
    }

    /**
     * Renders findings as one compact JSON object with a suppressed count and no trailing line feed.
     *
     * @param findings findings in output order
     * @param files number of distinct files represented by the findings
     * @param suppressed findings removed by a baseline
     * @return a compact JSON report
     * @throws IllegalArgumentException if {@code suppressed} is negative
     */
    public static String render(List<Finding> findings, int files, int suppressed) {
        if (suppressed < 0) {
            throw new IllegalArgumentException("suppressed must be non-negative");
        }
        return render(findings, files, true, suppressed);
    }

    private static String render(List<Finding> findings, int files, boolean includeSuppressed, int suppressed) {
        Objects.requireNonNull(findings, "findings");

        StringBuilder json = new StringBuilder();
        json.append("{\"findings\":[");
        for (int index = 0; index < findings.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Finding finding = Objects.requireNonNull(findings.get(index), "finding");
            json.append("{\"path\":");
            appendString(json, finding.displayPath());
            json.append(",\"line\":").append(finding.line());
            json.append(",\"rule\":");
            appendString(json, finding.rule());
            json.append(",\"message\":");
            appendString(json, finding.message());
            json.append('}');
        }
        json.append("],\"problems\":").append(findings.size());
        json.append(",\"files\":").append(files);
        if (includeSuppressed) {
            json.append(",\"suppressed\":").append(suppressed);
        }
        json.append('}');
        return json.toString();
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append('\\').append('"');
                case '\\' -> json.append('\\').append('\\');
                case '\n' -> json.append('\\').append('n');
                case '\r' -> json.append('\\').append('r');
                case '\t' -> json.append('\\').append('t');
                case '\b' -> json.append('\\').append('b');
                case '\f' -> json.append('\\').append('f');
                default -> {
                    if (character < 0x20) {
                        json.append('\\').append('u').append('0').append('0');
                        json.append(HEX[(character >>> 4) & 0x0f]);
                        json.append(HEX[character & 0x0f]);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
