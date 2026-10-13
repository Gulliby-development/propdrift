package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropdriftTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesArgumentsInTheSpecifiedOrder() {
        assertRun(0, "Usage: propdrift [--format text|json] DIR\n", "", "--help");
        assertRun(2, "", "propdrift: directory required\n");
        assertRun(2, "", "propdrift: unknown option \"--x\"\n", "--x", "a");
        assertRun(2, "", "propdrift: --format requires a value\n", "--format");
        assertRun(2, "", "propdrift: --format requires a value\n", "--format", "--x", "a");
        assertRun(2, "", "propdrift: invalid --format \"xml\"\n", "--format=xml", "a");
        assertRun(2, "", "propdrift: invalid --format \"xml\"\n", "--format", "xml", "a");
        assertRun(2, "", "propdrift: unknown option \"--x\"\n", "--x", "--format=xml", "a", "b");
        assertRun(2, "", "propdrift: invalid --format \"xml\"\n", "--format=xml", "a", "b");
        assertRun(2, "", "propdrift: exactly one directory required\n", "a", "b");
        assertRun(2, "", "propdrift: " + tempDir.resolve("missing") + ": not a directory\n",
                tempDir.resolve("missing").toString());
    }

    @Test
    void displayPathNormalizesSeparatorsAndTrailingSlashes() {
        assertEquals("f.properties", Cli.displayPath(".", "f.properties"));
        assertEquals("docs/f.properties", Cli.displayPath("docs", "f.properties"));
        assertEquals("docs/f.properties", Cli.displayPath("docs/", "f.properties"));
        assertEquals("docs/i18n/f.properties", Cli.displayPath("docs\\i18n\\", "f.properties"));
        assertEquals("C:/res/f.properties", Cli.displayPath("C:\\res", "f.properties"));
    }

    @Test
    void checksDuplicatesAndLocaleRulesWithPlaceholderNormalization() throws IOException {
        write(tempDir, "bundle.properties",
                "missing=z",
                "zero={00}",
                "escaped=" + "\\" + "u007b0}",
                "long={1234567890}",
                "shared=base");
        write(tempDir, "bundle_de.properties",
                "zero={1}",
                "escaped=plain",
                "long=other",
                "extra=local",
                "shared=base",
                "shared=ignored");
        write(tempDir, "orphan_es.properties", "a=b");

        String directory = displayDirectory();
        RunResult result = runCli(directory);

        assertEquals(1, result.status());
        assertEquals(
                directory + "/bundle_de.properties:1: PR002 missing key missing\n"
                        + directory + "/bundle_de.properties:1: PR004 placeholders differ for zero: "
                        + "base {0}, locale {1}\n"
                        + directory + "/bundle_de.properties:2: PR004 placeholders differ for escaped: "
                        + "base {0}, locale none\n"
                        + directory + "/bundle_de.properties:4: PR003 extra key extra\n"
                        + directory + "/bundle_de.properties:6: PR005 duplicate key shared "
                        + "(first defined on line 5)\n"
                        + directory + "/orphan_es.properties:1: PR001 missing base file for orphan\n"
                        + "6 problems in 2 files\n",
                result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void sortingIsStableAndSummaryUsesDistinctFiles() throws IOException {
        write(tempDir, "a.properties", "zeta=1", "alpha=1");
        write(tempDir, "a_de.properties", "extra=1");
        write(tempDir, "b_de.properties", "key=value");

        String directory = displayDirectory();
        RunResult result = runCli(directory);

        assertEquals(1, result.status());
        assertEquals(
                directory + "/a_de.properties:1: PR002 missing key zeta\n"
                        + directory + "/a_de.properties:1: PR002 missing key alpha\n"
                        + directory + "/a_de.properties:1: PR003 extra key extra\n"
                        + directory + "/b_de.properties:1: PR001 missing base file for b\n"
                        + "4 problems in 2 files\n",
                result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void reportsSingularSummaryAndBaseFileDuplicates() throws IOException {
        write(tempDir, "plain.properties", "key=first", "key=second");

        String directory = displayDirectory();
        RunResult result = runCli(directory);

        assertEquals(1, result.status());
        assertEquals(directory + "/plain.properties:2: PR005 duplicate key key "
                + "(first defined on line 1)\n1 problem in 1 file\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void readsOnlyDirectRegularPropertiesFiles() throws IOException {
        write(tempDir, "main.properties", "hello=Hello");
        write(tempDir, "main_de.properties", "hello=Hallo");
        write(tempDir, "ignored.PROPERTIES", "extra=value");
        Files.createDirectory(tempDir.resolve("nested"));
        write(tempDir.resolve("nested"), "main_fr.properties", "missing=value");
        Files.createDirectory(tempDir.resolve("not-a-file.properties"));

        RunResult result = runCli(tempDir.toString());

        assertEquals(0, result.status());
        assertEquals("ok\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void reportsInputErrorsWithoutPrintingPartialFindings() throws IOException {
        write(tempDir, "a_de.properties", "extra=value");
        Files.write(tempDir.resolve("z.properties"), new byte[] {(byte) 0xc3, 0x28});

        String directory = displayDirectory();
        RunResult invalidUtf8 = runCli(tempDir.toString());

        assertEquals(2, invalidUtf8.status());
        assertEquals("", invalidUtf8.stdout());
        assertTrue(invalidUtf8.stderr().startsWith(
                "propdrift: " + directory + "/z.properties: "));

        Files.delete(tempDir.resolve("z.properties"));
        write(tempDir, "z.properties", "ok=value", "bad=" + "\\" + "u12G4");

        RunResult malformedEscape = runCli(tempDir.toString());

        assertEquals(2, malformedEscape.status());
        assertEquals("", malformedEscape.stdout());
        assertEquals("propdrift: " + directory + "/z.properties:2: malformed \\u escape\n",
                malformedEscape.stderr());
    }

    @Test
    void workedExampleMatchesTheDocumentedOutputAndThenPassesCleanly() throws IOException {
        write(tempDir, "messages.properties",
                "# greetings",
                "hello=Hello, {0}!",
                "bye=Goodbye",
                "files=You have {0} files in {1}");
        write(tempDir, "messages_de.properties",
                "hello=Hallo, {0}!",
                "files=Sie haben {0} Dateien",
                "extra = nur hier",
                "hello=doppelt");
        write(tempDir, "messages_fr.properties",
                "hello=Bonjour, {0}!",
                "bye=Au revoir",
                "files=Vous avez {1} fichiers dans {0}");
        write(tempDir, "orphan_es.properties", "a=b");

        String directory = displayDirectory();
        RunResult findings = runCli(tempDir.toString());

        assertEquals(1, findings.status());
        assertEquals(
                directory + "/messages_de.properties:1: PR002 missing key bye\n"
                        + directory + "/messages_de.properties:2: PR004 placeholders differ for files: "
                        + "base {0},{1}, locale {0}\n"
                        + directory + "/messages_de.properties:3: PR003 extra key extra\n"
                        + directory + "/messages_de.properties:4: PR005 duplicate key hello "
                        + "(first defined on line 1)\n"
                        + directory + "/orphan_es.properties:1: PR001 missing base file for orphan\n"
                        + "5 problems in 2 files\n",
                findings.stdout());
        assertEquals("", findings.stderr());

        Files.delete(tempDir.resolve("messages_de.properties"));
        Files.delete(tempDir.resolve("orphan_es.properties"));
        RunResult clean = runCli(tempDir.toString());

        assertEquals(0, clean.status());
        assertEquals("ok\n", clean.stdout());
        assertEquals("", clean.stderr());
    }

    @Test
    void readmeContainsRequiredSectionsAndWorkedExampleDiagnostics() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        for (String section : List.of(
                "## Build",
                "## Usage",
                "## Rules",
                "## Supported properties syntax",
                "## Example",
                "## Exit codes",
                "## Development",
                "mvn -B -q verify")) {
            assertTrue(readme.contains(section), "README.md should contain " + section);
        }

        for (String line : List.of(
                "messages_de.properties:1: PR002 missing key bye",
                "messages_de.properties:2: PR004 placeholders differ for files: base {0},{1}, locale {0}",
                "messages_de.properties:3: PR003 extra key extra",
                "messages_de.properties:4: PR005 duplicate key hello (first defined on line 1)",
                "orphan_es.properties:1: PR001 missing base file for orphan",
                "5 problems in 2 files")) {
            assertTrue(readme.contains(line), "README.md should document " + line);
        }
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

    private void write(Path directory, String fileName, String... lines) throws IOException {
        write(directory, Path.of(fileName), lines);
    }

    private void write(Path directory, Path file, String... lines) throws IOException {
        Files.writeString(directory.resolve(file), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
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
