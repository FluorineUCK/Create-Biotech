package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalUiProgramDocuments {
    private static final Path EXAMPLES = Path.of("examples/biocomputing");
    private static final String RESOURCES = "/assets/create_biotech/biocomputing/examples/";
    private static final DataValue.BooleanValue ENABLED = new DataValue.BooleanValue(true);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void publicEditableDriverFitsTheMasterClericAndMatchesItsSource() throws Exception {
        var hardware = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        verify("terminal_ui_a", true, hardware);
        verify("terminal_ui_claim", false, hardware);
    }

    private void verify(String id, boolean publish, ComputerHostLimits hardware) throws Exception {
        var document = build(id, publish);
        String resource = RESOURCES + id + ".bcp";
        var examplePath = EXAMPLES.resolve(id + ".bcp");
        PreparedProgram.prepare(document, hardware);
        var bytes = ProgramDocumentWire.encode(document, hardware.archive());
        if ("1".equals(System.getenv("GENERATE_TERMINAL_UI"))) {
            var encoded = Base64.getEncoder().encodeToString(bytes) + "\n";
            Files.writeString(Path.of("src/main/resources" + resource), encoded);
            Files.writeString(examplePath, encoded);
        } else {
            assertEquals(Files.readString(examplePath), Files.readString(Path.of("src/main/resources" + resource)));
            var open = (java.util.function.Function<ResourceLocation, java.io.InputStream>) path ->
                getClass().getResourceAsStream("/assets/" + path.getNamespace() + "/" + path.getPath());
            var example = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            assertEquals(document, ProgramExampleCatalog.preview(example, open, hardware.archive()));
        }
    }

    private static ProgramDocument build(String id, boolean publish) throws Exception {
        var inputs = new LinkedHashMap<String, DataType>();
        inputs.put("target", new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER)));
        inputs.put("execute", BOOLEAN);
        if (publish) inputs.putAll(Map.of("home_layout", ANY, "form_layout", ANY, "roots", ANY));
        var outputs = Map.<String,DataType>of("reply", ANY);
        var bindings = Map.of("reply", "reply");
        var key = new ModuleDefinition.Key(id, 1);
        var names = new LinkedHashMap<String, String>();
        for (var name : inputs.keySet()) names.put(name, name);
        var call = new FlowCallBindings(names, bindings);
        var program = new FlowProgram(inputs, List.of(new FlowProgram.Invoke("exchange", key, call),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, bindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(id + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var home = new TerminalDefinition("home", Map.of("home", new TerminalDefinition.Column(List.of("title", "open")),
            "title", new TerminalDefinition.Text(literal(text("Terminal ready"))),
            "open", new TerminalDefinition.Button(literal(text("Open form")), literal(ENABLED), "open", literal(EMPTY))));
        var form = new TerminalDefinition("form", Map.of("form", new TerminalDefinition.Column(List.of("title", "input")),
            "title", new TerminalDefinition.Text(literal(text("Submit a message"))),
            "input", new TerminalDefinition.Input(literal(text("")), literal(ENABLED), "submit", "answer", literal(EMPTY))));
        var target = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(0), "y", new DataValue.IntegerValue(0),
            "z", new DataValue.IntegerValue(0)));
        var draft = new ProgramWorkspace();
        var initial = new LinkedHashMap<String, DataValue>();
        initial.put("target", target);
        initial.put("execute", ENABLED);
        if (publish) initial.putAll(Map.of("home_layout", TerminalCodec.encode(home),
            "form_layout", TerminalCodec.encode(form), "roots", EMPTY));
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(initial))));
        draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return draft.snapshot();
    }

    private static TerminalBinding.Literal literal(DataValue value) { return new TerminalBinding.Literal(value); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
