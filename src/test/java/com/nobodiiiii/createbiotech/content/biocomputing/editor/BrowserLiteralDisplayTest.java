package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BrowserLiteralDisplayTest {
    @Test void quotesFormattingControlsAndEscapeLookingTextReversibly() {
        assertEquals("\"a\\u00a7c\\u00a7\\t\\r\\u0001\\u007f\\u0085\\u2028\\u2029\\\\u00a7\\\\t\"",
            BrowserLiteralDisplay.quote("a§c§\t\r\u0001\u007f\u0085\u2028\u2029\\u00a7\\t"));
        assertNotEquals(BrowserLiteralDisplay.quote("§"), BrowserLiteralDisplay.quote("\\u00a7"));
        assertNotEquals(BrowserLiteralDisplay.quote("\t"), BrowserLiteralDisplay.quote("\\t"));
    }
}
