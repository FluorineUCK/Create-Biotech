package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Maintainer-only authoring of player-editable ISA procedures; never a runtime dispatch API. */
public final class ProductionDriverDocuments {
    private ProductionDriverDocuments() {}
    private static final String SUBMIT = TextDriverDocuments.PACKET_TYPE + """
        TYPE outcome_type RECORD ok BOOLEAN value ANY
        TYPE items_type LIST ANY
        TYPE data_type RECORD items items_type
        TYPE submit_type RECORD device ANY session ANY order TEXT data data_type
        REG packet packet_type
        REG outcome outcome_type
        REG kind TEXT
        REG version INTEGER
        REG request TEXT
        REG operation TEXT
        REG arguments ANY
        REG data data_type
        REG submission submit_type
        REG reply ANY
        REG code ANY
        REG state ANY
        REG device ANY
        REG session ANY
        REG expected TEXT
        REG flag BOOLEAN
        REG submitting BOOLEAN
        CONST kind "production_command"
        CONST version 1
        CONST operation "status"
        CONST arguments {}
        CONST expected "ok"
        CONST submitting false
        CORRELATION request
        send:
        RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
        XBUS.WRITE x0 packet
        XBUS.READ outcome x0
        FIELD flag outcome ok
        FIELD reply outcome value
        JFALSE flag done
        JFALSE submitting prepare
        JMP done
        prepare:
        FIELD code reply code
        VALUE.EQUALS flag code expected
        JFALSE flag done
        FIELD state reply state
        FIELD device state device
        FIELD session state session
        RECORD.MAKE data items items
        RECORD.MAKE submission device device session session order job data data
        MOV arguments submission
        CORRELATION request
        CONST operation "submit"
        CONST submitting true
        JMP send
        done:
        """;
    private static final String POLL = TextDriverDocuments.PACKET_TYPE + """
        TYPE outcome_type RECORD ok BOOLEAN value ANY
        TYPE order_type RECORD order TEXT
        REG packet packet_type
        REG outcome outcome_type
        REG kind TEXT
        REG version INTEGER
        REG request TEXT
        REG operation TEXT
        REG arguments order_type
        REG reply ANY
        CONST kind "production_command"
        CONST version 1
        CONST operation "order"
        CORRELATION request
        RECORD.MAKE arguments order job
        RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
        XBUS.WRITE x0 packet
        XBUS.READ outcome x0
        FIELD reply outcome value
        """;

