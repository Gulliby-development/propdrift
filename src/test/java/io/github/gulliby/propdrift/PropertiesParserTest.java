package io.github.gulliby.propdrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class PropertiesParserTest {
    private static final String BACKSLASH = "\\";

    @Test
    void parserAgreesWithJavaPropertiesForSupportedOracleInputs() throws Exception {
        List<String> samples = List.of(
                "a=1",
                "a:1",
                "a 1",
                "a  =  1",
                "a" + BACKSLASH + " b=c",
                "a" + BACKSLASH + ":b=c",
                "a=b" + BACKSLASH + "\n   c",
                "a=b" + BACKSLASH + BACKSLASH,
                "a=b" + BACKSLASH + BACKSLASH + "\nother=value",
                BACKSLASH + "u0041=x",
                "k=" + BACKSLASH + "u00e9",
                "k=" + BACKSLASH + "t",
                "#c",
                "!c",
                "  # c",
                "",
                "\n\t  \n",
                "k",
                "k=",
                "k = v ",
                "k=a=b",
                "k=a:b",
                "a=1\rb=2\r",
                "a=1\r\nb=2\r\n",
                "k=" + BACKSLASH + "x",
                BACKSLASH + " k=v",
                "\fkey=value"
        );

        assertTrue(samples.size() >= 25, "the parser oracle must cover at least 25 inputs");
        for (String text : samples) {
            Properties oracle = new Properties();
            oracle.load(new StringReader(text));

            Map<String, String> expected = new LinkedHashMap<>();
            for (String name : oracle.stringPropertyNames()) {
                expected.put(name, oracle.getProperty(name));
            }
            Map<String, String> actual = new LinkedHashMap<>();
            for (PropertiesParser.Entry entry : PropertiesParser.parse(text)) {
                actual.put(entry.key(), entry.value());
            }

            assertEquals(expected, actual, () -> "parser mismatch for input: " + printable(text));
        }
    }

    @Test
    void recordsFirstPhysicalLineForEntriesAndContinuations() throws Exception {
        String text = "# heading\n\nfirst=value\ncontinued=ab" + BACKSLASH
                + "\n    cd" + BACKSLASH + "\n\t ef\nlast=x";

        List<PropertiesParser.Entry> entries = PropertiesParser.parse(text);

        assertEquals(List.of(
                new PropertiesParser.Entry("first", "value", 3),
                new PropertiesParser.Entry("continued", "abcdef", 4),
                new PropertiesParser.Entry("last", "x", 7)), entries);
    }

    @Test
    void stripsLeadingUtf8Bom() throws Exception {
        byte[] bytes = ("\uFEFFkey=value\n").getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of(new PropertiesParser.Entry("key", "value", 1)),
                PropertiesParser.parse(bytes));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingIt() {
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};

        assertThrows(CharacterCodingException.class, () -> PropertiesParser.parse(malformed));
    }

    @Test
    void reportsMalformedUnicodeEscapeAtLogicalEntryStartLine() {
        String text = "# heading\nvalid=value\n\nbroken=prefix" + BACKSLASH
                + "\n  " + BACKSLASH + "u12G4\n";

        PropertiesParser.MalformedUnicodeEscapeException error = assertThrows(
                PropertiesParser.MalformedUnicodeEscapeException.class,
                () -> PropertiesParser.parse(text));

        assertEquals(4, error.line());
        assertEquals("malformed \\u escape", error.getMessage());
    }

    private static String printable(String text) {
        return text.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
