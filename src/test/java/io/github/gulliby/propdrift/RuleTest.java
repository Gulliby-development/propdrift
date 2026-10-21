package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleTest {
    private static final String PR004_LINE = "2: PR004 placeholders differ for files: "
            + "base {0},{1}, locale {0}";

    @TempDir
    Path tempDir;

    @Test
    void isRuleAcceptsOnlyTheFiveUpperCaseCodes() {
        for (String code : List.of("PR001", "PR002", "PR003", "PR004", "PR005")) {
            assertTrue(RuleFilter.isRule(code), code);
        }
        for (String code : List.of("pr004", "PR006", "PR000", "PR9", "PR0041", "")) {
            assertFalse(RuleFilter.isRule(code), code);
        }
    }

    @Test
    void keepFiltersByRuleAndKeepsOrder() {
        Finding second = new Finding("a.properties", 1, "PR002", "missing key x");
        Finding third = new Finding("a.properties", 2, "PR003", "extra key y");
        List<Finding> findings = List.of(second, third);

        assertSame(findings, RuleFilter.keep(findings, Set.of()));
        assertEquals(List.of(third), RuleFilter.keep(findings, Set.of("PR003")));
        assertEquals(List.of(second, third), RuleFilter.keep(findings, Set.of("PR003", "PR002")));
        assertEquals(List.of(), RuleFilter.keep(findings, Set.of("PR005")));
    }

    @Test
    void selectsRulesAndKeepsDuplicateDetection() throws IOException {
        Path main = Files.createDirectory(tempDir.resolve("main"));
        writeFixture(main);

        RunResult placeholders = runCli("--rule", "PR004", main.toString());
        assertEquals(1, placeholders.status());
        assertEquals(display(main) + "/messages_de.properties:" + PR004_LINE + "\n"
                + "1 problem in 1 file\n", placeholders.stdout());
        assertEquals("", placeholders.stderr());

        RunResult two = runCli("--rule=PR002", "--rule", "PR003", main.toString());
        assertEquals(1, two.status());
        assertEquals(display(main) + "/messages_de.properties:1: PR002 missing key bye\n"
                + display(main) + "/messages_de.properties:3: PR003 extra key extra\n"
                + "2 problems in 1 file\n", two.stdout());
        assertEquals("", two.stderr());

        Path dup = Files.createDirectory(tempDir.resolve("dup"));
        write(dup, "messages.properties", "a=x {0}");
        write(dup, "messages_de.properties", "a=y {0}", "a=z");

        RunResult hidden = runCli("--rule", "PR004", dup.toString());
        assertEquals(0, hidden.status());
        assertEquals("ok\n", hidden.stdout());

        RunResult duplicate = runCli("--rule", "PR005", dup.toString());
        assertEquals(1, duplicate.status());
        assertEquals(display(dup) + "/messages_de.properties:2: PR005 duplicate key a "
                + "(first defined on line 1)\n1 problem in 1 file\n", duplicate.stdout());
        assertEquals("", duplicate.stderr());
    }

    @Test
    void combinesWithLocaleAndJsonFormat() throws IOException {
        Path main = Files.createDirectory(tempDir.resolve("main"));
        writeFixture(main);

        RunResult clean = runCli("--rule", "PR001", "--locale", "de", main.toString());
        assertEquals(0, clean.status());
        assertEquals("ok\n", clean.stdout());
        assertEquals("", clean.stderr());

        RunResult json = runCli("--format", "json", "--rule", "PR004", main.toString());
        assertEquals(1, json.status());
        assertEquals("{\"findings\":[{\"path\":\"" + display(main) + "/messages_de.properties\","
                + "\"line\":2,\"rule\":\"PR004\",\"message\":\"placeholders differ for files: "
                + "base {0},{1}, locale {0}\"}],\"problems\":1,\"files\":1}\n", json.stdout());
        assertEquals("", json.stderr());
    }

    @Test
    void baselineHoldsOnlySelectedFindings() throws IOException {
        Path main = Files.createDirectory(tempDir.resolve("main"));
        writeFixture(main);
        Path baselineFile = tempDir.resolve("rule.baseline");

        RunResult written = runCli("--rule", "PR004", "--write-baseline", baselineFile.toString(),
                main.toString());
        assertEquals(0, written.status());
        assertEquals("wrote 1 finding to " + baselineFile + "\n", written.stdout());
        assertEquals("", written.stderr());
        assertEquals(Baseline.HEADER + "\n"
                + "PR004\tmessages_de.properties\tplaceholders differ for files: "
                + "base {0},{1}, locale {0}\n",
                Files.readString(baselineFile, StandardCharsets.UTF_8));

        RunResult suppressed = runCli("--rule", "PR004", "--baseline", baselineFile.toString(),
                main.toString());
        assertEquals(0, suppressed.status());
        assertEquals("ok (1 suppressed by baseline)\n", suppressed.stdout());
        assertEquals("", suppressed.stderr());
    }

    @Test
    void validatesRuleValuesAndArgumentErrorOrder() {
        String dir = tempDir.toString();
        String requires = "propdrift: --rule requires a value\n";
        assertRun(2, "", requires, "--rule");
        assertRun(2, "", requires, "--rule", "--locale", "de", dir);
        assertRun(2, "", requires, "--rule=", dir);
        assertRun(2, "", "propdrift: invalid --rule \"pr004\"\n", "--rule", "pr004", dir);
        assertRun(2, "", "propdrift: invalid --rule \"PR9\"\n", "--rule", "PR9", dir);
        assertRun(2, "", "propdrift: invalid --locale \"xx1\"\n",
                "--rule", "PR9", "--locale", "xx1", dir);
        assertRun(2, "", "propdrift: invalid --format \"xml\"\n",
                "--format", "xml", "--rule", "PR9", dir);
        assertRun(2, "", "propdrift: invalid --rule \"PR9\"\n", "--rule", "PR9");
    }

    private void assertRun(int status, String stdout, String stderr, String... args) {
        RunResult result = runCli(args);
        assertEquals(status, result.status());
        assertEquals(stdout, result.stdout());
        assertEquals(stderr, result.stderr());
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

    private void writeFixture(Path directory) throws IOException {
        write(directory, "messages.properties",
                "hello=Hello, {0}!",
                "bye=Goodbye",
                "files=You have {0} files in {1}");
        write(directory, "messages_de.properties",
                "hello=Hallo, {0}!",
                "files=Sie haben {0} Dateien",
                "extra = nur hier");
    }

    private void write(Path directory, String fileName, String... lines) throws IOException {
        Files.writeString(directory.resolve(fileName), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private String display(Path path) {
        String display = path.toString().replace('\\', '/');
        while (display.endsWith("/")) {
            display = display.substring(0, display.length() - 1);
        }
        return display;
    }

    private record RunResult(int status, String stdout, String stderr) {
    }
}
