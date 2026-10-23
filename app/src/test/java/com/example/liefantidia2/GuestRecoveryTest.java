package com.example.liefantidia2;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuestRecoveryTest {
    @Test public void recoveryCodesAreUniqueAndAllowFormattedEntry() {
        String first = GuestRecovery.generate();
        String second = GuestRecovery.generate();
        assertTrue(GuestRecovery.valid(first));
        assertNotEquals(first, second);
        assertEquals(GuestRecovery.email(first), GuestRecovery.email(GuestRecovery.display(first)));
        assertNotEquals(GuestRecovery.email(first), GuestRecovery.email(second));
        assertTrue(GuestRecovery.isRecoveryEmail(GuestRecovery.email(first)));
        assertFalse(GuestRecovery.valid("short"));
    }
}
