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

public class ComponentSettingsProgramDocuments {
    private static final DataType TARGET = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    private static final String ID = "component_settings_a";
    static ProgramDocument build() throws Exception {
        return build(ID, "component_settings", Map.of("target", TARGET, "setting", TEXT, "value", ANY, "apply", BOOLEAN), """
            {"target":{"x":0,"y":0,"z":0},"setting":"delay","value":4,"apply":false}
            """);
    }
    private static ProgramDocument build(String id, String module, Map<String, DataType> inputs, String initial) throws Exception {
        var outputs = Map.<String, DataType>of("reply", ANY);
        var bindings = Map.of("reply", "reply");
        var key = new ModuleDefinition.Key(module, 1);
        var arguments = new java.util.LinkedHashMap<String, String>();
        inputs.keySet().forEach(name -> arguments.put(name, name));
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("apply", key, new FlowCallBindings(arguments, bindings)),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, bindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/" + id + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        var values = (DataValue.RecordValue)EditorValueFormat.parse(EditorValueFormat.Kind.JSON, initial);
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, values)));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return workspace.snapshot();
    }

    @Test void editableDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        verifyPublished(ID, build());
    }
    @Test void editableMotionDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        var id = "sequenced_motion_a";
        verifyPublished(id, build(id, "sequenced_motion",
            Map.of("target", TARGET, "mode", TEXT, "amount", INTEGER, "speed", INTEGER, "apply", BOOLEAN), """
                {"target":{"x":0,"y":0,"z":0},"mode":"angle","amount":90,"speed":1,"apply":false}
                """));
    }
    @Test void editableArmStatusDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        var id = "arm_status_a";
        verifyPublished(id, build(id, "arm_status", Map.of("target", TARGET), """
            {"target":{"x":0,"y":0,"z":0}}
            """));
    }
    @Test void editableArmPointsNbtDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        var id = "arm_points_nbt_a";
        verifyPublished(id, build(id, "arm_points_nbt", Map.of("target", TARGET), """
            {"target":{"x":0,"y":0,"z":0}}
            """));
    }
    @Test void editableArmMoveDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        var id = "arm_move_a";
        verifyPublished(id, build(id, "arm_move",
            Map.of("target", TARGET, "input", INTEGER, "output", INTEGER, "apply", BOOLEAN), """
                {"target":{"x":0,"y":0,"z":0},"input":0,"output":0,"apply":false}
                """));
    }
    @Test void editableDeployerDriverFitsMasterClericAndMatchesPublishedSource() throws Exception {
        verifyPublished("deployer_process_a", build("deployer_process_a", "deployer_process", Map.of("target", TARGET, "apply", BOOLEAN), """
            {"target":{"x":0,"y":0,"z":0},"apply":false}
            """));
    }
    private static void verifyPublished(String id, ProgramDocument document) throws Exception {
        var limits = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        PreparedProgram.prepare(document, limits);
        var asset = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/" + id + ".bcp");
        var doc = Path.of("examples/biocomputing/" + id + ".bcp");
        if ("1".equals(System.getenv("GENERATE_COMPONENT_SETTINGS"))) {
            var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
            Files.writeString(asset, encoded);
            Files.writeString(doc, encoded);
        } else {
            assertEquals(Files.readString(doc), Files.readString(asset));
            java.util.function.Function<ResourceLocation, java.io.InputStream> open = resource ->
                ComponentSettingsProgramDocuments.class.getResourceAsStream("/assets/" + resource.getNamespace() + "/" + resource.getPath());
            var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            assertEquals(document, ProgramExampleCatalog.preview(entry, open, limits.archive()));
        }
    }
}
