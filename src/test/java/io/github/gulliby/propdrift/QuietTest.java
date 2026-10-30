package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuietTest {
    private static final String NO_QUIET_JSON = "propdrift: --quiet cannot be used with --format json\n";

    @TempDir
    Path tempDir;

    /** One command line: "@DIR@" is the worked example directory and "@BASE@" a baseline file with the three PR002 to PR004 findings. */
    private record Row(String[] args, int status, String stdout, String stderr) {
    }

    private static Row row(int status, String stdout, String stderr, String... args) {
        return new Row(args, status, stdout, stderr);
    }

    @Test
    void quietRowsOnTheWorkedExample() throws IOException {
        Path ex = Files.createDirectory(tempDir.resolve("ex"));
        writeWorkedExample(ex);
        Path clean = Files.createDirectory(tempDir.resolve("clean"));
        write(clean, "messages.properties", "a=b");
        write(clean, "messages_de.properties", "a=c");
        Path baseline = tempDir.resolve("three.baseline");
        Files.writeString(baseline, "# propdrift baseline v2\n"
                + "PR002\tmessages_de.properties\tmissing key bye\n"
                + "PR003\tmessages_de.properties\textra key extra\n"
                + "PR001\torphan_es.properties\tmissing base file for orphan\n", StandardCharsets.UTF_8);
        Path written = tempDir.resolve("all.baseline");

        Row[] rows = {
            row(1, "5 problems in 2 files\n", "", "--quiet", "@DIR@"),
            row(1, "5 problems in 2 files\n", "", "@DIR@", "--quiet"),
            row(1, "5 problems in 2 files\n", "", "--quiet", "--quiet", "@DIR@"),
            row(1, "5 problems in 2 files\n", "", "--format", "text", "--quiet", "@DIR@"),
            row(1, "1 problem in 1 file\n", "", "--quiet", "--rule", "PR004", "@DIR@"),
            row(1, "4 problems in 1 file\n", "", "--locale", "de", "--quiet", "@DIR@"),
            row(0, "ok\n", "", "--quiet", "--locale", "fr", "--rule", "PR002", "@DIR@"),
            row(0, "ok\n", "", "--quiet", "@CLEAN@"),
            row(1, "2 problems in 1 file (3 suppressed by baseline)\n", "", "--quiet", "--baseline", "@BASE@", "@DIR@"),
            row(0, "ok (3 suppressed by baseline)\n", "", "--quiet", "--baseline", "@BASE@", "--rule", "PR001", "--rule", "PR002", "--rule", "PR003",
                    "@DIR@"),
            row(0, "wrote 5 findings to @OUT@\n", "", "--quiet", "--write-baseline", "@OUT@", "@DIR@"),
            row(2, "", NO_QUIET_JSON, "--quiet", "--format", "json", "@DIR@"),
            row(2, "", NO_QUIET_JSON, "--format=json", "--quiet", "@DIR@"),
            row(2, "", "propdrift: unknown option \"--quiet=1\"\n", "--quiet=1", "@DIR@"),
            row(2, "", "propdrift: invalid --format \"xml\"\n", "--quiet", "--format", "xml", "@DIR@"),
            row(2, "", "propdrift: directory required\n", "--quiet"),
            row(2, "", "propdrift: no file for locale \"pt_BR\"\n", "--quiet", "--locale", "pt_BR", "@DIR@"),
        };
        for (Row row : rows) {
            String[] args = new String[row.args().length];
            for (int i = 0; i < args.length; i++) {
                args[i] = fill(row.args()[i], ex, clean, baseline, written);
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int status;
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                    PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                status = Cli.run(args, out, err);
            }
            String label = String.join(" ", row.args());
            assertEquals(row.status(), status, label);
            assertEquals(fill(row.stdout(), ex, clean, baseline, written), stdout.toString(StandardCharsets.UTF_8), label);
            assertEquals(row.stderr(), stderr.toString(StandardCharsets.UTF_8), label);
        }
    }

    private static String fill(String text, Path ex, Path clean, Path baseline, Path written) {
        return text.replace("@DIR@", display(ex)).replace("@CLEAN@", display(clean)).replace("@BASE@", display(baseline))
                .replace("@OUT@", display(written));
    }

    private static String display(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void writeWorkedExample(Path directory) throws IOException {
        write(directory, "messages.properties", "# greetings", "hello=Hello, {0}!", "bye=Goodbye", "files=You have {0} files in {1}");
        write(directory, "messages_de.properties", "hello=Hallo, {0}!", "files=Sie haben {0} Dateien", "extra = nur hier", "hello=doppelt");
        write(directory, "messages_fr.properties", "hello=Bonjour, {0}!", "bye=Au revoir", "files=Vous avez {1} fichiers dans {0}");
        write(directory, "orphan_es.properties", "a=b");
    }

    private void write(Path directory, String fileName, String... lines) throws IOException {
        Files.writeString(directory.resolve(fileName), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
