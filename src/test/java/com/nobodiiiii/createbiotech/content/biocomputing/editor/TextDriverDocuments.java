package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;

/** Maintainer authoring only. Runtime installs the same public documents that players can edit. */
public final class TextDriverDocuments {
    private TextDriverDocuments() {}
    public static final String PACKET_TYPE = """
        TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
        TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
        """;
    private static final String OUTPUT = PACKET_TYPE + """
        TYPE outcome_type RECORD ok BOOLEAN value ANY
        TYPE line_list LIST TEXT
        TYPE setting_type RECORD device ANY session ANY revision ANY lines line_list
        REG packet packet_type
        REG outcome outcome_type
        REG kind TEXT
        REG version INTEGER
        REG request TEXT
        REG operation TEXT
        REG arguments ANY
        REG setting_arguments setting_type
        REG expected TEXT
        REG reply ANY
        REG code ANY
        REG state ANY
        REG device ANY
        REG session ANY
        REG revision ANY
        REG flag BOOLEAN
        REG setting BOOLEAN
        CONST kind "text_output_command"
        CONST version 1
        CONST operation "status"
        CONST arguments {}
        CONST expected "ok"
        CONST setting false
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
        JFALSE setting prepare_set
        JMP done
        prepare_set:
        FIELD state reply state
        FIELD device state device
        FIELD session state session
        FIELD revision state revision
        RECORD.MAKE setting_arguments device device session session revision revision lines lines
        MOV arguments setting_arguments
        CONST operation "replace"
        CONST expected "accepted"
        CONST setting true
        JMP send
        done:
        """;

    public static ProgramDocument build(String role) {
        if (role.equals("a")) {
            var key = new ModuleDefinition.Key("output", 1);
            var inputs = Map.<String, DataType>of("target", TARGET, "lines", new DataType.ListType(TEXT));
            var outputs = Map.<String, DataType>of("ok", BOOLEAN, "reply", ANY);
            var returns = Map.of("ok", "flag", "reply", "reply");
            var steps = List.<FlowProgram.Step>of(
                new FlowProgram.Invoke("apply_output", key, new FlowCallBindings(Map.of("target", "target", "lines", "lines"), Map.of("ok", "ok", "reply", "reply"))),
                new FlowProgram.Procedure("output_definition", key, inputs, outputs, returns, FlowBlockRules.NONE, Optional.of(new AssemblySource(OUTPUT))),
                new FlowProgram.EndProcedure("output_end"));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(),
                record("target", record("x", 0, "y", 0, "z", 0), "lines", new DataValue.ListValue(List.of(new DataValue.TextValue("READY")))))));
            workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("ok", "reply")));
            return workspace.snapshot();
        }
        if (!Set.of("b", "c").contains(role)) throw new IllegalArgumentException(role);
        // Reuse the existing public bounded serial service / matcher scaffolding, not Library hardware.
        var original = NativeLibraryServiceDocuments.build(role);
        var body = original.flow().orElseThrow();
        var old = body.program(original.archive());
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure procedure) {
                String source = procedure.assembly().orElseThrow().text();
                source = source.replace(NativeLibraryReadDocuments.PACKET_TYPE, PACKET_TYPE);
                if (role.equals("c")) {
                    source = source.replace("\"library_reply\"", "\"text_output_reply\"")
                        .replace("[\"value\",\"sequence\",\"code\",\"request\",\"target\",\"kind\"]",
                            "[\"version\",\"code\",\"request\",\"target\",\"kind\"]");
                    String request = "FIELD left reply request";
                    if (!source.contains(request)) throw new IllegalStateException("Review the public matcher source");
                    source = source.replace(request, """
                        FIELD left reply version
                        FIELD right packet version
                        VALUE.EQUALS flag left right
                        JFALSE flag done
                        """ + request);
                }
                steps.add(new FlowProgram.Procedure(procedure.sourceId(), procedure.key(), procedure.inputs(), procedure.outputs(),
                    procedure.returns(), procedure.rules(), Optional.of(new AssemblySource(source))));
            } else steps.add(step);
        }
        var next = FlowDocumentBody.fromProgram(new FlowProgram(old.inputs(), steps).withAssembly(), body.initial());
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(next.inputs(), next.steps(), next.initial(), next.canvas(), body.inline(), next.execution())));
        return workspace.snapshot();
    }

    @org.junit.jupiter.api.Test void publishedBytesMatchEditableSource() throws Exception {
        for (String role : List.of("a", "c")) {
            var document = build(role);
            System.out.println("TEXT_OUTPUT_AUTHOR " + role + " " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096, 65536, 8)));
            document.validate(RedstoneDriverDocumentTest.HARDWARE);
            var bytes = ProgramDocumentWire.encode(document, RedstoneDriverDocumentTest.ARCHIVE);
            if ("1".equals(System.getenv("GENERATE_TEXT_OUTPUT"))) {
                for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing"))
                    java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "text_output_" + role + ".bcp"), Base64.getEncoder().encodeToString(bytes) + "\n");
            }
            var actual = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/create_biotech/biocomputing/examples/text_output_" + role + ".bcp")).strip();
            org.junit.jupiter.api.Assertions.assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(actual), RedstoneDriverDocumentTest.ARCHIVE));
        }
    }
}
