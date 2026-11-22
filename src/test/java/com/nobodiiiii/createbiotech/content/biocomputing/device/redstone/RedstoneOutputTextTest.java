package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedstoneOutputTextTest {
    @ParameterizedTest @ValueSource(strings = {"zh_cn", "en_us"})
    void successfulSnapshotHasReadableResultAndNoEmptyFailureLabel(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            assertEquals(locale.equals("zh_cn") ? "状态已刷新 · 最近操作：电平已应用" : "Snapshot refreshed · last: Level applied",
                RedstoneOutputScreen.notice("ok", "applied", "", false).getString());
            assertEquals(values.get("create_biotech.biocomputing.serial.code.ready"), RedstoneOutputScreen.statusText("ready").getString());
            assertEquals(values.get("create_biotech.biocomputing.communication.face.east"), RedstoneOutputScreen.directionText("east").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"zh_cn", "en_us"})
    void waitingAndLineFailureStayDistinctFromThePreviousApplication(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            String result = RedstoneOutputScreen.notice("ok", "applied", "collision", true).getString();
            assertTrue(result.contains(values.get("create_biotech.redstone_output.waiting")));
            assertTrue(result.contains(values.get("create_biotech.redstone_output.code.applied")));
            assertTrue(result.endsWith(values.get("create_biotech.redstone_output.notice_failure").formatted(
                values.get("create_biotech.biocomputing.serial.code.collision"))));
            assertFalse(result.contains(values.get("create_biotech.redstone_output.code.ok")), "Pending refresh must not claim a fresh snapshot");
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"zh_cn", "en_us"})
    void authorizationVersionAndRecoveryFailuresAreTranslatedWithoutLosingUnknownDiagnostics(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (String code : List.of("denied", "changed", "stale_session", "device_changed", "unsupported_version",
                    "invalid_configuration", "member_changed", "out_of_range", "version_exhausted")) {
                String expected = values.get("create_biotech.redstone_output.code." + code);
                assertNotNull(expected, "Device result must not use an unrelated transport/archive description");
                assertEquals(expected, RedstoneOutputScreen.statusText(code).getString(), code);
            }
            for (String code : List.of("interrupted", "reloaded", "buffer_full", "gateway_unformed"))
                assertEquals(values.get("create_biotech.biocomputing.serial.code." + code), RedstoneOutputScreen.statusText(code).getString());
            assertEquals("\"future_error\\n\\u00a7c\"", RedstoneOutputScreen.statusText("future_error\n§c").getString());
            assertEquals("\"new_face\"", RedstoneOutputScreen.directionText("new_face").getString());
        } finally { Language.inject(old); }
    }

    private static Map<String, String> translations(String locale) throws Exception {
        var values = new LinkedHashMap<String, String>();
        JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang/" + locale + ".json")))
            .getAsJsonObject().entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
        return values;
    }
    private static Language language(Map<String, String> values) {
        return new Language() {
            @Override public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return values.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        };
    }
}
