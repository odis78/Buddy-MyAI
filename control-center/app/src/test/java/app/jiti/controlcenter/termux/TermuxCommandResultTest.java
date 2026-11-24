package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandResultTest {
    @Test public void outputIsRedactedAndCapped() {
        TermuxCommandResult result = new TermuxCommandResult(7, "BUDDY_TOKEN=secret-value", "", 0, -1, "", false);
        assertFalse(result.stdout.contains("secret-value"));
        String longText = new String(new char[17000]).replace('\0', 'x');
        TermuxCommandResult capped = new TermuxCommandResult(8, longText, "", 0, -1, "", false);
        assertTrue(capped.stdout.length() < 16500);
    }

    @Test public void successRequiresZeroExitAndNoTransportError() {
        assertTrue(new TermuxCommandResult(1, "", "", 0, -1, "", false).succeeded());
        assertFalse(new TermuxCommandResult(1, "", "", 1, -1, "", false).succeeded());
        assertFalse(new TermuxCommandResult(1, "", "", 0, 7, "transport failure", false).succeeded());
        assertFalse(new TermuxCommandResult(1, "", "", 1, 7, "transport failure", false).succeeded());
    }
}
