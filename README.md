<p align="center"><img src="docs/logo.svg" width="96" alt="" /></p>

# propdrift

A small Java command-line checker that finds duplicate keys, missing or extra translations and mismatched placeholders in UTF-8 properties bundles.

[![CI](https://github.com/Gulliby-development/propdrift/actions/workflows/ci.yml/badge.svg)](https://github.com/Gulliby-development/propdrift/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## Why

Translations drift: a key is added to the base file but never to the German one, a placeholder gets reordered, a key is defined twice. propdrift catches this before you ship localized resources, with no dependencies beyond the JDK.

## Installation

propdrift requires Java 21 or newer and Maven. From a checkout of this project, build it with Maven (see [Build](#build)). No extra runtime dependencies are required. To run the built application from the project root:

```text
java -cp target/classes io.github.gulliby.propdrift.Main path/to/bundles
```

Replace `path/to/bundles` with the directory containing the bundle files. The same Java command works on Windows, macOS, and Linux.

## Build

Run the full verification suite, including tests:

```text
mvn -B -q verify
```

## Usage

Run propdrift on exactly one directory containing properties files; optionally select `text` (the default) or `json` output. Replace `path/to/bundles` with your resource directory:

```text
java -cp target/classes io.github.gulliby.propdrift.Main [--format text|json] [--baseline FILE] [--write-baseline FILE] path/to/bundles
```

Use `--help` to print the usage line:

```text
java -cp target/classes io.github.gulliby.propdrift.Main --help
Usage: propdrift [--format text|json] [--baseline FILE] [--write-baseline FILE] DIR
```

The checker reads regular `.properties` files directly in the directory; it does not recurse into subdirectories. File names are case-sensitive. Locale suffixes such as `_de` and `_pt_BR` associate a file with its base bundle (for example, `messages_de.properties` belongs to `messages.properties`).

### Options

| Option | Description |
| --- | --- |
| `DIR` | The directory to check (exactly one). |
| `--format text\|json` | Output format: `text` (default) or `json`. Also `--format=json`. |
| `--baseline FILE` | Do not report the findings listed in the baseline file `FILE`. Also `--baseline=FILE`. |
| `--write-baseline FILE` | Write all current findings to `FILE` as a version 2 baseline and exit 0. Also `--write-baseline=FILE`. |
| `--help` | Print the usage line. |

### JSON output

Run JSON output on the worked example directory with:

```text
java -cp target/classes io.github.gulliby.propdrift.Main --format json ex
```

```json
{"findings":[{"path":"ex/messages_de.properties","line":1,"rule":"PR002","message":"missing key bye"},{"path":"ex/messages_de.properties","line":2,"rule":"PR004","message":"placeholders differ for files: base {0},{1}, locale {0}"},{"path":"ex/messages_de.properties","line":3,"rule":"PR003","message":"extra key extra"},{"path":"ex/messages_de.properties","line":4,"rule":"PR005","message":"duplicate key hello (first defined on line 1)"},{"path":"ex/orphan_es.properties","line":1,"rule":"PR001","message":"missing base file for orphan"}],"problems":5,"files":2}
```

JSON strings use standard escaping for quotes, backslashes, and control characters; non-ASCII characters are preserved. `problems` is the number of findings, and `files` is the number of distinct files with findings.

### Baselines

Baselines let you adopt propdrift when a project already has findings. Write the current findings to a version 2 baseline with:

```text
java -cp target/classes io.github.gulliby.propdrift.Main --write-baseline ex.baseline ex
wrote 5 findings to ex.baseline
```

The resulting `ex.baseline` contains one entry per finding. Fields are separated by real tab characters:

```text
# propdrift baseline v2
PR002	messages_de.properties	missing key bye
PR003	messages_de.properties	extra key extra
PR004	messages_de.properties	placeholders differ for files: base {0},{1}, locale {0}
PR005	messages_de.properties	duplicate key hello (first defined on line 1)
PR001	orphan_es.properties	missing base file for orphan
```

Use it to report only new findings:

```text
java -cp target/classes io.github.gulliby.propdrift.Main --baseline ex.baseline ex
ok (5 suppressed by baseline)
```

A finding matches an entry by file name, rule, and message, not by directory or line number. For PR005, the message includes the line where the key was first defined, so moving that definition can change whether it matches. Each entry suppresses one finding; duplicate findings need duplicate entries, and unused entries are ignored.

A saved plain-text report from an earlier run is also accepted as a version 1 baseline. For example, save one with `propdrift ex > old.txt`; from this checkout, the equivalent command is:

```text
java -cp target/classes io.github.gulliby.propdrift.Main ex > old.txt
```

To convert it to version 2, read and write the same file:

```text
java -cp target/classes io.github.gulliby.propdrift.Main --baseline old.txt --write-baseline old.txt ex
```

When a new finding remains, for example after removing `bye` from `ex/messages_fr.properties`, text output is:

```text
ex/messages_fr.properties:1: PR002 missing key bye
1 problem in 1 file (5 suppressed by baseline)
```

With `--format json`, the suppressed count follows `files` (including when it is zero):

```json
{"findings":[{"path":"ex/messages_fr.properties","line":1,"rule":"PR002","message":"missing key bye"}],"problems":1,"files":1,"suppressed":5}
```

With all five findings suppressed, text output is `ok (5 suppressed by baseline)`. JSON output includes the suppressed count after `files`:

```json
{"findings":[],"problems":0,"files":0,"suppressed":5}
```

## Rules

| Rule | Check |
| --- | --- |
| PR001 | A locale file has no matching base file. |
| PR002 | A key from the base file is missing in a locale file. |
| PR003 | A locale file defines a key absent from the base file. |
| PR004 | A shared key has different numbered-placeholder sets in the base and locale values. |
| PR005 | A key is defined more than once in the same file; the later definition is ignored by the other checks. |

Placeholder sets are compared by distinct integer value, so `{00}` is equivalent to `{0}`; a placeholder is a brace-enclosed run of one to nine ASCII digits. Findings are sorted by path, line, and rule. A successful check prints `ok` (or an empty `findings` array with `--format json`); otherwise, findings are followed by a problem and affected-file count.

## Supported properties syntax

Files are decoded as UTF-8. A leading byte-order mark is accepted. Physical lines may end in LF, CRLF, or CR. Blank lines and lines whose first non-whitespace character is `#` or `!` are ignored. Leading spaces, tabs, and form feeds are skipped.

Entries use `=`, `:`, or whitespace to separate a key and value. Backslash escapes support `\t`, `\n`, `\r`, `\f`, `\u` followed by exactly four hexadecimal digits, and a backslash before any other character to include that character literally. An odd number of trailing backslashes continues an entry onto the next physical line; leading spaces, tabs, and form feeds on the continued line are removed. Trailing value whitespace is preserved.

## Example

Suppose a directory contains these files:

`messages.properties`

```properties
# greetings
hello=Hello, {0}!
bye=Goodbye
files=You have {0} files in {1}
```

`messages_de.properties`

```properties
hello=Hallo, {0}!
files=Sie haben {0} Dateien
extra = nur hier
hello=doppelt
```

`messages_fr.properties`

```properties
hello=Bonjour, {0}!
bye=Au revoir
files=Vous avez {1} fichiers dans {0}
```

`orphan_es.properties`

```properties
a=b
```

Running `java -cp target/classes io.github.gulliby.propdrift.Main ex`, where `ex` is the directory holding these files, reports (each path is prefixed with the scanned directory):

```text
ex/messages_de.properties:1: PR002 missing key bye
ex/messages_de.properties:2: PR004 placeholders differ for files: base {0},{1}, locale {0}
ex/messages_de.properties:3: PR003 extra key extra
ex/messages_de.properties:4: PR005 duplicate key hello (first defined on line 1)
ex/orphan_es.properties:1: PR001 missing base file for orphan
5 problems in 2 files
```

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | The directory is valid, or `--help` was requested. |
| 1 | One or more findings were reported. |
| 2 | Arguments, the directory, a file, its properties syntax, or a baseline file could not be processed. |

## Development

The project uses the Java standard library (release 21) in production and JUnit 5 for tests. Run all tests and checks from the project root with `mvn -B -q verify`. Tests use temporary directories for file-based cases.

## License

MIT, see [LICENSE](LICENSE).

---

Built with BAKED, an open-source coding agent (bench loop round 9, BAKED a5870fe8).
