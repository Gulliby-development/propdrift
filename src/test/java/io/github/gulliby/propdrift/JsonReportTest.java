package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReportTest {
    private static final String BACKSLASH = String.valueOf((char) 92);
    private static final String TICK = String.valueOf((char) 96);
    private static final String README_JSON = "{\"findings\":["
            + "{\"path\":\"ex/messages_de.properties\",\"line\":1,\"rule\":\"PR002\",\"message\":\"missing key bye\"},"
            + "{\"path\":\"ex/messages_de.properties\",\"line\":2,\"rule\":\"PR004\",\"message\":\"placeholders differ for files: base {0},{1}, locale {0}\"},"
            + "{\"path\":\"ex/messages_de.properties\",\"line\":3,\"rule\":\"PR003\",\"message\":\"extra key extra\"},"
            + "{\"path\":\"ex/messages_de.properties\",\"line\":4,\"rule\":\"PR005\",\"message\":\"duplicate key hello (first defined on line 1)\"},"
            + "{\"path\":\"ex/orphan_es.properties\",\"line\":1,\"rule\":\"PR001\",\"message\":\"missing base file for orphan\"}"
            + "],\"problems\":5,\"files\":2}";

    @TempDir
    Path tempDir;

    @Test
    void rendererEscapesRequiredCharactersAndPreservesOtherCharacters() {
        String message = "quote=\" backslash=" + BACKSLASH + " tab=\t newline=\n carriage=\r"
                + " backspace=\b formfeed=\f control=" + (char) 1 + (char) 0x1f
                + " unicode=é separator=" + (char) 0x2028 + (char) 0x2029 + " end";
        Finding finding = new Finding("a\"b" + BACKSLASH + "c", 7, "R", message);

        String report = JsonReport.render(List.of(finding), 1);

        assertEquals("{\"findings\":[{\"path\":\"a\\\"b" + BACKSLASH + BACKSLASH
                + "c\",\"line\":7,\"rule\":\"R\",\"message\":\"quote=\\\" backslash="
                + BACKSLASH + BACKSLASH + " tab=\\t newline=\\n carriage=\\r backspace=\\b"
                + " formfeed=\\f control=" + BACKSLASH + "u0001" + BACKSLASH + "u001f"
                + " unicode=é separator=" + (char) 0x2028 + (char) 0x2029 + " end\"}],"
                + "\"problems\":1,\"files\":1}", report);
        assertFalse(report.endsWith("\n"));
    }

    @Test
    void rendererUsesRequiredFieldOrderAndPreservesFindingOrder() {
        String report = JsonReport.render(List.of(
                new Finding("first.properties", 3, "PR003", "first finding"),
                new Finding("second.properties", 1, "PR001", "second finding")), 9);

        assertEquals("{\"findings\":["
                + "{\"path\":\"first.properties\",\"line\":3,\"rule\":\"PR003\",\"message\":\"first finding\"},"
                + "{\"path\":\"second.properties\",\"line\":1,\"rule\":\"PR001\",\"message\":\"second finding\"}"
                + "],\"problems\":2,\"files\":9}", report);
    }

    @Test
    void jsonOutputMatchesTheDocumentedWorkedExample() throws IOException {
        writeWorkedExample();
        String directory = displayDirectory();

        RunResult result = runCli("--format", "json", tempDir.toString());

        assertEquals(1, result.status());
        assertEquals(readmeJsonFor(directory) + "\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void cleanJsonRunUsesCompactEmptyReportAndSupportsBothFormatSpellings() throws IOException {
        write(tempDir, "messages.properties", "hello=Hello");
        write(tempDir, "messages_de.properties", "hello=Hallo");
        String directory = tempDir.toString();

        RunResult equalsSpelling = runCli("--format=json", directory);
        RunResult separatedSpelling = runCli("--format", "json", directory);
        RunResult optionAfterDirectory = runCli(directory, "--format", "json");

        for (RunResult result : List.of(equalsSpelling, separatedSpelling, optionAfterDirectory)) {
            assertEquals(0, result.status());
            assertEquals("{\"findings\":[],\"problems\":0,\"files\":0}\n", result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void jsonSummaryMatchesTextSummaryForMultipleAndSingleProblemDirectories() throws IOException {
        writeWorkedExample();
        String directory = tempDir.toString();

        RunResult text = runCli(directory);
        RunResult json = runCli("--format=json", directory);

        assertEquals(1, text.status());
        assertTrue(text.stdout().endsWith("5 problems in 2 files\n"));
        assertEquals(1, json.status());
        assertTrue(json.stdout().endsWith("\"problems\":5,\"files\":2}\n"));

        Path singleProblem = Files.createDirectory(tempDir.resolve("single"));
        write(singleProblem, "base.properties", "key=one", "key=two");
        String singleDirectory = singleProblem.toString();

        RunResult singleText = runCli(singleDirectory);
        RunResult singleJson = runCli(singleDirectory, "--format=json");

        assertEquals(1, singleText.status());
        assertTrue(singleText.stdout().endsWith("1 problem in 1 file\n"));
        assertEquals(1, singleJson.status());
        assertTrue(singleJson.stdout().endsWith("\"problems\":1,\"files\":1}\n"));
    }

    @Test
    void repeatedFormatOptionsUseTheLastValue() throws IOException {
        write(tempDir, "messages.properties", "hello=Hello");
        write(tempDir, "messages_de.properties", "hello=Hallo");
        String directory = tempDir.toString();

        RunResult jsonLast = runCli("--format=text", "--format", "json", directory);
        RunResult textLast = runCli("--format", "json", "--format=text", directory);

        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0}\n", jsonLast.stdout());
        assertEquals("ok\n", textLast.stdout());
        assertEquals(0, jsonLast.status());
        assertEquals(0, textLast.status());
    }

    @Test
    void formatErrorsKeepSpecifiedMessagesAndPrecedence() {
        assertError("propdrift: --format requires a value\n", "--format");
        assertError("propdrift: --format requires a value\n", "--format", "--x", "a");
        assertError("propdrift: invalid --format \"xml\"\n", "--format=xml", "a");
        assertError("propdrift: invalid --format \"xml\"\n", "--format", "xml", "a");
        assertError("propdrift: invalid --format \"\"\n", "--format=", "a");
        assertError("propdrift: unknown option \"--x\"\n", "--format=xml", "--x", "a", "b");
        assertError("propdrift: invalid --format \"xml\"\n", "--format=xml", "a", "b");
    }

    @Test
    void formatTextPreservesDefaultTextOutputAndJsonErrorsRemainTextOnStderr() throws IOException {
        write(tempDir, "base.properties", "key=one", "key=two");
        String directory = tempDir.toString();

        RunResult defaultText = runCli(directory);
        RunResult explicitText = runCli("--format", "text", directory);
        assertEquals(defaultText.status(), explicitText.status());
        assertEquals(defaultText.stdout(), explicitText.stdout());
        assertEquals(defaultText.stderr(), explicitText.stderr());

        RunResult error = runCli("--format=json", tempDir.resolve("missing").toString());
        assertEquals(2, error.status());
        assertEquals("", error.stdout());
        assertTrue(error.stderr().startsWith("propdrift: " + tempDir.resolve("missing") + ": not a directory\n"));
    }

    @Test
    void readmeDocumentsTheOptionUsageJsonExampleAndExplanations() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        for (String content : List.of(
                "| Option | Description |",
                "| " + TICK + "--format text\\|json" + TICK
                        + " | Output format: " + TICK + "text" + TICK + " (default) or "
                        + TICK + "json" + TICK + ". Also " + TICK + "--format=json" + TICK + ". |",
                "java -cp target/classes io.github.gulliby.propdrift.Main [--format text|json] path/to/bundles",
                "Usage: propdrift [--format text|json] DIR",
                "java -cp target/classes io.github.gulliby.propdrift.Main --help",
                "### JSON output",
                "java -cp target/classes io.github.gulliby.propdrift.Main --format json ex",
                README_JSON,
                "JSON strings use standard escaping for quotes, backslashes, and control characters; non-ASCII characters are preserved.",
                TICK + "problems" + TICK + " is the number of findings, and " + TICK + "files" + TICK
                        + " is the number of distinct files with findings.",
                "A successful check prints " + TICK + "ok" + TICK + " (or an empty " + TICK + "findings"
                        + TICK + " array with " + TICK + "--format json" + TICK
                        + "); otherwise, findings are followed by a problem and affected-file count.")) {
            assertTrue(readme.contains(content), "README.md should contain: " + content);
        }
    }

    private void writeWorkedExample() throws IOException {
        write(tempDir, "messages.properties",
                "# greetings", "hello=Hello, {0}!", "bye=Goodbye", "files=You have {0} files in {1}");
        write(tempDir, "messages_de.properties",
                "hello=Hallo, {0}!", "files=Sie haben {0} Dateien", "extra = nur hier", "hello=doppelt");
        write(tempDir, "messages_fr.properties",
                "hello=Bonjour, {0}!", "bye=Au revoir", "files=Vous avez {1} fichiers dans {0}");
        write(tempDir, "orphan_es.properties", "a=b");
    }

    private String readmeJsonFor(String directory) {
        return README_JSON.replace("ex/", directory + "/");
    }

    private void assertError(String expectedStderr, String... args) {
        RunResult result = runCli(args);
        assertEquals(2, result.status());
        assertEquals("", result.stdout());
        assertEquals(expectedStderr, result.stderr());
    }

    private RunResult runCli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int status = Cli.run(args, out, err);
            return new RunResult(status, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private void write(Path directory, String fileName, String... lines) throws IOException {
        Files.writeString(directory.resolve(fileName), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private String displayDirectory() {
        String display = tempDir.toString().replace('\\', '/');
        while (display.endsWith("/")) {
            display = display.substring(0, display.length() - 1);
        }
        return display;
    }

    private record RunResult(int status, String stdout, String stderr) {
    }
}
