package io.github.gulliby.propdrift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Groups properties files into bundles and creates PR001–PR005 findings. */
public final class Bundles {
    private static final Pattern LOCALE_STEM =
            Pattern.compile("^(.+)_([a-z]{2}(_[A-Z]{2})?)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([0-9]{1,9})}");

    private Bundles() {
    }

    /**
     * An input file for bundle analysis. The filename is used for grouping and
     * ordinal ordering; displayPath is retained in any resulting findings.
     */
    public record BundleFile(
            String fileName,
            String displayPath,
            List<PropertiesParser.Entry> entries) {
        public BundleFile {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(displayPath, "displayPath");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * Applies duplicate and locale-bundle rules to parsed files.
     *
     * @param files files in a directory; analysis applies ordinal filename order
     * @return findings in rule-production order (the CLI applies final sorting)
     */
    public static List<Finding> analyze(List<BundleFile> files) {
        Objects.requireNonNull(files, "files");

        List<BundleFile> orderedFiles = new ArrayList<>(files);
        orderedFiles.sort(Comparator.comparing(BundleFile::fileName));

        List<Finding> findings = new ArrayList<>();
        Map<String, BundleFile> baseFiles = new LinkedHashMap<>();
        Map<String, List<AnalyzedFile>> localeFiles = new LinkedHashMap<>();

        for (BundleFile file : orderedFiles) {
            String stem = stem(file.fileName());
            Matcher matcher = LOCALE_STEM.matcher(stem);
            AnalyzedFile analyzed = analyzeDuplicates(file);
            findings.addAll(analyzed.duplicateFindings());

            if (matcher.matches()) {
                String prefix = matcher.group(1);
                localeFiles.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(analyzed);
            } else {
                baseFiles.put(stem, file);
            }
        }

        for (Map.Entry<String, List<AnalyzedFile>> group : localeFiles.entrySet()) {
            String prefix = group.getKey();
            List<AnalyzedFile> locales = group.getValue();
            BundleFile base = baseFiles.get(prefix);
            if (base == null) {
                AnalyzedFile firstLocale = locales.get(0);
                findings.add(new Finding(
                        firstLocale.file().displayPath(),
                        1,
                        "PR001",
                        "missing base file for " + prefix));
                continue;
            }

            Map<String, PropertiesParser.Entry> baseEntries =
                    analyzeDuplicates(base).uniqueEntries();
            for (AnalyzedFile locale : locales) {
                compareBundle(baseEntries, locale, findings);
            }
        }

        return List.copyOf(findings);
    }

    private static AnalyzedFile analyzeDuplicates(BundleFile file) {
        Map<String, PropertiesParser.Entry> firstByKey = new LinkedHashMap<>();
        List<Finding> duplicates = new ArrayList<>();

        for (PropertiesParser.Entry entry : file.entries()) {
            PropertiesParser.Entry first = firstByKey.putIfAbsent(entry.key(), entry);
            if (first != null) {
                duplicates.add(new Finding(
                        file.displayPath(),
                        entry.line(),
                        "PR005",
                        "duplicate key " + entry.key() + " (first defined on line " + first.line() + ")"));
            }
        }
        return new AnalyzedFile(file, firstByKey, duplicates);
    }

    private static void compareBundle(
            Map<String, PropertiesParser.Entry> baseEntries,
            AnalyzedFile locale,
            List<Finding> findings) {
        for (String key : baseEntries.keySet()) {
            if (!locale.uniqueEntries().containsKey(key)) {
                findings.add(new Finding(
                        locale.file().displayPath(),
                        1,
                        "PR002",
                        "missing key " + key));
            }
        }

        for (Map.Entry<String, PropertiesParser.Entry> localeEntry
                : locale.uniqueEntries().entrySet()) {
            String key = localeEntry.getKey();
            PropertiesParser.Entry baseEntry = baseEntries.get(key);
            PropertiesParser.Entry translatedEntry = localeEntry.getValue();
            if (baseEntry == null) {
                findings.add(new Finding(
                        locale.file().displayPath(),
                        translatedEntry.line(),
                        "PR003",
                        "extra key " + key));
            } else {
                Set<Integer> basePlaceholders = placeholders(baseEntry.value());
                Set<Integer> localePlaceholders = placeholders(translatedEntry.value());
                if (!basePlaceholders.equals(localePlaceholders)) {
                    findings.add(new Finding(
                            locale.file().displayPath(),
                            translatedEntry.line(),
                            "PR004",
                            "placeholders differ for " + key + ": base "
                                    + renderPlaceholders(basePlaceholders) + ", locale "
                                    + renderPlaceholders(localePlaceholders)));
                }
            }
        }
    }

    private static Set<Integer> placeholders(String value) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    private static String renderPlaceholders(Set<Integer> placeholders) {
        if (placeholders.isEmpty()) {
            return "none";
        }
        StringBuilder rendered = new StringBuilder();
        for (int placeholder : placeholders) {
            if (!rendered.isEmpty()) {
                rendered.append(',');
            }
            rendered.append('{').append(placeholder).append('}');
        }
        return rendered.toString();
    }

    private static String stem(String fileName) {
        return fileName.substring(0, fileName.length() - ".properties".length());
    }

    private record AnalyzedFile(
            BundleFile file,
            Map<String, PropertiesParser.Entry> uniqueEntries,
            List<Finding> duplicateFindings) {
    }
}
