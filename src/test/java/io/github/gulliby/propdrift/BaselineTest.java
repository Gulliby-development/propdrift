package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineTest {
    private static final String BACKSLASH = String.valueOf((char) 92);

    @TempDir
    Path tempDir;

    @Test
    void rendersAndParsesTheWorkedExampleAndSuppressesItsFindings() throws Exception {
        List<Finding> findings = exampleFindings();

        String rendered = Baseline.render(findings);

        assertEquals(exampleBaseline(), rendered);
        assertEquals(Baseline.HEADER + "\n", Baseline.render(List.of()));
        Baseline parsed = Baseline.parse(rendered);
        assertEquals(2, parsed.version());
        assertEquals(5, parsed.size());
        assertEquals(new Baseline.Applied(List.of(), 5), parsed.apply(findings));
        assertEquals(2, Baseline.parse(Baseline.HEADER).version());
        assertEquals(0, Baseline.parse(Baseline.HEADER).size());
        assertEquals(1, Baseline.parse("").version());
        assertEquals(0, Baseline.parse("").size());
    }

    @Test
    void parsesPlainTextReportsAndIgnoresNonFindingLines() throws Exception {
        String oldReport = "\uFEFF5 problems in 2 files\r\n"
                + "\r\n"
                + "ok\r\n"
                + "dir/:3: PR003 ignored empty file name\r\n"
                + "C:/res/dir/f_de.properties:3: PR003 extra key a: b\r\n";
        Baseline parsed = Baseline.parse(oldReport.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.version());
        assertEquals(1, parsed.size());
        Finding finding = new Finding("elsewhere/f_de.properties", 99, "PR003", "extra key a: b");
        assertEquals(new Baseline.Applied(List.of(), 1), parsed.apply(List.of(finding)));
        assertEquals(0, Baseline.parse("ok\r\n\r\n").size());
    }

    @Test
    void matchesByRuleFileNameAndMessageAndConsumesEntriesOnce() throws Exception {
        Baseline baseline = Baseline.parse(Baseline.HEADER + "\n"
                + "PR002\tmessages_de.properties\tmissing key bye\n"
                + "PR002\tmessages_de.properties\tmissing key bye");
        Finding first = new Finding("project/a/messages_de.properties", 1, "PR002", "missing key bye");
        Finding second = new Finding("other/messages_de.properties", 30, "PR002", "missing key bye");
        Finding third = new Finding("other/messages_de.properties", 31, "PR002", "missing key bye");
        Finding differentFile = new Finding("other/messages_fr.properties", 1, "PR002", "missing key bye");
        Finding differentRule = new Finding("other/messages_de.properties", 1, "PR003", "missing key bye");
        Finding differentMessage = new Finding("other/messages_de.properties", 1, "PR002", "missing key hello");

        Baseline.Applied applied = baseline.apply(
                List.of(first, second, third, differentFile, differentRule, differentMessage));

        assertEquals(2, applied.suppressed());
        assertEquals(List.of(third, differentFile, differentRule, differentMessage), applied.remaining());
    }

    @Test
    void versionTwoEscapesRoundTripAndAcceptsCommentsAndCrLf() throws Exception {
        String message = "tab\t slash" + BACKSLASH + " line\nreturn\r";
        Finding finding = new Finding("dir/file.properties", 7, "PR004", message);
        String rendered = Baseline.render(List.of(finding));
        assertTrue(rendered.contains("tab\\t slash" + BACKSLASH + BACKSLASH + " line\\nreturn\\r"));

        String crlf = (Baseline.HEADER + "\n\n# ignored comment\n"
                + rendered.substring(Baseline.HEADER.length() + 1)).replace("\n", "\r\n");
        Baseline parsed = Baseline.parse(crlf.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.size());
        assertEquals(new Baseline.Applied(List.of(), 1), parsed.apply(List.of(finding)));
        assertEquals("file.properties", finding.fileName());
        assertEquals("plain.properties", new Finding("plain.properties", 1, "PR001", "m").fileName());
    }

    @Test
    void reportsUnsupportedVersionsAndMalformedEntriesWithPhysicalLines() {
        assertBaselineError(Baseline.HEADER + "x", 0, "unsupported baseline version");
        assertBaselineError("# propdrift baseline v3", 0, "unsupported baseline version");
        assertBaselineError(Baseline.HEADER + " ", 0, "unsupported baseline version");
        assertBaselineError(Baseline.HEADER + "\none field", 2, "malformed baseline entry");
        assertBaselineError(Baseline.HEADER + "\nPR002\ta\tb\tc", 2, "malformed baseline entry");
        assertBaselineError(Baseline.HEADER + "\nPX002\ta\tb", 2, "malformed baseline entry");
        assertBaselineError(Baseline.HEADER + "\nPR002\tfile\tbad" + BACKSLASH + "q",
                2, "malformed baseline entry");
        assertBaselineError(Baseline.HEADER + "\nPR002\tfile\tbad" + BACKSLASH,
                2, "malformed baseline entry");
        assertBaselineError(Baseline.HEADER + "\n# comment\r\nPR002\tfile\tbad" + BACKSLASH + "x",
                3, "malformed baseline entry");

        assertThrows(CharacterCodingException.class,
                () -> Baseline.parse(new byte[] {(byte) 0xc3, 0x28}));
    }

    @Test
    void jsonRendererIncludesSuppressedCountAndRejectsNegativeValues() {
        Finding finding = new Finding("f.properties", 1, "PR002", "missing key x");
        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0,\"suppressed\":0}",
                JsonReport.render(List.of(), 0, 0));
        assertEquals("{\"findings\":[{\"path\":\"f.properties\",\"line\":1,"
                + "\"rule\":\"PR002\",\"message\":\"missing key x\"}],"
                + "\"problems\":1,\"files\":1,\"suppressed\":3}",
                JsonReport.render(List.of(finding), 1, 3));
        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0}",
                JsonReport.render(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> JsonReport.render(List.of(), 0, -1));
    }

    @Test
    void cliWritesAndAppliesTheWorkedExampleBaseline() throws IOException {
        writeWorkedExample();
        Path baselineFile = tempDir.resolve("ex.baseline");
        String expectedBaseline = exampleBaseline();

        RunResult written = runCli("--write-baseline", baselineFile.toString(), tempDir.toString());
        assertEquals(0, written.status());
        assertEquals("wrote 5 findings to " + baselineFile + "\n", written.stdout());
        assertEquals("", written.stderr());
        assertEquals(expectedBaseline, Files.readString(baselineFile, StandardCharsets.UTF_8));

        RunResult suppressed = runCli(tempDir.toString(), "--baseline=" + baselineFile);
        assertEquals(0, suppressed.status());
        assertEquals("ok (5 suppressed by baseline)\n", suppressed.stdout());
        assertEquals("", suppressed.stderr());

        RunResult json = runCli("--format", "json", "--baseline", baselineFile.toString(), tempDir.toString());
        assertEquals(0, json.status());
        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0,\"suppressed\":5}\n", json.stdout());

        Files.writeString(tempDir.resolve("messages_fr.properties"),
                "hello=Bonjour, {0}!\nfiles=Vous avez {1} fichiers dans {0}\n",
                StandardCharsets.UTF_8);
        RunResult newFinding = runCli("--baseline", baselineFile.toString(), tempDir.toString());
        String displayDirectory = displayDirectory();
        assertEquals(1, newFinding.status());
        assertEquals(displayDirectory + "/messages_fr.properties:1: PR002 missing key bye\n"
                + "1 problem in 1 file (5 suppressed by baseline)\n", newFinding.stdout());

        RunResult newFindingJson = runCli("--format=json", tempDir.toString(), "--baseline", baselineFile.toString());
        assertEquals(1, newFindingJson.status());
        assertEquals("{\"findings\":[{\"path\":\"" + displayDirectory
                + "/messages_fr.properties\",\"line\":1,\"rule\":\"PR002\","
                + "\"message\":\"missing key bye\"}],\"problems\":1,\"files\":1,\"suppressed\":5}\n",
                newFindingJson.stdout());
    }

    @Test
    void cliAcceptsTextReportsAndMigratesThemToVersionTwo() throws IOException {
        writeWorkedExample();
        String directory = tempDir.toString();
        RunResult plain = runCli(directory);
        assertEquals(1, plain.status());
        Path oldFile = tempDir.resolve("old.txt");
        Files.writeString(oldFile, plain.stdout(), StandardCharsets.UTF_8);

        RunResult fromPlainText = runCli("--baseline", oldFile.toString(), directory);
        assertEquals(0, fromPlainText.status());
        assertEquals("ok (5 suppressed by baseline)\n", fromPlainText.stdout());

        Files.writeString(oldFile, plain.stdout() + "stale.properties:1: PR999 stale entry\n",
                StandardCharsets.UTF_8);
        RunResult migrated = runCli("--baseline", oldFile.toString(), "--write-baseline", oldFile.toString(), directory);
        assertEquals(0, migrated.status());
        assertEquals("wrote 5 findings to " + oldFile + "\n", migrated.stdout());
        assertEquals(exampleBaseline(), Files.readString(oldFile, StandardCharsets.UTF_8));
    }

    @Test
    void cliWritesEmptyAndSingleFindingBaselinesWithCorrectCounts() throws IOException {
        Path clean = Files.createDirectory(tempDir.resolve("clean"));
        write(clean, "messages.properties", "hello=Hello");
        write(clean, "messages_de.properties", "hello=Hallo");
        Path baselineFile = tempDir.resolve("clean.baseline");

        RunResult empty = runCli("--write-baseline=" + baselineFile, clean.toString());
        assertEquals(0, empty.status());
        assertEquals("wrote 0 findings to " + baselineFile + "\n", empty.stdout());
        assertEquals(Baseline.HEADER + "\n", Files.readString(baselineFile, StandardCharsets.UTF_8));

        Path oneFinding = Files.createDirectory(tempDir.resolve("one"));
        write(oneFinding, "base.properties", "key=one", "key=two");
        RunResult one = runCli(oneFinding.toString(), "--write-baseline", baselineFile.toString());
        assertEquals(0, one.status());
        assertEquals("wrote 1 finding to " + baselineFile + "\n", one.stdout());
        assertEquals(Baseline.HEADER + "\n"
                + "PR005\tbase.properties\tduplicate key key (first defined on line 1)\n",
                Files.readString(baselineFile, StandardCharsets.UTF_8));

        RunResult suppressed = runCli("--baseline", baselineFile.toString(), oneFinding.toString());
        assertEquals(0, suppressed.status());
        assertEquals("ok (1 suppressed by baseline)\n", suppressed.stdout());
        RunResult json = runCli("--format=json", "--baseline=" + baselineFile, oneFinding.toString());
        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0,\"suppressed\":1}\n", json.stdout());
    }

    @Test
    void cliReportsArgumentAndBaselineErrorsInTheSpecifiedOrder() throws IOException {
        assertError("propdrift: --baseline requires a value\n", "--baseline");
        assertError("propdrift: --baseline requires a value\n", "--baseline", "--format");
        assertError("propdrift: --write-baseline requires a value\n", "--write-baseline");
        assertError("propdrift: --write-baseline requires a value\n", "--write-baseline", "--format");
        assertError("propdrift: --baseline requires a value\n", "--baseline=");
        assertError("propdrift: --write-baseline requires a value\n", "--write-baseline=");
        assertError("propdrift: unknown option \"--unknown\"\n", "--unknown", "--baseline");
        assertError("propdrift: invalid --format \"xml\"\n", "--format=xml");
        assertError("propdrift: directory required\n", "--baseline", tempDir.resolve("missing").toString());

        Path directory = Files.createDirectory(tempDir.resolve("bundles"));
        Path missing = tempDir.resolve("missing-baseline");
        assertError("propdrift: " + missing + ": cannot read baseline\n",
                "--baseline", missing.toString(), directory.toString());

        Path baselineDirectory = Files.createDirectory(tempDir.resolve("baseline-directory"));
        assertError("propdrift: " + baselineDirectory + ": cannot read baseline\n",
                "--baseline", baselineDirectory.toString(), directory.toString());

        Path invalidUtf8 = tempDir.resolve("invalid.txt");
        Files.write(invalidUtf8, new byte[] {(byte) 0xc3, 0x28});
        assertError("propdrift: " + invalidUtf8 + ": invalid UTF-8\n",
                "--baseline", invalidUtf8.toString(), directory.toString());

        Path unsupported = tempDir.resolve("unsupported.txt");
        Files.writeString(unsupported, "# propdrift baseline v3\n", StandardCharsets.UTF_8);
        assertError("propdrift: " + unsupported + ": unsupported baseline version\n",
                "--baseline", unsupported.toString(), directory.toString());

        Path malformed = tempDir.resolve("malformed.txt");
        Files.writeString(malformed, Baseline.HEADER + "\nPR002\tfile\tbad" + BACKSLASH + "x\n",
                StandardCharsets.UTF_8);
        assertError("propdrift: " + malformed + ":2: malformed baseline entry\n",
                "--baseline", malformed.toString(), directory.toString());

        Path writeTarget = tempDir.resolve("missing-parent").resolve("new.baseline");
        assertError("propdrift: " + writeTarget + ": cannot write baseline\n",
                "--write-baseline", writeTarget.toString(), directory.toString());
        assertTrue(!Files.exists(writeTarget));

        Path readBeforeWrite = tempDir.resolve("not-written.baseline");
        assertError("propdrift: " + malformed + ":2: malformed baseline entry\n",
                "--baseline", malformed.toString(), "--write-baseline", readBeforeWrite.toString(),
                directory.toString());
        assertTrue(!Files.exists(readBeforeWrite));
    }

    @Test
    void readmeDocumentsBaselineUsageAndBehavior() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        for (String required : List.of(
                "### Baselines",
                "--write-baseline ex.baseline ex",
                "wrote 5 findings to ex.baseline",
                Baseline.HEADER,
                "PR002\tmessages_de.properties\tmissing key bye",
                "1 problem in 1 file (5 suppressed by baseline)",
                "ok (5 suppressed by baseline)",
                "\"suppressed\":5",
                "--baseline old.txt --write-baseline old.txt",
                "propdrift ex > old.txt")) {
            assertTrue(readme.contains(required), "README.md should contain: " + required);
        }
    }

    private String exampleBaseline() {
        return Baseline.HEADER + "\n"
                + "PR002\tmessages_de.properties\tmissing key bye\n"
                + "PR003\tmessages_de.properties\textra key extra\n"
                + "PR004\tmessages_de.properties\tplaceholders differ for files: base {0},{1}, locale {0}\n"
                + "PR005\tmessages_de.properties\tduplicate key hello (first defined on line 1)\n"
                + "PR001\torphan_es.properties\tmissing base file for orphan\n";
    }

    private List<Finding> exampleFindings() {
        return List.of(
                new Finding("ex/orphan_es.properties", 1, "PR001", "missing base file for orphan"),
                new Finding("ex/messages_de.properties", 4, "PR005",
                        "duplicate key hello (first defined on line 1)"),
                new Finding("ex/messages_de.properties", 3, "PR003", "extra key extra"),
                new Finding("ex/messages_de.properties", 1, "PR002", "missing key bye"),
                new Finding("ex/messages_de.properties", 2, "PR004",
                        "placeholders differ for files: base {0},{1}, locale {0}"));
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

    private void assertBaselineError(String text, int line, String message) {
        Baseline.BaselineException exception = assertThrows(
                Baseline.BaselineException.class, () -> Baseline.parse(text));
        assertEquals(line, exception.line());
        assertEquals(message, exception.getMessage());
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

    private record RunResult(int status, String stdout, String stderr) {
    }
}
