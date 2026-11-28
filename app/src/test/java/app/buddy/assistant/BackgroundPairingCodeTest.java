package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class BackgroundPairingCodeTest {
    @Test
    public void parsesSixAsciiDigits() {
        assertEquals("123456", Background.codeIn("Pairing code: 123456"));
    }

    @Test
    public void parsesGroupedDigits() {
        assertEquals("123456", Background.codeIn("123 456"));
        assertEquals("123456", Background.codeIn("123-456"));
    }

    @Test
    public void normalizesUnicodeDigits() {
        assertEquals("123456", Background.codeIn("١٢٣٤٥٦"));
    }

    @Test
    public void rejectsShortOrAmbiguousCodes() {
        assertNull(Background.codeIn("12345"));
        assertNull(Background.codeIn("1234567"));
        assertNull(Background.codeIn("no code yet"));
    }
}
