package io.github.gulliby.propdrift;

import java.util.Objects;

/** A diagnostic produced while checking a properties bundle. */
public record Finding(String displayPath, int line, String rule, String message) {
    public Finding {
        Objects.requireNonNull(displayPath, "displayPath");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(message, "message");
    }

    /** Formats this finding using the CLI diagnostic layout. */
    public String format() {
        return displayPath + ":" + line + ": " + rule + " " + message;
    }
}
