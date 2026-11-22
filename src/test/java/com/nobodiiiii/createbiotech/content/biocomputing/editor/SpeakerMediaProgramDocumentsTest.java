package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerMediaProgramDocumentsTest {
    private static final Path EXAMPLES = Path.of("examples/biocomputing");
    private static final String USER = "speaker_media_program", WIRE = "speaker_media_wire";
    private static final DataType POSITION = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    private static final DataType ROWS = new DataType.ListType(new DataType.RecordType(Map.of("o", TEXT, "e", TEXT, "p", INTEGER, "a", INTEGER)));

    @Test void enlargedLocalIndexStillStopsReadingAtItsFixedBound() {
        var stream = new java.io.ByteArrayInputStream(new byte[ProgramExampleCatalog.MAX_CATALOG_BYTES * 2]);
        assertThrows(IllegalArgumentException.class, () -> ProgramExampleCatalog.load(id -> stream));
        assertEquals(ProgramExampleCatalog.MAX_CATALOG_BYTES - 1, stream.available());
    }

    @Test void bothEditableDocumentsMatchTheCatalogAndEnforceThePublishedHardwareRequirements() throws Exception {
        var limits = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var open = (java.util.function.Function<ResourceLocation, java.io.InputStream>)id ->
            getClass().getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
        for (String id : List.of(USER, WIRE)) {
            var document = build(id);
            var usage = document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096, 65536, 8));
            System.out.println(id + " " + usage);
            if (id.equals(USER)) assertTrue(usage.programUnits() <= 640);
            else assertTrue(usage.programUnits() <= 256);
            long capacity = id.equals(USER) ? 640 : limits.hardware().programUnits();
            var selected = new ComputerHostLimits(new ResourceLimits(capacity, limits.hardware().memoryUnits(), limits.hardware().workPerSlice()),
                limits.archive(), limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots(), limits.isa1a(), limits.isa1b());
            PreparedProgram.prepare(document, selected);
            var asset = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples", id + ".bcp");
            var editable = EXAMPLES.resolve(id + ".bcp");
            if ("1".equals(System.getenv("GENERATE_SPEAKER_MEDIA_PROGRAM"))) {
                String encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
                Files.writeString(asset, encoded);
                Files.writeString(editable, encoded);
            } else {
                assertEquals(Files.readString(editable), Files.readString(asset));
                var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
                assertEquals(document, ProgramExampleCatalog.preview(entry, open, limits.archive()));
                for (String language : List.of("zh_cn", "en_us")) {
                    try (var json = getClass().getResourceAsStream("/assets/create_biotech/lang/" + language + ".json")) {
                        var keys = com.google.gson.JsonParser.parseString(new String(Objects.requireNonNull(json).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        assertTrue(keys.has(entry.title()));
                        entry.instructions().forEach(key -> assertTrue(keys.has(key), key));
                    }
                }
            }
        }
    }

    private static ProgramDocument build(String id) throws Exception {
        var inputs = new LinkedHashMap<String, DataType>();
        var values = new LinkedHashMap<String, DataValue>();
        var outputs = new LinkedHashMap<String, DataType>();
        if (id.equals(USER)) {
            inputs.put("speaker", POSITION); inputs.put("library", POSITION); inputs.put("track", POSITION);
            inputs.put("slot", INTEGER); inputs.put("entry", TEXT); inputs.put("play", BOOLEAN);
            inputs.put("phase_polls", INTEGER); inputs.put("phase_delay", INTEGER);
            inputs.put("play_rows", ROWS); inputs.put("stop_rows", ROWS);
            values.put("speaker", pos(0, 0, 0)); values.put("library", pos(0, 0, 0)); values.put("track", pos(1, 0, 0));
            values.put("slot", number(2)); values.put("entry", text("song")); values.put("play", new DataValue.BooleanValue(true));
            values.put("phase_polls", number(80)); values.put("phase_delay", number(4));
            values.put("play_rows", new DataValue.ListValue(List.of(
                step("status", "idle", 0, 0),
                step("seek", "accepted", 0, 1), step("result", "positioned", 1, 0), step("ack", "acknowledged", 0, 0),
                step("select", "accepted", 0, 2), step("result", "selected", 1, 0), step("ack", "acknowledged", 0, 0),
                step("read_begin", "accepted", 0, 3), step("read_ref", "read_ref", 1, 0),
                step("status", "ok", 2, 0), step("play", "accepted", 3, 0), step("status", "ok", 4, 0),
                step("read_close", "acknowledged", 0, 0))));
            values.put("stop_rows", new DataValue.ListValue(List.of(step("status", "ok", 2, 0), step("stop", "accepted", 5, 0))));
            outputs.put("stage", TEXT); outputs.put("reply", ANY); outputs.put("speaker_reply", ANY);
        } else {
            inputs.put("poll_ticks", INTEGER); inputs.put("poll_limit", INTEGER);
            values.put("poll_ticks", number(4)); values.put("poll_limit", number(128));
        }
        var module = new ModuleDefinition.Key(id, 1);
        var inputBindings = ordinaryBindings(inputs);
        var outputBindings = ordinaryBindings(outputs);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("run", module, new FlowCallBindings(inputBindings, outputBindings)),
            new FlowProgram.Procedure("implementation", module, inputs, outputs, outputBindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(id + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(values))));
        if (!outputs.isEmpty()) workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("stage", "reply", "speaker_reply")));
        return workspace.snapshot();
    }

    private static Map<String, String> ordinaryBindings(Map<String, DataType> names) {
        var map = new LinkedHashMap<String, String>();
        names.keySet().forEach(name -> map.put(name, name));
        return map;
    }
    private static DataValue.RecordValue step(String operation, String expect, int protocol, int input) {
        return new DataValue.RecordValue(Map.of("o", text(operation), "e", text(expect), "p", number(protocol), "a", number(input)));
    }
    private static DataValue.RecordValue pos(int x, int y, int z) {
        return new DataValue.RecordValue(Map.of("x", number(x), "y", number(y), "z", number(z)));
    }
    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    private static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
}
