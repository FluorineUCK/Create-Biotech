package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;

/** Resource authoring only. No sampling application executor is packaged in the mod. */
public final class ReaderDriverDocuments {
    private ReaderDriverDocuments() {}
    private static final String SAMPLE = RedstoneDriverDocuments.PACKET_TYPE + """
        TYPE component_list LIST TEXT
        TYPE outcome_type RECORD ok BOOLEAN value ANY
        TYPE sample_type RECORD device ANY session ANY components component_list
        REG packet packet_type
        REG outcome outcome_type
        REG kind TEXT
        REG version INTEGER
        REG request TEXT
        REG operation TEXT
        REG arguments ANY
        REG sample_arguments sample_type
        REG expected TEXT
        REG reply ANY
        REG code ANY
        REG state ANY
        REG device ANY
        REG session ANY
        REG flag BOOLEAN
        REG sampled BOOLEAN
        CONST kind "reader_command"
        CONST version 1
        CONST operation "status"
        CONST arguments {}
        CONST expected "ok"
        CONST sampled false
        send:
        CORRELATION request
        RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
        XBUS.WRITE x0 packet
        XBUS.READ outcome x0
        FIELD flag outcome ok
        FIELD reply outcome value
        JFALSE flag done
        FIELD code reply code
        VALUE.EQUALS flag code expected
        JFALSE flag done
        JFALSE sampled prepare_sample
        JMP done
        prepare_sample:
        FIELD state reply state
        FIELD device state device
        FIELD session state session
        RECORD.MAKE sample_arguments device device session session components components
        MOV arguments sample_arguments
        CONST operation "sample"
        CONST sampled true
        JMP send
        done:
        """;
    public static ProgramDocument build(String role) {
        if (role.equals("b")) return RedstoneDriverDocuments.build("b");
        if (role.equals("a")) {
            var key = new ModuleDefinition.Key("sample", 1);
            var inputs = Map.<String, DataType>of("target", TARGET, "components", new DataType.ListType(TEXT));
            var outputs = Map.<String, DataType>of("ok", BOOLEAN, "reply", ANY);
            var steps = List.<FlowProgram.Step>of(
                new FlowProgram.Invoke("sample_once", key, new FlowCallBindings(Map.of("target", "target", "components", "components"), Map.of("ok", "ok", "reply", "reply"))),
                new FlowProgram.Procedure("sample_definition", key, inputs, outputs, Map.of("ok", "flag", "reply", "reply"), FlowBlockRules.NONE, Optional.of(new AssemblySource(SAMPLE))),
                new FlowProgram.EndProcedure("sample_end"));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(),
                record("target", record("x", 0, "y", 0, "z", 0), "components", new DataValue.ListValue(List.of(new DataValue.TextValue("create_biotech:redstone")))))));
            workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("ok", "reply")));
            return workspace.snapshot();
        }
        if (!role.equals("c")) throw new IllegalArgumentException(role);
        var original = RedstoneDriverDocuments.build("c"); var body = original.flow().orElseThrow();
        var old = body.program(original.archive()); var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure procedure) {
                var source = procedure.assembly().orElseThrow();
                if (!source.text().contains("\"redstone_reply\"")) throw new IllegalStateException("Review public matcher source");
                steps.add(new FlowProgram.Procedure(procedure.sourceId(), procedure.key(), procedure.inputs(), procedure.outputs(), procedure.returns(), procedure.rules(),
                    Optional.of(new AssemblySource(source.version(), source.text().replace("\"redstone_reply\"", "\"reader_reply\"")))));
            } else steps.add(step);
        }
        var next = FlowDocumentBody.fromProgram(new FlowProgram(old.inputs(), steps).withAssembly(), body.initial());
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(next.inputs(), next.steps(), next.initial(), next.canvas(), body.inline(), next.execution())));
        return workspace.snapshot();
    }
    @org.junit.jupiter.api.Test void publishedBytesMatchEditableSourcesAndReuseTheExistingTransport() throws Exception {
        for (String role : List.of("a", "b", "c")) {
            var document = build(role);
            System.out.println("READER_AUTHOR " + role + " " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096, 65536, 8)));
            document.validate(ReaderDriverDocumentTest.HARDWARE);
            if (!role.equals("b") && "1".equals(System.getenv("GENERATE_READER"))) {
                var bytes = ProgramDocumentWire.encode(document, ReaderDriverDocumentTest.ARCHIVE);
                for (String dir : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing"))
                    java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "reader_sample_" + role + ".bcp"), Base64.getEncoder().encodeToString(bytes) + "\n");
            }
            String file = role.equals("b") ? "redstone_output_b" : "reader_sample_" + role;
            var actual = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/" + file + ".bcp")).strip();
            org.junit.jupiter.api.Assertions.assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(actual), ReaderDriverDocumentTest.ARCHIVE));
        }
    }
}
