package io.github.gulliby.propdrift;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Command-line orchestration for checking properties bundles. */
public final class Cli {
    private static final Pattern LOCALE_CODE = Pattern.compile("[a-z]{2}(?:_[A-Z]{2})?");
    private static final Pattern LOCALE_SUFFIX = Pattern.compile("^.+_([a-z]{2}(?:_[A-Z]{2})?)$");

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
            out.print("Usage: propdrift [--format text|json] [--locale CODE]... [--baseline FILE] [--write-baseline FILE] DIR\n");
            return 0;
        }
        if (args.length == 0) {
            return reportError(err, "propdrift: directory required");
        }

        String format = "text";
        String baselineFile = null;
        String writeBaselineFile = null;
        List<String> directories = new ArrayList<>();
        List<String> locales = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--format".equals(arg)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
                    return reportError(err, "propdrift: --format requires a value");
                }
                format = args[++index];
            } else if (arg.startsWith("--format=")) {
                format = arg.substring("--format=".length());
            } else if ("--baseline".equals(arg)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
                    return reportError(err, "propdrift: --baseline requires a value");
                }
                baselineFile = args[++index];
            } else if ("--locale".equals(arg)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
                    return reportError(err, "propdrift: --locale requires a value");
                }
                locales.add(args[++index]);
            } else if (arg.startsWith("--locale=")) {
                String locale = arg.substring("--locale=".length());
                if (locale.isEmpty()) {
                    return reportError(err, "propdrift: --locale requires a value");
                }
                locales.add(locale);
            } else if (arg.startsWith("--baseline=")) {
                baselineFile = arg.substring("--baseline=".length());
                if (baselineFile.isEmpty()) {
                    return reportError(err, "propdrift: --baseline requires a value");
                }
            } else if ("--write-baseline".equals(arg)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
                    return reportError(err, "propdrift: --write-baseline requires a value");
                }
                writeBaselineFile = args[++index];
            } else if (arg.startsWith("--write-baseline=")) {
                writeBaselineFile = arg.substring("--write-baseline=".length());
                if (writeBaselineFile.isEmpty()) {
                    return reportError(err, "propdrift: --write-baseline requires a value");
                }
            } else if (arg.startsWith("-")) {
                return reportError(err, "propdrift: unknown option \"" + arg + "\"");
            } else {
                directories.add(arg);
            }
        }
        if (!"text".equals(format) && !"json".equals(format)) {
            return reportError(err, "propdrift: invalid --format \"" + format + "\"");
        }
        for (String locale : locales) {
            if (!LOCALE_CODE.matcher(locale).matches()) {
                return reportError(err, "propdrift: invalid --locale \"" + locale + "\"");
            }
        }
        if (directories.isEmpty()) {
            return reportError(err, "propdrift: directory required");
        }
        if (directories.size() >= 2) {
            return reportError(err, "propdrift: exactly one directory required");
        }

        String dir = directories.get(0);
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

        if (!locales.isEmpty()) {
            Set<String> availableLocales = new HashSet<>();
            for (Bundles.BundleFile parsedFile : parsedFiles) {
                String locale = localeOf(parsedFile.fileName());
                if (locale != null) {
                    availableLocales.add(locale);
                }
            }
            for (String locale : locales) {
                if (!availableLocales.contains(locale)) {
                    return reportError(err, "propdrift: no file for locale \"" + locale + "\"");
                }
            }
        }
  
        List<Finding> findings = new ArrayList<>(Bundles.analyze(parsedFiles));
        if (!locales.isEmpty()) {
            Set<String> selectedLocales = new HashSet<>(locales);
            Set<String> selectedFiles = new HashSet<>();
            for (Bundles.BundleFile parsedFile : parsedFiles) {
                if (selectedLocales.contains(localeOf(parsedFile.fileName()))) {
                    selectedFiles.add(parsedFile.displayPath());
                }
            }
            findings.removeIf(finding -> !selectedFiles.contains(finding.displayPath()));
        }
        findings.sort(Comparator.comparing(Finding::displayPath)
                .thenComparingInt(Finding::line)
                .thenComparing(Finding::rule));

        int suppressed = 0;
        List<Finding> reportedFindings = findings;
        if (baselineFile != null) {
            Baseline baseline;
            byte[] baselineBytes;
            try {
                baselineBytes = Files.readAllBytes(Path.of(baselineFile));
            } catch (InvalidPathException | IOException | SecurityException exception) {
                return reportError(err, "propdrift: " + baselineFile + ": cannot read baseline");
            }
            try {
                baseline = Baseline.parse(baselineBytes);
            } catch (CharacterCodingException exception) {
                return reportError(err, "propdrift: " + baselineFile + ": invalid UTF-8");
            } catch (Baseline.BaselineException exception) {
                if (exception.line() == 0) {
                    return reportError(err, "propdrift: " + baselineFile + ": " + exception.getMessage());
                }
                return reportError(err, "propdrift: " + baselineFile + ":" + exception.line()
                        + ": " + exception.getMessage());
            }
            Baseline.Applied applied = baseline.apply(findings);
            reportedFindings = applied.remaining();
            suppressed = applied.suppressed();
        }

        if (writeBaselineFile != null) {
            String baselineText = Baseline.render(findings);
            try {
                Files.write(Path.of(writeBaselineFile), baselineText.getBytes(StandardCharsets.UTF_8));
            } catch (InvalidPathException | IOException | SecurityException exception) {
                return reportError(err, "propdrift: " + writeBaselineFile + ": cannot write baseline");
            }
            out.print("wrote " + findings.size() + (findings.size() == 1 ? " finding to " : " findings to ")
                    + writeBaselineFile + "\n");
            return 0;
        }

        Set<String> filesWithFindings = new HashSet<>();
        for (Finding finding : reportedFindings) {
            filesWithFindings.add(finding.displayPath());
        }
        if ("json".equals(format)) {
            if (baselineFile == null) {
                out.print(JsonReport.render(reportedFindings, filesWithFindings.size()));
            } else {
                out.print(JsonReport.render(reportedFindings, filesWithFindings.size(), suppressed));
            }
            out.print('\n');
            return reportedFindings.isEmpty() ? 0 : 1;
        }

        if (reportedFindings.isEmpty()) {
            if (baselineFile == null) {
                out.print("ok\n");
            } else {
                out.print("ok (" + suppressed + " suppressed by baseline)\n");
            }
            return 0;
        }

        for (Finding finding : reportedFindings) {
            out.print(finding.format());
            out.print('\n');
        }

        int problemCount = reportedFindings.size();
        int fileCount = filesWithFindings.size();
        String summary = problemCount + (problemCount == 1 ? " problem in " : " problems in ")
                + fileCount + (fileCount == 1 ? " file" : " files");
        if (baselineFile != null) {
            summary += " (" + suppressed + " suppressed by baseline)";
        }
        out.print(summary + "\n");
        return 1;
    }

    /**
     * Returns the display form used for diagnostic paths.
     *
     * <p>Backslashes are normalized to slashes and trailing slashes are
     * removed. A directory represented by {@code .} omits the directory prefix.</p>
     */
    private static String localeOf(String fileName) {
        String stem = fileName.substring(0, fileName.length() - ".properties".length());
        Matcher matcher = LOCALE_SUFFIX.matcher(stem);
        return matcher.matches() ? matcher.group(1) : null;
    }

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
