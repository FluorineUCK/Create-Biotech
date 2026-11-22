package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReaderTextTest {
    @ParameterizedTest @ValueSource(strings = {"zh_cn", "en_us"})
    void readerAndTransportStatesStayReadableWithoutConcealingUnknownErrors(String locale) throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang/" + locale + ".json"))).getAsJsonObject();
        var values = new HashMap<String, String>(); json.entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
        var old = Language.getInstance();
        try {
            Language.inject(new Language() {
                public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
                public boolean has(String key) { return values.containsKey(key); }
                public boolean isDefaultRightToLeft() { return false; }
                public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
            });
            for (var code : List.of("configured", "denied", "access_denied", "stale_session", "component_denied", "invalid_configuration", "snapshot_limit", "reply_unrepresentable")) {
                String expected = values.get("create_biotech.reader.code." + code); assertNotNull(expected);
                assertEquals(expected, ReaderScreen.statusText(code).getString());
            }
            for (var code : List.of("collision", "payload_too_large", "reloaded", "buffer_full"))
                assertEquals(values.get("create_biotech.biocomputing.serial.code." + code), ReaderScreen.statusText(code).getString());
            assertEquals("", ReaderScreen.statusText("").getString());
            assertEquals("\"mod_error\\n\\u00a7c\"", ReaderScreen.statusText("mod_error\n§c").getString());
            for (String key : List.of("sources", "components", "grant", "revoke", "grant_hint", "additional", "builtin"))
                assertNotNull(values.get("create_biotech.reader." + key));
            for (String component : List.of("entities", "environment", "redstone", "world")) {
                String key = "create_biotech.biocomputing.component." + component;
                assertTrue(values.containsKey(key), "The actual reader grant list needs a label for " + key);
                assertFalse(values.get(key).isBlank());
            }
        } finally { Language.inject(old); }
    }
}
