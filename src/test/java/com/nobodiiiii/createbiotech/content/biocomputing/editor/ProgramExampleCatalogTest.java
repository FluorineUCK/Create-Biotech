package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProgramExampleCatalogTest {
    private static final Map<String,Integer> EXPANDED_WORKCELL = Map.of(
        "motion_panel_a",1280, "line_drain_a",1280, "line_filter_switch_a",2048,
        "line_machine_switch_a",3072, "line_process_panel_a",4096, "line_resume_panel_a",5120, "line_resume_panel_report_a",6144);
    private static final Set<String> DEFAULT_MEDIA = Set.of("speaker_media_default_play", "speaker_media_default_stop",
        "speaker_media_library_stage", "speaker_media_delivery_stage", "speaker_media_output_stage", "speaker_media_frame_stage");
    private static com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits masterClericHardware() {
        return new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder())
            .select(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
                net.minecraft.resources.ResourceLocation.parse("minecraft:villager"),
                net.minecraft.resources.ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
    }
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    private static InputStream resource(net.minecraft.resources.ResourceLocation id) {
        return ProgramExampleCatalogTest.class.getClassLoader().getResourceAsStream(
            "assets/" + id.getNamespace() + "/" + id.getPath());
    }
    @Test void localResourceIndexIsNotLimitedToOneEditorNetworkPacket() {
        String json = " ".repeat(ProgramEditorWire.MAX_BYTES) + "{\"version\":1,\"examples\":[]}";
        assertTrue(ProgramExampleCatalog.load(id -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).isEmpty());
    }
    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyPublishedExampleHasLocalizedInstructions(String locale) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/create_biotech/lang/" + locale + ".json")) {
            var language = com.google.gson.JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var example : ProgramExampleCatalog.load(ProgramExampleCatalogTest::resource)) {
                var keys = new ArrayList<>(example.instructions()); keys.add(example.title());
                for (String key : keys) assertTrue(language.has(key) && !language.get(key).getAsString().isBlank(), key);
            }
        }
    }
    @Test void packagedCatalogImportsTheSameOrdinaryDocumentsAsTheTestedJointExample() throws Exception {
        var catalog = ProgramExampleCatalog.load(ProgramExampleCatalogTest::resource);
        assertEquals(Set.of("recipe_book_read","recipe_book_decode","recipe_book_gate","recipe_book_query","recipe_stream_read","recipe_stream_decode","recipe_stream_query","library_segmented_read_collect","library_segmented_read_verify","library_segmented_read_stream","recipe_browser_pick","recipe_browser_checkout","recipe_browser_dispatch","recipe_browser_route","recipe_browser_terminal","recipe_browser_query","radio_probe_send","radio_probe_receive","motion_panel_a", "line_drain_a", "line_filter_switch_a", "line_machine_switch_a", "line_process_panel_a", "line_resume_panel_a", "line_resume_panel_report_a", "reader_selection_emitter","reader_selection_preparer","reader_selection_terminal","reader_sample_range_a","routed_production_a","routed_production_e","routed_production_d","routed_production_f","material_selection_service","material_selection_coordinator","recipe_preview_v","recipe_preview_p","recipe_preview_s","recipe_page_a","recipe_page_d","cluster_journal_worker","cluster_journal_preparer","cluster_journal_coordinator","cluster_journal_adapter","cluster_serial_caller","cluster_serial_gateway","cluster_serial_worker","library_service_b","library_service_c","library_service_d","library_terminal_a","library_inspect_a","library_ack_a","library_write_a","library_write_d","library_read_a","library_read_b","library_read_c","cluster_library_caller", "cluster_library_worker", "xbus_calculator_caller", "xbus_calculator_worker",
                "serial_bridge_source", "serial_bridge_converter", "serial_bridge_receiver", "library_serial_command", "library_read_driver", "library_stat_driver", "library_write_driver", "library_write_recovery_driver", "library_delete_driver", "library_list_driver", "library_query_recovery_driver",
                "cluster_serial_library_coordinator", "cluster_serial_library_worker", "cluster_serial_library_caller", "library_failure_recovery_driver",
                "stock_application_a", "stock_application_b", "stock_application_publish", "stock_application_read", "stock_application_recipe_row", "stock_application_recipe_catalog", "stock_application_recipe_sample", "stock_application_recipe_selection", "stock_application_recipe_order", "stock_application_recipe_dispatch", "stock_application_recipe_materials", "stock_application_recipe_route", "stock_application_recipe_submit", "stock_material_a", "serial_exchange", "serial_exchange_logic", "serial_exchange_uart", "serial_exchange_probe",
                "speaker_media_program", "speaker_media_wire", "speaker_media_default_play", "speaker_media_default_stop",
                "speaker_media_library_stage", "speaker_media_delivery_stage", "speaker_media_output_stage", "speaker_media_frame_stage",
                "stock_native_item", "stock_native_order", "stock_native_preview",
                "stock_native_sample", "stock_native_inspect", "stock_native_identity", "stock_native_plain_order", "stock_native_prepare", "stock_native_identity_selected", "stock_native_handoff", "stock_native_packet", "stock_native_packet_scoped", "stock_native_supply", "stock_native_supply_followup", "stock_native_finish", "stock_native_guard", "stock_native_ack", "stock_native_ack_current", "stock_native_ack_exchange", "library_entry_store", "library_entry_coordinator", "stock_native_archive_store", "stock_native_archive_check", "stock_native_archived_identity", "stock_native_archive_read", "stock_native_scope_submit", "stock_native_scope_archive", "stock_native_scope_read", "stock_native_scope_check", "stock_native_scope_identity", "library_segmented_session_read", "library_segmented_session_store", "library_segmented_session_coordinator", "library_segmented_session_buffer", "library_segmented_step_slice", "library_segmented_step_pump", "library_segmented_range_reply", "library_segmented_read_adapter", "library_segmented_session_read_decode", "library_segmented_archive_read_collect", "library_segmented_step_read_verify", "library_segmented_step_read_stream",
                "stock_native_scope_handoff", "stock_native_scope_live", "stock_native_scope_finish",
                "production_submit_a", "production_poll_a", "production_b", "production_c", "text_output_a", "music_output_a", "arm_move_a", "arm_points_nbt_a", "arm_status_a", "sequenced_motion_a", "deployer_process_a", "label_output_a", "particle_output_a", "virtual_filter_a", "virtual_filter_list_a", "component_settings_a", "narrator_output_a", "chat_output_a", "terminal_ui_a", "terminal_ui_claim", "text_output_b", "text_output_c", "redstone_output_a", "redstone_output_b", "redstone_output_c", "reader_sample_a", "reader_sample_b", "reader_sample_c", "reader_sample_component_a", "value_check_integer", "reader_output_a", "reader_output_b", "reader_output_c", "reader_output_d", "reader_output_e"),
            catalog.stream().map(ProgramExampleCatalog.Example::id).collect(java.util.stream.Collectors.toSet()));
        for (var example : catalog) {
            var actual = ProgramExampleCatalog.preview(example, ProgramExampleCatalogTest::resource, LIMITS);
            var expected = Set.of("library_inspect_a","library_ack_a").contains(example.id()) ? NativeLibraryRecoveryDocuments.build(example.id().equals("library_inspect_a")?"inspect":"ack") : ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(
                java.nio.file.Path.of("examples/biocomputing/" + java.nio.file.Path.of(example.document().getPath()).getFileName())).strip()), LIMITS);
            assertEquals(expected, actual);
            if (example.id().equals("library_terminal_a")) assertEquals(NativeLibraryTerminalDocuments.build(), actual);
            if (example.id().startsWith("library_service_")) assertEquals(NativeLibraryServiceDocuments.build(example.id().substring("library_service_".length())), actual);
            if (Set.of("library_write_a", "library_write_d").contains(example.id()))
                assertEquals(NativeLibraryWriteDocuments.build(example.id().substring("library_write_".length())), actual);
            assertEquals(actual, new ProgramWorkspace(actual).snapshot());
            if (Set.of("serial_exchange", "serial_exchange_logic", "serial_exchange_uart").contains(example.id())) {
                var hardware = switch (example.id()) {
                    case "serial_exchange" -> SerialExchangeDriverDocuments.legacyEquippedParser();
                    case "serial_exchange_uart" -> SerialExchangeDriverDocuments.witch();
                    default -> masterClericHardware();
                };
                com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(actual, hardware);
                continue;
            }
            if (EXPANDED_WORKCELL.containsKey(example.id())) {
                int capacity = EXPANDED_WORKCELL.get(example.id());
                var limits = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(capacity, 65536, 64);
                assertTrue(example.instructions().contains("create_biotech.biocomputing.editor.example.workcell.budget_"+capacity));
                assertTrue(example.tags().contains("sensing:output"));
                actual.validate(limits);
                assertTrue(actual.flow().orElseThrow().program(actual.archive()).usage(limits).programUnits() > masterClericHardware().hardware().programUnits());
                continue;
            }
            if (DEFAULT_MEDIA.contains(example.id()) || example.id().startsWith("library_segmented_")) {
                com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(actual, masterClericHardware());
                if (DEFAULT_MEDIA.contains(example.id())) assertTrue(example.tags().contains("sensing:output"), example.id());
                continue;
            }
            if (Set.of("speaker_media_program", "speaker_media_wire").contains(example.id())) {
                long words = example.id().equals("speaker_media_program") ? 640 : 256;
                actual.validate(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(words, 65536, 8));
                continue;
            }
            if (Set.of("recipe_book_read","recipe_book_decode","recipe_book_gate","recipe_book_query","recipe_stream_read","recipe_stream_decode","recipe_stream_query","library_segmented_read_collect","library_segmented_read_verify","library_segmented_read_stream","music_output_a", "arm_move_a", "arm_points_nbt_a", "arm_status_a", "sequenced_motion_a", "deployer_process_a", "label_output_a", "particle_output_a", "virtual_filter_a", "virtual_filter_list_a", "component_settings_a", "narrator_output_a", "chat_output_a", "terminal_ui_a", "terminal_ui_claim", "stock_application_a", "stock_application_b", "stock_application_publish", "stock_application_read", "stock_application_recipe_row", "stock_application_recipe_catalog", "stock_application_recipe_sample", "stock_application_recipe_selection", "stock_application_recipe_order", "stock_application_recipe_dispatch", "stock_application_recipe_materials", "stock_application_recipe_route", "stock_application_recipe_submit",
                    "stock_material_a", "serial_exchange", "serial_exchange_probe", "stock_native_item", "stock_native_order", "stock_native_preview", "stock_native_sample", "stock_native_inspect", "stock_native_identity", "stock_native_plain_order", "stock_native_prepare", "stock_native_identity_selected", "stock_native_handoff", "stock_native_packet", "stock_native_packet_scoped", "stock_native_supply", "stock_native_supply_followup", "stock_native_finish", "stock_native_guard", "stock_native_ack", "stock_native_ack_current", "stock_native_ack_exchange", "library_entry_store", "library_entry_coordinator", "stock_native_archive_store", "stock_native_archive_check", "stock_native_archived_identity", "stock_native_archive_read", "stock_native_scope_submit", "stock_native_scope_archive", "stock_native_scope_read", "stock_native_scope_check", "stock_native_scope_identity", "stock_native_scope_handoff", "stock_native_scope_live", "stock_native_scope_finish","recipe_browser_query","recipe_browser_pick","recipe_browser_checkout","recipe_browser_dispatch","recipe_browser_route","recipe_browser_terminal").contains(example.id())) {
                var profile=example.id().equals("stock_application_a") ? SerialExchangeDriverDocuments.legacyEquippedParser() : StockApplicationDriverDocuments.hardware();
                com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(actual, profile);
                continue; // These examples declare the existing master-cleric hardware, not the fallback 32-unit preset.
            }
            actual.validate(Set.of("reader_selection_emitter","reader_selection_preparer","reader_selection_terminal","reader_sample_range_a","routed_production_a","routed_production_e","routed_production_d","routed_production_f","material_selection_service","material_selection_coordinator","recipe_preview_v","recipe_preview_p","recipe_preview_s","recipe_page_a","recipe_page_d","production_submit_a","production_poll_a","production_b","production_c","text_output_a","text_output_b","text_output_c","reader_output_a","reader_output_b","reader_output_c","reader_output_d","reader_output_e","reader_sample_a","reader_sample_b","reader_sample_c", "reader_sample_component_a","redstone_output_a","redstone_output_b","redstone_output_c","cluster_journal_worker","cluster_journal_preparer","cluster_journal_coordinator","cluster_journal_adapter","cluster_serial_caller","cluster_serial_gateway","library_service_b","library_service_d","library_terminal_a","library_inspect_a","library_ack_a","library_write_a","library_write_d","library_read_a","library_read_b","library_write_driver", "library_delete_driver", "cluster_serial_library_coordinator").contains(example.id()) ? new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(256, 65536, 8)
                : Set.of("value_check_integer","cluster_serial_worker","library_service_c","library_read_c","library_read_driver", "library_stat_driver", "library_write_recovery_driver", "library_list_driver", "library_query_recovery_driver", "cluster_serial_library_caller", "library_failure_recovery_driver").contains(example.id())
                ? new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(192, 49152, 6)
                : new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(32, 32768, 4));
        }
    }
    @Test void missingMalformedOversizedAndWrongKindDocumentsFailWithinExistingArchiveBounds() {
        var example = ProgramExampleCatalog.load(ProgramExampleCatalogTest::resource).getFirst();
        assertThrows(IllegalArgumentException.class, () -> ProgramExampleCatalog.preview(example, id -> null, LIMITS));
        for (String invalid : List.of("not base64", "A".repeat(ComputerProgramDocumentScreen.MAX_TEXT + 1),
                Base64.getEncoder().encodeToString(SubgraphDocumentWire.encode(SubgraphDocumentTest.fixture(), LIMITS)))) {
            assertThrows(RuntimeException.class, () -> ProgramExampleCatalog.preview(example, id ->
                new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)), LIMITS));
        }
        assertThrows(RuntimeException.class, () -> ProgramExampleCatalog.preview(example,
            ProgramExampleCatalogTest::resource, new ArchiveLimits(1, 0, 1)));
        var stream = new ByteArrayInputStream(new byte[ProgramExampleCatalog.MAX_CATALOG_BYTES * 2]);
        assertThrows(IllegalArgumentException.class, () -> ProgramExampleCatalog.load(id -> stream));
        assertEquals(ProgramExampleCatalog.MAX_CATALOG_BYTES - 1, stream.available(), "Oversized streams are not fully buffered");
    }

    @Test void integratedCatalogRetainsOutputTagsAndTheLargerLocalResourceBound() throws Exception {
        var catalog = ProgramExampleCatalog.load(ProgramExampleCatalogTest::resource);
        var music = catalog.stream().filter(item -> item.id().equals("music_output_a")).findFirst().orElseThrow();
        assertEquals(Set.of("sensing:output"), music.tags());
        var narrator = catalog.stream().filter(item -> item.id().equals("narrator_output_a")).findFirst().orElseThrow();
        assertEquals(Set.of("sensing:output"), narrator.tags());
        assertThrows(UnsupportedOperationException.class, () -> music.tags().add("unexpected"));
        var serial = catalog.stream().filter(item -> item.id().equals("serial_exchange")).findFirst().orElseThrow();
        assertTrue(serial.tags().isEmpty(), "A generic transport driver is not an output-device category");
        assertTrue(new ProgramExampleCatalog.Example(music.id(), music.document(), music.title(), music.instructions()).tags().isEmpty());
        try (var stream = resource(ProgramExampleCatalog.INDEX)) {
            int size = stream.readAllBytes().length;
            assertTrue(size > 64 * 1024, "Combined examples no longer fit the source UI's old limit");
            assertTrue(size <= ProgramExampleCatalog.MAX_CATALOG_BYTES);
        }
    }

    @Test void unsupportedVersionDuplicateIdsAndNonExamplePathsAreRejected() {
        var item = "{\"id\":\"a\",\"title\":\"title\",\"document\":\"create_biotech:biocomputing/examples/a.bcp\",\"instructions\":[\"body\"]}";
        for (String json : List.of("{\"version\":2,\"examples\":[]}",
            "{\"version\":1,\"examples\":[" + item + "," + item + "]}",
            "{\"version\":1,\"examples\":[" + item.replace("create_biotech:", "Invalid Namespace:") + "]}",
            "{\"version\":1,\"examples\":[" + item.replace("biocomputing/examples/a.bcp", "lang/en_us.json") + "]}"))
            assertThrows(IllegalArgumentException.class, () -> ProgramExampleCatalog.load(id ->
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"version\":1}", "{\"version\":1,\"examples\":[{}]}", "{\"version\":1,\"examples\":[{\"id\":\"a\",\"title\":\"a\",\"document\":\"create_biotech:biocomputing/examples/a.bcp\"}]}", "[]", "null"})
    void malformedCatalogIsAUserFacingValidationFailure(String json) {
        assertThrows(IllegalArgumentException.class, () -> ProgramExampleCatalog.load(id ->
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }
}
