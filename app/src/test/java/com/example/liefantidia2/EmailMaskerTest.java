package com.example.liefantidia2;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmailMaskerTest {
    @Test public void masksLongAddressAsRequested() {
        assertEquals("li****12@gmail.com", EmailMasker.mask("little12@gmail.com"));
    }
    @Test public void neverDisplaysShortLocalPartsInFull() {
        assertEquals("****@example.com", EmailMasker.mask("a@example.com"));
        assertEquals("a****@example.com", EmailMasker.mask("ab@example.com"));
        assertEquals("a****c@example.com", EmailMasker.mask("abc@example.com"));
        assertEquals("a****d@example.com", EmailMasker.mask("abcd@example.com"));
    }
    @Test public void nullAndMalformedAddressesAreNotExposed() {
        for (String email : new String[] {null, "", "private-address", "a@", "a@b@c", "@domain"}) {
            assertEquals("登録済みアカウント", EmailMasker.mask(email));
        }
    }
    @Test public void preservesUnicodeCharacterBoundaries() {
        assertEquals("😀****@example.com", EmailMasker.mask("😀a@example.com"));
    }
}
