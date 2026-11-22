package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Official packaged resources, real translatable components and the public-facing node catalog. */
class ComputerUiTranslationsTest {
    private static final String PREFIX = "create_biotech.biocomputing.";

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void paletteLabelsResolveAndLocalizedNamesAreSearchable(String locale) throws Exception {
        var values = load(locale);
        var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (var category : ComputerNodePalette.Category.values()) readable(category.label());
            for (String key : List.of("search", "empty", "hint")) readable(ComputerNodePalette.text(key));
            for (var kind : ComputerNodePalette.Kind.values()) {
                readable(kind.label());
                assertTrue(ComputerNodePalette.search(ComputerNodePalette.Category.ALL, kind.label().getString()).contains(kind));
                assertTrue(ComputerNodePalette.search(kind.category(), kind.id()).contains(kind));
            }
        } finally { Language.inject(old); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void sortAndTerminalLabelsPreserveEverySuppliedArgument(String locale) throws Exception {
        var values = load(locale);
        var old = Language.getInstance();
        var keys = new LinkedHashMap<String, Integer>();
        keys.put("tree.generate_expression", 0);
        keys.put("flow_editor.add_xbus_status", 0);
        keys.put("flow_editor.block_xbus_status", 1);
        keys.put("flow_editor.xbus_status_hint", 0);
        keys.put("editor.has_field_hint", 0);
        keys.put("editor.keys_hint", 0);
        keys.put("editor.path_expression_generated", 2);
        for (String key : List.of("title", "hint", "missing_module", "missing_port", "ascending", "descending", "open", "apply", "back")) keys.put("sort." + key, 0);
        keys.put("sort.module", 2);
        for (String key : List.of("item", "result", "direction")) keys.put("sort." + key, 1);
        for (String key : List.of("edit", "binding_sample", "literal", "columns", "button_entry", "entry", "argument", "configure", "bind", "up", "down", "remove", "path_key", "path_index", "path_parent", "tree_previous", "tree_next", "installed", "apply", "cancel", "select_binding", "waiting", "refresh", "previous", "next", "back", "submit", "inspect", "frozen", "title", "empty")) keys.put("terminal." + key, 0);
        for (String key : List.of("button_slot", "input_slot", "literal_mode", "path_root", "invalid", "unknown_item", "click_accepted", "click_rejected")) keys.put("terminal." + key, 1);
        for (String key : List.of("page", "problem", "state")) keys.put("terminal." + key, 2);
        for (String key : List.of("output_pending", "output_failed", "output_no_program", "output_unavailable")) keys.put("terminal." + key, 0);
        for (String key : List.of("task_target_title", "task_definitions_empty", "task_target_hint", "inspect_task", "claim_task", "claim_task_hint", "tasks_empty", "tasks_frozen", "task_claimed")) keys.put("terminal." + key, 0);
        for (String key : List.of("select_task", "select_task_hint", "tasks")) keys.put("terminal." + key, 1);
        for (String key : List.of("task_signature", "task_row_hint")) keys.put("terminal." + key, 2);
        for (var kind : TerminalDraft.Kind.values()) for (String prefix : List.of("add", "kind")) keys.put("terminal." + prefix + "." + kind.name().toLowerCase(Locale.ROOT), 0);
        for (String slot : List.of("title", "enabled", "inputs")) keys.put("terminal.button_slot." + slot, 0);
        for (String slot : List.of("initial", "enabled", "inputs")) keys.put("terminal.input_slot." + slot, 0);
        try {
            Language.inject(language(values));
            for (var entry : keys.entrySet()) {
                String key = PREFIX + entry.getKey();
                assertTrue(values.containsKey(key), "Missing official translation: " + locale + " " + key);
                Object[] args = java.util.stream.IntStream.range(0, entry.getValue()).mapToObj(i -> "[arg" + i + "🐝]").toArray();
                assertEquals(entry.getValue().longValue(), java.util.regex.Pattern.compile("%s").matcher(values.get(key)).results().count(), key);
                Component label = Component.translatable(key, args);
                readable(label);
                assertEquals(values.get(key).formatted(args), label.getString(), key);
                for (Object arg : args) assertTrue(label.getString().contains(arg.toString()), key);
            }
            readable(Component.translatable(PREFIX + "sort.direction", Component.translatable(PREFIX + "sort.descending")));
        } finally { Language.inject(old); }
    }

    private static void readable(Component label) {
        assertFalse(label.getString().isBlank());
        assertFalse(label.getString().contains(PREFIX), "Missing official translation: " + label.getString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void clusterControlsExposeCoordinatesRoutesAndDiagnosticArguments(String locale) throws Exception {
        var values = load(locale); var old = Language.getInstance();
        var keys = new LinkedHashMap<String, Integer>();
        keys.put("cluster.title", 0);
        keys.put("cluster.refresh", 0);
        keys.put("cluster.save", 0);
        keys.put("cluster.members", 0);
        keys.put("cluster.ports", 0);
        keys.put("cluster.connections", 0);
        keys.put("cluster.configure_member", 0);
        keys.put("cluster.add_port", 0);
        keys.put("cluster.add_connection", 0);
        keys.put("cluster.back", 0);
        keys.put("cluster.member_name", 0);
        keys.put("cluster.apply", 0);
        keys.put("cluster.remove_binding", 0);
        keys.put("cluster.cancel", 0);
        keys.put("cluster.public_port", 0);
        keys.put("cluster.output", 0);
        keys.put("cluster.external", 0);
        keys.put("cluster.no_endpoints", 0);
        keys.put("cluster.input", 0);
        keys.put("cluster.member_select", 0);
        keys.put("cluster.enabled", 0);
        keys.put("cluster.disabled", 0);
        keys.put("cluster.remove", 0);
        keys.put("cluster.connection_name", 0);
        keys.put("cluster.source_member_select", 0);
        keys.put("cluster.target_member_select", 0);
        keys.put("cluster.invalid", 1);
        keys.put("cluster.draft_changed", 0);
        keys.put("cluster.waiting", 0);
        keys.put("cluster.previous", 0);
        keys.put("cluster.next", 0);
        keys.put("cluster.member", 4);
        keys.put("cluster.candidate", 4);
        keys.put("cluster.candidate_detail", 5);
        keys.put("cluster.port_enabled", 3);
        keys.put("cluster.port_disabled", 3);
        keys.put("cluster.connection_enabled", 3);
        keys.put("cluster.connection_disabled", 3);
        keys.put("cluster.status", 3);
        keys.put("cluster.code.incomplete_configuration", 0);
        keys.put("cluster.result.incomplete_configuration", 1);
        keys.put("wiring.code.incomplete_configuration", 0);
        for (var status : com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Status.values())
            keys.put("cluster.code." + status.name().toLowerCase(Locale.ROOT), 0);
        for (var status : com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.values())
            keys.put("cluster.code." + status.name().toLowerCase(Locale.ROOT), 0);
        for (String code : List.of("ready", "configured", "stale", "invalid", "too_large", "unavailable", "unformed", "open_shell", "not_controller", "invalid_controller", "controller_conflict", "partial_unloaded", "wrong_space", "size", "computer_limit", "invalid_computer", "illegal_internal", "ambiguous", "coordinate_range", "access_denied", "already_bound", "closed", "controller_unloaded", "endpoint_changed", "identity_changed", "member_missing", "member_unloaded", "no_program", "not_attached", "not_contacting", "not_member", "return_route_missing", "invalid_configuration", "invalid_save", "busy", "archive_limit", "configuration_limit", "memory_limit", "queue_full", "source_limit", "accepted", "duplicate", "identity_conflict", "type_mismatch", "unsupported", "stale_reply", "invalid_name", "not_found", "provider_failure", "topology_unloaded", "endpoint_limit", "topology_limit", "owner_unavailable")) {
            keys.put("cluster.code." + code, 0);
            keys.put("cluster.result." + code, 1);
        }
        try {
            Language.inject(language(values));
            for (var entry : keys.entrySet()) {
                String key = PREFIX + entry.getKey();
                assertTrue(values.containsKey(key), "Missing device translation: " + locale + " " + key);
                Object[] args = java.util.stream.IntStream.range(0, entry.getValue()).mapToObj(i -> "[arg" + i + "]").toArray();
                assertEquals(entry.getValue().longValue(), java.util.regex.Pattern.compile("%s").matcher(values.get(key)).results().count(), key);
                Component label = Component.translatable(key, args); readable(label);
                assertEquals(values.get(key).formatted(args), label.getString(), key);
                for (Object arg : args) assertTrue(label.getString().contains(arg.toString()), key);
            }
            var member = Component.translatable(PREFIX + "cluster.candidate", -123, 64, 789,
                Component.translatable(PREFIX + "cluster.code.member_unloaded"));
            readable(member);
            for (String part : List.of("-123", "64", "789", values.get(PREFIX + "cluster.code.member_unloaded")))
                assertTrue(member.getString().contains(part), member.getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void programDocumentsAndBundledExampleInstructionsResolve(String locale) throws Exception {
        var values = load(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (String key : List.of("program_package", "program_hint", "program_exported", "program_imported",
                    "export_program", "import_program", "copy_package", "paste_package", "document_exchange",
                    "examples", "examples_reload", "examples_hint", "examples_preview", "examples_previous", "examples_next",
                    "instructions_previous", "instructions_next", "example_replace"))
                readable(Component.translatable(PREFIX + "editor." + key));
            var catalog = ProgramExampleCatalog.load(id -> getClass().getClassLoader().getResourceAsStream(
                "assets/" + id.getNamespace() + "/" + id.getPath()));
            for (var example : catalog) {
                readable(Component.translatable(example.title()));
                for (String key : example.instructions()) readable(Component.translatable(key));
            }
            String key = PREFIX + "editor.instructions_page";
            assertEquals(2, java.util.regex.Pattern.compile("%s").matcher(values.getOrDefault(key, "")).results().count());
            assertEquals(values.get(key).formatted(2, 7), Component.translatable(key, 2, 7).getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void libraryFieldsPermissionsAndDiagnosticsResolveWithoutLosingQueueArguments(String locale) throws Exception {
        var values = load(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (String key : List.of("library.title", "library.volume", "library.name", "library.value", "library.kind.data", "library.kind.program", "library.kind.subgraph", "library.access.closed", "library.access.read_only", "library.access.read_write", "library.runtime", "endpoint.name"))
                readable(Component.translatable(PREFIX + key));
            for (String code : List.of("accepted", "duplicate", "stale", "identity_conflict", "queue_full", "source_limit", "closed", "unavailable", "no_program", "type_mismatch", "memory_limit", "busy", "archive_limit", "unsupported", "stale_reply", "configured", "invalid_configuration", "invalid_save", "configuration_limit", "endpoint_changed", "invalid_name", "not_found", "ambiguous", "provider_failure", "topology_unloaded", "topology_limit", "endpoint_limit", "owner_unavailable", "access_denied", "wrong_space", "ok", "changed", "denied", "invalid", "ok_view_too_large", "too_large", "duplicate_volume", "missing_volume", "unnamed_volume", "missing_entry", "missing_medium", "full", "unsupported_version", "unsupported_medium", "invalid_volume", "unversioned_medium", "medium_changed", "version_exhausted", "stale_version", "conflict", "readonly", "invalid_request", "invalid_contents", "unloaded", "missing", "unformed", "partial", "too_wide", "core_conflict", "space_mismatch", "idle", "ready", "sequence_exhausted", "revision_exhausted", "batch_pending", "commit_retry", "response_too_large"))
                readable(Component.translatable(PREFIX + "library.code." + code));
            for (var state : com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfReader.Status.values())
                readable(Component.translatable(PREFIX + "library.code." + state.name().toLowerCase(Locale.ROOT)));
            String key = PREFIX + "library.runtime";
            assertEquals(3, java.util.regex.Pattern.compile("%s").matcher(values.get(key)).results().count());
            assertEquals(values.get(key).formatted(19, "pending", "sent"), Component.translatable(key, 19, "pending", "sent").getString());
        } finally { Language.inject(old); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void independentSubgraphDocumentLabelsAndVersionedPackageNoticesResolve(String locale) throws Exception {
        var values = load(locale); var old = Language.getInstance();
        try {
            Language.inject(language(values));
            for (String key : List.of("subgraph_package", "subgraph_hint", "export_subgraph", "import_subgraph",
                    "copy_package", "paste_package", "back"))
                readable(Component.translatable(PREFIX + "editor." + key));
            for (String suffix : List.of("subgraph_exported", "subgraph_imported")) {
                String key = PREFIX + "editor." + suffix;
                assertTrue(values.containsKey(key), "Missing subgraph notice: " + locale + " " + key);
                assertEquals(3, java.util.regex.Pattern.compile("%s").matcher(values.get(key)).results().count(), key);
                var label = Component.translatable(key, "计算🐝", 17, 3);
                readable(label);
                assertEquals(values.get(key).formatted("计算🐝", 17, 3), label.getString());
            }
        } finally { Language.inject(old); }
    }

    static Map<String, String> load(String locale) throws Exception {
        String resource = "assets/create_biotech/lang/" + locale + ".json";
        try (var stream = ComputerUiTranslationsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            try (var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                var result = new LinkedHashMap<String, String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    assertNull(result.put(key, reader.nextString()), "Duplicate language key: " + key);
                }
                reader.endObject();
                return result;
            }
        }
    }

    static Language language(Map<String, String> values) {
        return new Language() {
            @Override public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return values.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        };
    }
}
