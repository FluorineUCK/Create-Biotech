package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authoring fixture only. Runtime consumes complete editable public ISA documents. */
public class MaterialSelectionDocuments {
    public static final DataType.RecordType CHOICE = new DataType.RecordType(Map.of("slot", DataType.Scalar.INTEGER,
        "item", DataType.Scalar.TEXT, "tags", new DataType.ListType(DataType.Scalar.TEXT)));
    public static final DataType.RecordType MATERIAL = new DataType.RecordType(Map.of(
        "item", new DataType.OptionalType(DataType.Scalar.TEXT), "tag", new DataType.OptionalType(DataType.Scalar.TEXT),
        "count", DataType.Scalar.INTEGER, "components", DataType.Scalar.NBT));
    public static final DataType.RecordType EXACT = new DataType.RecordType(Map.of("item", DataType.Scalar.TEXT,
        "count", DataType.Scalar.INTEGER, "components", DataType.Scalar.NBT));
    public static final DataType.RecordType RESULT = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN,
        "code", DataType.Scalar.TEXT, "items", new DataType.OptionalType(new DataType.ListType(EXACT))));
    public static String source(String role) throws Exception {
        try (var in = MaterialSelectionDocuments.class.getResourceAsStream(
            "/assets/create_biotech/biocomputing/examples/material_selection_" + role + ".asm")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public static ProgramDocument build(String role) throws Exception {
        boolean service = role.equals("service");
        var inputs = new LinkedHashMap<String, DataType>();
        inputs.put("poll_ticks", DataType.Scalar.INTEGER);
        if (!service) {
            inputs.put("materials", new DataType.ListType(MATERIAL));
            inputs.put("choices", new DataType.ListType(new DataType.OptionalType(CHOICE)));
        }
        var outputs = service ? Map.<String, DataType>of() : Map.<String, DataType>of("result", RESULT);
        var bindings = service ? Map.<String, String>of() : Map.of("result", "result");
        var args = new LinkedHashMap<String, String>(); inputs.keySet().forEach(n -> args.put(n, n));
        var key = new ModuleDefinition.Key("material_selection_" + role, 1);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("select", key, new FlowCallBindings(args, bindings)),
            new FlowProgram.Procedure("implementation", key, inputs, outputs, bindings, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(source(role)))), new FlowProgram.EndProcedure("end"))).withAssembly();
        var initial = new LinkedHashMap<String, DataValue>(); initial.put("poll_ticks", new DataValue.IntegerValue(20));
        if (!service) { initial.put("materials", new DataValue.ListValue(List.of())); initial.put("choices", new DataValue.ListValue(List.of())); }
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(initial))));
        var base = NativeLibraryResultLayouts.fields("result"); var nodes = new LinkedHashMap<>(base.nodes());
        nodes.put("selection_label", new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(service
            ? "SELECTION SERVICE | x0 <-> coordinator x0 | start first; pause explicitly after use | c=code,i=optional exact; editable data, not stock/authority"
            : "SELECTION ONLY | explicit per-row choices | no stock reservation or dispatch | pending is NOT success; faults remain visible"))));
        nodes.put("selection_root", new TerminalDefinition.Column(List.of("selection_label", base.root())));
        workspace.setTerminal(Optional.of(new TerminalDefinition("selection_root", nodes)));
        return workspace.snapshot();
    }
    public static ProgramDocument published(String role) throws Exception {
        try (var in = MaterialSelectionDocuments.class.getResourceAsStream(
            "/assets/create_biotech/biocomputing/examples/material_selection_" + role + ".bcp")) {
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()), FoundationRecipeTest.LIMITS);
        }
    }
    @Test void completePublicCopiesMatchAndFitDefaultHardware() throws Exception {
        for (String role : List.of("service", "coordinator")) {
            var document = build(role); document.validate(new ResourceLimits(256, 65536, 1));
            byte[] bytes = ProgramDocumentWire.encode(document, FoundationRecipeTest.LIMITS);
            String name = "material_selection_" + role;
            for (String root : List.of("src/main/resources/assets/create_biotech/biocomputing/examples/", "examples/biocomputing/")) {
                var path = Path.of(root + name + ".bcp");
                if ("1".equals(System.getenv("MATERIAL_SELECTION_WRITE"))) Files.writeString(path, Base64.getEncoder().encodeToString(bytes) + "\n");
                else assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(path).strip()), FoundationRecipeTest.LIMITS));
            }
            assertEquals(source(role), Files.readString(Path.of("examples/biocomputing/" + name + ".asm")));
            System.out.println("SELECTION_DOCUMENT role=" + role + " words=" + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(256, 65536, 1)).programUnits());
        }
    }
}
