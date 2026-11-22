package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecentEventTextTest {
    private static final String PREFIX = "create_biotech.biocomputing.";

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void pausedPeerDiagnosticDoesNotClaimTheObservingComputerItselfIsPaused(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            assertEquals(locale.equals("zh_cn") ? "XBus 对端暂停，暂不可用" : "XBus peer paused; unavailable",
                RecentEventScreen.eventText("execution_paused").getString());
            assertEquals(values.get(PREFIX + "events.code.paused"), RecentEventScreen.eventText("paused").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void executionRejectionIsNotPresentedAsAnExternalCableFailure(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            String wrapper = locale.equals("zh_cn") ? "XBus 执行：%s" : "XBus execution: %s";
            for (String cause : List.of("type_mismatch", "memory_limit", "archive_limit", "history_conflict"))
                assertEquals(wrapper.formatted(values.get(PREFIX + "cluster.code.xbus_" + cause)),
                    RecentEventScreen.eventText("execution_" + cause).getString());
            assertEquals(wrapper.formatted(values.get(PREFIX + "events.code.xbus_recovery_required")),
                RecentEventScreen.eventText("execution_recovery_required").getString());
            assertEquals(wrapper.formatted("future_failure"), RecentEventScreen.eventText("execution_future_failure").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void internalRouteFailureDoesNotExposeRawUnreachableCode(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            assertEquals(locale.equals("zh_cn") ? "线路不可达" : "Connection unreachable",
                RecentEventScreen.eventText("unreachable").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void externalEventsUseWiringDiagnosticsAndDescribeBindingRecoveryNotSuccessfulDelivery(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            String wrapper = locale.equals("zh_cn") ? "外部线路：%s" : "External wiring: %s";
            for (String code : List.of("unreachable", "target_unloaded", "topology_unloaded", "closed", "identity_changed", "invalid_save"))
                assertEquals(wrapper.formatted(values.get(PREFIX + "wiring.code." + code)), RecentEventScreen.eventText("wiring_" + code).getString());
            assertEquals(locale.equals("zh_cn") ? "外部线路恢复可用（不代表会合完成）" : "External binding available again (not a completed rendezvous)",
                RecentEventScreen.eventText("wiring_restored").getString());
            assertEquals(wrapper.formatted("future_status"), RecentEventScreen.eventText("wiring_future_status").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void actualClusterEventsReuseReadableStatusTextAndKeepUnknownCodes(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (String code : List.of("xbus_paused", "endpoint_changed", "controller_unloaded",
                    "partial_unloaded", "signal_closed", "xbus_history_conflict", "xbus_archive_limit"))
                assertEquals(values.get(PREFIX + "cluster.code." + code), RecentEventScreen.eventText(code).getString(), code);
            assertEquals(values.get(PREFIX + "events.code.xbus_exchanged"), RecentEventScreen.eventText("xbus_exchanged").getString(),
                "The more specific event wording still takes precedence");
            assertEquals("future_status", RecentEventScreen.eventText("future_status").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void pairedPinsDescribeBothStatesWithoutClaimingAHandoff(String locale) throws Exception {
        var values = translations(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (var left : com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState.Mode.values())
                for (var right : com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState.Mode.values()) {
                    String a = left.name().toLowerCase(Locale.ROOT), b = right.name().toLowerCase(Locale.ROOT);
                    assertEquals(values.get(PREFIX + "cluster.wire_wait_status").formatted(
                        values.get(PREFIX + "cluster.pin_mode." + a), values.get(PREFIX + "cluster.pin_mode." + b)),
                        RecentEventScreen.eventText("xbus_" + a + "_" + b).getString());
                }
            for (String unknown : List.of("xbus_unknown_read", "xbus_complete_read_extra", "xbus_complete_read_"))
                assertEquals(unknown, RecentEventScreen.eventText(unknown).getString());
        } finally { Language.inject(old); }
    }

    private static Map<String, String> translations(String locale) throws Exception {
        var result = new LinkedHashMap<String, String>();
        JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang/" + locale + ".json")))
            .getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        return result;
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
