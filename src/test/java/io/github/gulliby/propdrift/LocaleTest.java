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

class LocaleTest {
    @TempDir
    Path tempDir;

    @Test
    void filtersWorkedExampleToOneLocale() throws IOException {
        writeWorkedExample();

        RunResult result = runCli("--locale", "de", tempDir.toString());

        assertEquals(1, result.status());
        assertEquals(directory() + "/messages_de.properties:1: PR002 missing key bye\n"
                + directory() + "/messages_de.properties:2: PR004 placeholders differ for files: "
                + "base {0},{1}, locale {0}\n"
                + directory() + "/messages_de.properties:3: PR003 extra key extra\n"
                + directory() + "/messages_de.properties:4: PR005 duplicate key hello "
                + "(first defined on line 1)\n"
                + "4 problems in 1 file\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void repeatedLocaleOptionsSelectTheUnion() throws IOException {
        writeWorkedExample();

        RunResult result = runCli("--locale=es", "--locale", "de", tempDir.toString());

        assertEquals(1, result.status());
        assertEquals(directory() + "/messages_de.properties:1: PR002 missing key bye\n"
                + directory() + "/messages_de.properties:2: PR004 placeholders differ for files: "
                + "base {0},{1}, locale {0}\n"
                + directory() + "/messages_de.properties:3: PR003 extra key extra\n"
                + directory() + "/messages_de.properties:4: PR005 duplicate key hello "
                + "(first defined on line 1)\n"
                + directory() + "/orphan_es.properties:1: PR001 missing base file for orphan\n"
                + "5 problems in 2 files\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void cleanSelectedLocaleHasEmptyJsonReport() throws IOException {
        writeWorkedExample();

        RunResult result = runCli("--format=json", "--locale=fr", tempDir.toString());

        assertEquals(0, result.status());
        assertEquals("{\"findings\":[],\"problems\":0,\"files\":0}\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void regionalLocalesAreDistinctAndBaseFindingsAreExcluded() throws IOException {
        write(tempDir, "m.properties", "a=one", "a=duplicate", "b=two");
        write(tempDir, "m_pt_BR.properties", "a=one");
        write(tempDir, "m_pt.properties", "a=one", "b=two");

        RunResult regional = runCli("--locale", "pt_BR", tempDir.toString());
        RunResult languageOnly = runCli("--locale", "pt", tempDir.toString());

        assertEquals(1, regional.status());
        assertEquals(directory() + "/m_pt_BR.properties:1: PR002 missing key b\n"
                + "1 problem in 1 file\n", regional.stdout());
        assertEquals("", regional.stderr());
        assertEquals(0, languageOnly.status());
        assertEquals("ok\n", languageOnly.stdout());
        assertEquals("", languageOnly.stderr());
    }

    @Test
    void baselineWritingAndSuppressionUseOnlySelectedFindings() throws IOException {
        writeWorkedExample();
        Path baselineFile = tempDir.resolve("selected.baseline");

        RunResult written = runCli("--locale=de", "--write-baseline", baselineFile.toString(),
                tempDir.toString());

        assertEquals(0, written.status());
        assertEquals("wrote 4 findings to " + baselineFile + "\n", written.stdout());
        assertEquals("", written.stderr());
        assertEquals(Baseline.HEADER + "\n"
                + "PR002\tmessages_de.properties\tmissing key bye\n"
                + "PR003\tmessages_de.properties\textra key extra\n"
                + "PR004\tmessages_de.properties\tplaceholders differ for files: "
                + "base {0},{1}, locale {0}\n"
                + "PR005\tmessages_de.properties\tduplicate key hello (first defined on line 1)\n",
                Files.readString(baselineFile, StandardCharsets.UTF_8));

        RunResult suppressed = runCli("--locale", "de", "--baseline", baselineFile.toString(),
                tempDir.toString());

        assertEquals(0, suppressed.status());
        assertEquals("ok (4 suppressed by baseline)\n", suppressed.stdout());
        assertEquals("", suppressed.stderr());
    }

    @Test
    void validatesLocaleValuesAndArgumentErrorOrder() {
        assertRun(2, "", "propdrift: --locale requires a value\n", "--locale");
        assertRun(2, "", "propdrift: --locale requires a value\n", "--locale", "--x", "bundles");
        assertRun(2, "", "propdrift: --locale requires a value\n", "--locale=");
        assertRun(2, "", "propdrift: invalid --locale \"DE\"\n", "--locale", "DE", "bundles");
        assertRun(2, "", "propdrift: invalid --locale \"deu\"\n", "--locale=deu", "bundles");
        assertRun(2, "", "propdrift: invalid --locale \"pt_br\"\n", "--locale", "pt_br", "bundles");
        assertRun(2, "", "propdrift: invalid --locale \"DE\"\n",
                "--locale", "DE", "--locale", "deu", "bundles");
        assertRun(2, "", "propdrift: invalid --format \"xml\"\n",
                "--locale", "DE", "--format", "xml", "bundles");
        assertRun(2, "", "propdrift: invalid --locale \"DE\"\n", "--locale", "DE");
        assertRun(2, "", "propdrift: directory required\n", "--locale", "de");
    }

    @Test
    void checksParsedFilesBeforeMissingLocaleAndMissingLocaleBeforeBaseline() throws IOException {
        write(tempDir, "messages.properties", "hello=Hello");

        RunResult missingLocale = runCli("--locale", "pt_BR", "--locale=it",
                "--baseline", tempDir.resolve("missing.baseline").toString(), tempDir.toString());

        assertEquals(2, missingLocale.status());
        assertEquals("", missingLocale.stdout());
        assertEquals("propdrift: no file for locale \"pt_BR\"\n", missingLocale.stderr());

        write(tempDir, "broken.properties", "bad=" + "\\" + "u12G4");
        RunResult malformedFile = runCli("--locale", "pt_BR",
                "--baseline", tempDir.resolve("missing.baseline").toString(), tempDir.toString());

        assertEquals(2, malformedFile.status());
        assertEquals("", malformedFile.stdout());
        assertEquals("propdrift: " + directory()
                + "/broken.properties:1: malformed \\u escape\n", malformedFile.stderr());
    }

    @Test
    void readmeDocumentsTheLocaleUsageAndSection() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertTrue(readme.contains("java -cp target/classes io.github.gulliby.propdrift.Main "
                + "[--format text|json] [--locale CODE]... [--baseline FILE] "
                + "[--write-baseline FILE] path/to/bundles"));
        assertTrue(readme.contains("Usage: propdrift [--format text|json] [--locale CODE]... "
                + "[--baseline FILE] [--write-baseline FILE] DIR"));
        assertTrue(readme.contains("| `--locale CODE` | Report only the findings in files of this locale, "
                + "for example `de` or `pt_BR`; repeatable. Also `--locale=CODE`. |"));

        String localeSection = """
                ### One locale

                Use `--locale` when you maintain a single translation. It reports only the findings in the files of the given locale (the suffix of the file name, such as `de` for `messages_de.properties` or `pt_BR` for `messages_pt_BR.properties`) and leaves out the rest, including the findings of base files. For the worked example directory `ex` below:

                ```text
                java -cp target/classes io.github.gulliby.propdrift.Main --locale de ex
                ex/messages_de.properties:1: PR002 missing key bye
                ex/messages_de.properties:2: PR004 placeholders differ for files: base {0},{1}, locale {0}
                ex/messages_de.properties:3: PR003 extra key extra
                ex/messages_de.properties:4: PR005 duplicate key hello (first defined on line 1)
                4 problems in 1 file
                ```

                The option can be repeated (`--locale de --locale es` reports both files), and a locale without findings prints `ok`. The counts in the summary, in `--format json` and in `--baseline` and `--write-baseline` are those of the selected findings only. The code must be two lowercase letters, optionally followed by `_` and two uppercase letters; a locale that no file in the directory has is an error (exit 2):

                ```text
                propdrift: no file for locale "pt_BR"
                ```
                """;
        assertTrue(readme.contains(localeSection));
        assertTrue(readme.indexOf(localeSection) < readme.indexOf("### JSON output"));
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

    private void writeWorkedExample() throws IOException {
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
    }

    private void write(Path directory, String fileName, String... lines) throws IOException {
        Files.writeString(directory.resolve(fileName), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private String directory() {
        String display = tempDir.toString().replace('\\', '/');
        while (display.endsWith("/")) {
            display = display.substring(0, display.length() - 1);
        }
        return display;
    }

    private record RunResult(int status, String stdout, String stderr) {
    }
}