    /** a: one status + one stable-job submit; poll: one read-only query; c: ordinary reply matcher. */
    public static ProgramDocument build(String role) {
        if (role.equals("c")) return matcher();
        if (!Set.of("a", "poll").contains(role)) throw new IllegalArgumentException(role);
        var inputs = new LinkedHashMap<String, DataType>();
        inputs.put("target", TARGET); inputs.put("job", TEXT);
        var initial = new LinkedHashMap<String, DataValue>();
        initial.put("target", record("x", 0, "y", 0, "z", 0));
        initial.put("job", new DataValue.TextValue("production-example-1"));
        if (role.equals("a")) {
            inputs.put("items", new DataType.ListType(ANY));
            initial.put("items", new DataValue.ListValue(List.of(record("item", "minecraft:stone", "count", 1))));
        }
        var bindings = new LinkedHashMap<String, String>();
        inputs.keySet().forEach(name -> bindings.put(name, name));
        var key = new ModuleDefinition.Key("i", 1);
        var outputs = Map.<String, DataType>of("reply", ANY);
        var steps = List.<FlowProgram.Step>of(
            new FlowProgram.Invoke("request", key, new FlowCallBindings(bindings, Map.of("reply", "reply"))),
            new FlowProgram.Procedure("request_definition", key, inputs, outputs, Map.of("reply", "reply"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(role.equals("a") ? SUBMIT : POLL))),
            new FlowProgram.EndProcedure("request_end"));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), new DataValue.RecordValue(initial))));
        // Display the actual reply (including code/phase/diagnostic), never a locally invented completion boolean.
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return workspace.snapshot();
    }

    public static ProgramDocument stockMaterial() throws Exception {
        var target = record("x", 0, "y", 0, "z", 0);
        var initial = record("target", target, "job", "material-example-1",
            "items", new DataValue.ListValue(List.of(record("item", "minecraft:stone", "count", 4))));
        var types = Map.<String, DataType>of("target", TARGET, "job", TEXT, "items", new DataType.ListType(ANY));
        var args = new LinkedHashMap<>(types);
        args.put("action", TEXT);
        var key = new ModuleDefinition.Key("material_request", 1);
        var source = Files.readString(Path.of("examples/biocomputing/stock_material_a.asm")).stripTrailing() + "\n";
        var program = new FlowProgram(types, List.of(new FlowProgram.Procedure("material", key, args,
            Map.of("reply", ANY), Map.of("reply", "reply"), FlowBlockRules.NONE, Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("material_end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, initial)));
        var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
        nodes.put("root", new TerminalDefinition.Column(List.of("hint", "input", "tasks")));
        nodes.put("hint", new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(
            "Type submit to request the configured materials; order to query the same job. Claim a finished task before sending another."))));
        nodes.put("input", new TerminalDefinition.Input(new TerminalBinding.Literal(new DataValue.TextValue("")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)), new TerminalTarget.Task(key), "action", path("output")));
        nodes.put("tasks", new TerminalDefinition.Repeat(path("tasks"), "task"));
        nodes.put("task", new TerminalDefinition.Column(List.of("phase", "result", "failure")));
        for (String field : List.of("phase", "result", "failure")) nodes.put(field, new TerminalDefinition.Text(path("row", field)));
        workspace.setTerminal(Optional.of(new TerminalDefinition("root", nodes)));
        return workspace.snapshot();
    }

    private static TerminalBinding.Path path(String root, String... fields) {
        var path = new ValuePath(root, List.of());
        for (String field : fields) path = path.key(field);
        return new TerminalBinding.Path(path);
    }

    @Test void stockMaterialTerminalFitsExistingHardwareAndMatchesPublicBytes() throws Exception {
        var document = stockMaterial();
        System.out.println("STOCK_MATERIAL_AUTHOR " + document.flow().orElseThrow().program(document.archive())
            .usage(new ResourceLimits(4096, 65536, 8)));
        document.validate(RedstoneDriverDocumentTest.HARDWARE);
        var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, RedstoneDriverDocumentTest.ARCHIVE)) + "\n";
        for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing")) {
            var path = Path.of(directory, "stock_material_a.bcp");
            if ("1".equals(System.getenv("GENERATE_STOCK_MATERIAL"))) Files.writeString(path, encoded);
            var published = Files.readString(path);
            assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(published.strip()), RedstoneDriverDocumentTest.ARCHIVE));
        }
    }

    private static ProgramDocument matcher() {
        var original = TextDriverDocuments.build("c");
        var body = original.flow().orElseThrow();
        var program = body.program(original.archive());
        var steps = new ArrayList<FlowProgram.Step>();
        boolean replaced = false;
        for (var step : program.steps()) {
            if (step instanceof FlowProgram.Procedure procedure) {
                var assembly = procedure.assembly().orElseThrow();
                if (!assembly.text().contains("\"text_output_reply\"")) throw new IllegalStateException("Review the public reply matcher");
                steps.add(new FlowProgram.Procedure(procedure.sourceId(), procedure.key(), procedure.inputs(), procedure.outputs(),
                    procedure.returns(), procedure.rules(), Optional.of(new AssemblySource(assembly.version(),
                        assembly.text().replace("\"text_output_reply\"", "\"production_reply\"")))));
                replaced = true;
            } else steps.add(step);
        }
        if (!replaced) throw new IllegalStateException("Missing public matcher procedure");
        var next = FlowDocumentBody.fromProgram(new FlowProgram(program.inputs(), steps).withAssembly(), body.initial());
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(next.inputs(), next.steps(), next.initial(), next.canvas(), body.inline(), next.execution())));
        return workspace.snapshot();
    }

    @Test void publishedBytesMatchEditableSourceAndDocumentationCopy() throws Exception {
        for (String role : List.of("a", "poll", "c")) {
            String name = switch (role) { case "a" -> "production_submit_a"; case "poll" -> "production_poll_a"; default -> "production_c"; };
            var document = build(role);
            System.out.println("PRODUCTION_AUTHOR " + name + " " + document.flow().orElseThrow().program(document.archive())
                .usage(new ResourceLimits(4096, 65536, 8)));
            document.validate(RedstoneDriverDocumentTest.HARDWARE);
            String expected = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, RedstoneDriverDocumentTest.ARCHIVE)) + "\n";
            String published = null;
            for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing")) {
                var path = Path.of(directory, name + ".bcp");
                if ("1".equals(System.getenv("GENERATE_PRODUCTION_DRIVER"))) Files.writeString(path, expected);
                String actual = Files.readString(path);
                // Public document equality is exact; wire map iteration order is not canonical across JVMs.
                assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(actual.strip()),
                    RedstoneDriverDocumentTest.ARCHIVE), "Published document must match the editable author: " + path);
                if (published == null) published = actual;
                else assertEquals(published, actual, "Documentation must contain exactly the same published bytes");
            }
        }
    }
}
