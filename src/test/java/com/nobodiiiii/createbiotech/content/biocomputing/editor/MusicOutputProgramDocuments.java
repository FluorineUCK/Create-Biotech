package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

public class MusicOutputProgramDocuments {
    private static final DataType TARGET = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    static ProgramDocument build() throws Exception {
        var inputs = Map.of("target", TARGET, "instrument", (DataType)TEXT, "note", (DataType)INTEGER, "play", (DataType)BOOLEAN);
        var outputs = Map.<String,DataType>of("reply", ANY);
        var bindings = Map.of("reply", "reply");
        var key = new ModuleDefinition.Key("note", 1);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("play", key, new FlowCallBindings(Map.of("target", "target", "instrument", "instrument", "note", "note", "play", "play"), bindings)),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, bindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/music_output_a.asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var draft = new ProgramWorkspace();
        var target = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(0), "y", new DataValue.IntegerValue(0), "z", new DataValue.IntegerValue(0)));
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(Map.of(
            "target", target, "instrument", new DataValue.TextValue("flute"), "note", new DataValue.IntegerValue(19), "play", new DataValue.BooleanValue(true))))));
        draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return draft.snapshot();
    }

    @Test void editableMusicDocumentFitsExistingMasterClericAndMatchesPublishedSource() throws Exception {
        var limits = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var document = build();
        System.out.println("MUSIC_OUTPUT_A " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096, 65536, 8)));
        PreparedProgram.prepare(document, limits);
        var asset = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/music_output_a.bcp");
        var doc = Path.of("examples/biocomputing/music_output_a.bcp");
        if ("1".equals(System.getenv("GENERATE_MUSIC_OUTPUT"))) {
            var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
            Files.writeString(asset, encoded);
            Files.writeString(doc, encoded);
        } else {
            assertEquals(Files.readString(doc), Files.readString(asset));
            java.util.function.Function<ResourceLocation, java.io.InputStream> open = id ->
                getClass().getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
            var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals("music_output_a")).findFirst().orElseThrow();
            assertEquals(document, ProgramExampleCatalog.preview(entry, open, limits.archive()));
            for (String language : List.of("zh_cn", "en_us")) {
                try (var input = getClass().getResourceAsStream("/assets/create_biotech/lang/" + language + ".json")) {
                    var keys = com.google.gson.JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    assertTrue(keys.has(entry.title()));
                    entry.instructions().forEach(keyName -> assertTrue(keys.has(keyName), keyName));
                }
            }
        }
    }
}
