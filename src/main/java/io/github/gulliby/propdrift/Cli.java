package io.github.gulliby.propdrift;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Command-line orchestration for checking properties bundles. */
public final class Cli {
    private Cli() {
    }

    /**
     * Runs Propdrift and writes diagnostics using explicit LF line endings.
     *
     * @return 0 for clean input or help, 1 when findings were produced, and 2
     *         for invalid arguments or input errors
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (args.length == 1 && "--help".equals(args[0])) {
            out.print("Usage: propdrift DIR\n");
            return 0;
        }
        if (args.length == 0) {
            return reportError(err, "propdrift: directory required");
        }
        for (String arg : args) {
            if (arg.startsWith("-")) {
                return reportError(err, "propdrift: unknown option \"" + arg + "\"");
            }
        }
        if (args.length >= 2) {
            return reportError(err, "propdrift: exactly one directory required");
        }

        String dir = args[0];
        Path directory;
        try {
            directory = Path.of(dir);
        } catch (InvalidPathException exception) {
            return reportError(err, "propdrift: " + dir + ": not a directory");
        }
        try {
            if (!Files.isDirectory(directory)) {
                return reportError(err, "propdrift: " + dir + ": not a directory");
            }
        } catch (SecurityException exception) {
            return reportError(err, "propdrift: " + dir + ": not a directory");
        }

        List<Path> propertyFiles;
        try (Stream<Path> children = Files.list(directory)) {
            propertyFiles = children
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException | DirectoryIteratorException | UncheckedIOException | SecurityException exception) {
            return reportError(err, "propdrift: " + dir + ": " + reason(exception));
        }

        List<Bundles.BundleFile> parsedFiles = new ArrayList<>(propertyFiles.size());
        for (Path propertyFile : propertyFiles) {
            String fileName = propertyFile.getFileName().toString();
            String pathForDisplay = displayPath(dir, fileName);
            List<PropertiesParser.Entry> entries;
            try {
                entries = PropertiesParser.parse(Files.readAllBytes(propertyFile));
            } catch (PropertiesParser.MalformedUnicodeEscapeException exception) {
                return reportError(err, "propdrift: " + pathForDisplay + ":" + exception.line()
                        + ": malformed \\u escape");
            } catch (CharacterCodingException exception) {
                return reportError(err, "propdrift: " + pathForDisplay + ": invalid UTF-8");
            } catch (IOException | SecurityException exception) {
                return reportError(err, "propdrift: " + pathForDisplay + ": " + reason(exception));
            }
            parsedFiles.add(new Bundles.BundleFile(fileName, pathForDisplay, entries));
        }

        List<Finding> findings = new ArrayList<>(Bundles.analyze(parsedFiles));
        findings.sort(Comparator.comparing(Finding::displayPath)
                .thenComparingInt(Finding::line)
                .thenComparing(Finding::rule));

        if (findings.isEmpty()) {
            out.print("ok\n");
            return 0;
        }

        Set<String> filesWithFindings = new HashSet<>();
        for (Finding finding : findings) {
            out.print(finding.format());
            out.print('\n');
            filesWithFindings.add(finding.displayPath());
        }

        int problemCount = findings.size();
        int fileCount = filesWithFindings.size();
        out.print(problemCount + (problemCount == 1 ? " problem in " : " problems in ")
                + fileCount + (fileCount == 1 ? " file\n" : " files\n"));
        return 1;
    }

    /**
     * Returns the display form used for diagnostic paths.
     *
     * <p>Backslashes are normalized to slashes and trailing slashes are
     * removed. A directory represented by {@code .} omits the directory prefix.</p>
     */
    static String displayPath(String dir, String fileName) {
        String normalized = Objects.requireNonNull(dir, "dir").replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals(".")) {
            return Objects.requireNonNull(fileName, "fileName");
        }
        return normalized + "/" + Objects.requireNonNull(fileName, "fileName");
    }

    private static int reportError(PrintStream err, String message) {
        err.print(message);
        err.print('\n');
        return 2;
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }
}
