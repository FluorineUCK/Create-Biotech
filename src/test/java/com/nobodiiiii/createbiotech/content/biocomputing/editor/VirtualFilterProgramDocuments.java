package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class VirtualFilterProgramDocuments {
    private static final DataType TARGET = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    private static final DataType RULE = new DataType.RecordType(Map.of("domain", TEXT, "mode", TEXT, "matchers", ANY));
    static ProgramDocument build(boolean list) throws Exception {
        var inputs = new java.util.LinkedHashMap<>(Map.of("target", TARGET, "rule", RULE, "apply", (DataType) BOOLEAN));
        if (list) inputs.put("slot", INTEGER);
        var outputs = Map.<String, DataType>of("reply", ANY);
        var bindings = Map.of("reply", "reply");
        var key = new ModuleDefinition.Key(list ? "virtual_list_filter" : "virtual_filter", 1);
        var arguments = new java.util.LinkedHashMap<String, String>();
        inputs.keySet().forEach(name -> arguments.put(name, name));
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("apply", key, new FlowCallBindings(arguments, bindings)),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, bindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/" + id(list) + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        var values = (DataValue.RecordValue)EditorValueFormat.parse(EditorValueFormat.Kind.JSON, """
            {"target":{"x":0,"y":0,"z":0},"rule":{"domain":"item","mode":"allow",
             "matchers":[{"kind":"id","name":"minecraft:iron_ingot"}]},"apply":true}
            """);
        var initial = new java.util.LinkedHashMap<>(values.fields());
        if (list) initial.put("slot", new DataValue.IntegerValue(1));
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(initial))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return workspace.snapshot();
    }

    private static String id(boolean list) { return list ? "virtual_filter_list_a" : "virtual_filter_a"; }
    @Test void editableDirectFilterProgramFitsExistingHardwareAndMatchesItsSource() throws Exception { verify(false); }
    @Test void editableListFilterProgramFitsExistingHardwareAndMatchesItsSource() throws Exception { verify(true); }
    private void verify(boolean list) throws Exception {
        var limits = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var document = build(list);
        PreparedProgram.prepare(document, limits);
        var asset = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/" + id(list) + ".bcp");
        var doc = Path.of("examples/biocomputing/" + id(list) + ".bcp");
        if ("1".equals(System.getenv("GENERATE_VIRTUAL_FILTER"))) {
            var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
            Files.writeString(asset, encoded);
            Files.writeString(doc, encoded);
        } else {
            assertEquals(Files.readString(doc), Files.readString(asset));
            java.util.function.Function<ResourceLocation, java.io.InputStream> open = id ->
                getClass().getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
            var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id(list))).findFirst().orElseThrow();
            assertEquals(document, ProgramExampleCatalog.preview(entry, open, limits.archive()));
        }
    }
}
