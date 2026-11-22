package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Only authors ordinary editable resources. No application Java is included in the mod. */
public final class ReaderOutputDocuments {
    private ReaderOutputDocuments() {}
    public static ProgramDocument build(String role) {
        if (role.equals("b")) return RedstoneDriverDocuments.build("b");
        if (role.equals("d")) return calculation();
        var original = role.equals("a") ? ReaderDriverDocuments.build("a") : RedstoneDriverDocuments.build(role.equals("e") ? "a" : role);
        var body = original.flow().orElseThrow(); var old = body.program(original.archive());
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure p) {
                String source = p.assembly().orElseThrow().text();
                var inputs = new LinkedHashMap<>(p.inputs());
                var outputs = p.outputs(); var returns = p.returns();
                if (role.equals("a")) {
                    source = "TYPE sample_message RECORD r ANY\nREG forwarded sample_message\n" + source;
                    source += "RECORD.MAKE forwarded r reply\nXBUS.WRITE x1 forwarded\n";
                }
                else if (role.equals("c")) source = replace(source, "CONST kind \"redstone_reply\"", """
                    FIELD right packet kind
                    CONST kind "reader_command"
                    VALUE.EQUALS flag right kind
                    JFALSE flag output_kind
                    CONST kind "reader_reply"
                    JMP compare_kind
                    output_kind:
                    CONST kind "redstone_command"
                    VALUE.EQUALS flag right kind
                    JFALSE flag done
                    CONST kind "redstone_reply"
                    compare_kind:
                    """);
                else if (role.equals("e")) {
                    inputs.clear();
                    outputs = Map.of("reply", ANY); returns = Map.of("reply", "reply");
                    source = replace(source, "TYPE setting_type RECORD device ANY session ANY revision ANY level INTEGER", "TYPE maybe OPTIONAL INTEGER\nTYPE setting_type RECORD device ANY session ANY revision ANY level maybe");
                    source = replace(source, "REG packet packet_type", "TYPE level_message RECORD v maybe\nREG message level_message\nREG level maybe\nREG packet packet_type");
                    // A visible application configuration constant, not an automatically resolved target.
                    // Avoid staging a single-use configuration argument through the call frame.
                    source = replace(source, "REG packet packet_type", "REG target target_type\nREG packet packet_type");
                    source = replace(source, "CONST version 1", "CONST target {\"x\":0,\"y\":0,\"z\":0}\nCONST version 1");
                    source = replace(source, "CONST kind \"redstone_command\"", """
                        CONST reply "invalid_sample"
                        XBUS.READ message x1
                        FIELD level message v
                        IS_NULL setting level
                        JFALSE setting valid_sample
                        JMP done
                        valid_sample:
                        CONST kind "redstone_command"
                        """);
                    // IS_NULL already established setting=false. Publish the final reply verbatim,
                    // including a business refusal; only the first status reply can cause a set.
                    source = replace(source, "CONST setting false\n", "");
                    source = replace(source, "FIELD code reply code", "JFALSE setting check_status\nJMP done\ncheck_status:\nFIELD code reply code");
                    source = replace(source, "JFALSE setting prepare_set\nJMP done\nprepare_set:\n", "");
                    source = replace(source, "CONST expected \"applied\"\n", "");
                } else throw new IllegalArgumentException(role);
                steps.add(new FlowProgram.Procedure(p.sourceId(), p.key(), inputs, outputs, returns, p.rules(), Optional.of(new AssemblySource(source))));
            } else if (role.equals("e") && step instanceof FlowProgram.Invoke invoke) {
                steps.add(new FlowProgram.Invoke(invoke.sourceId(), invoke.procedure(), new FlowCallBindings(Map.of(), Map.of("reply", "reply"))));
            } else steps.add(step);
        }
        var inputs = new LinkedHashMap<>(old.inputs()); var initial = new LinkedHashMap<>(body.initial().fields());
        if (role.equals("e")) { inputs.clear(); initial.clear(); }
        var next = FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), new DataValue.RecordValue(initial));
        var workspace = new ProgramWorkspace(original);
        workspace.setFlow(Optional.of(new FlowDocumentBody(next.inputs(), next.steps(), next.initial(), next.canvas(), body.inline(), next.execution())));
        if (role.equals("e")) workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return workspace.snapshot();
    }
    private static ProgramDocument calculation() {
        var maybe = new DataType.OptionalType(INTEGER);
        var inputs = Map.<String, DataType>of("interval", INTEGER);
        var outputs = Map.<String, DataType>of("sample", ANY, "level", maybe, "ok", BOOLEAN);
        var key = new ModuleDefinition.Key("invert_signal", 1);
        var source = new AssemblySource("""
            TYPE maybe OPTIONAL INTEGER
            TYPE position RECORD x INTEGER y INTEGER z INTEGER
            TYPE redstone RECORD face TEXT signal INTEGER direct INTEGER comparator ANY
            TYPE components RECORD create_biotech:redstone redstone
            TYPE snapshot RECORD game_time INTEGER target position components components
            TYPE reply_type RECORD kind TEXT version INTEGER target position request TEXT code TEXT state ANY value snapshot
            TYPE maybe_reply OPTIONAL reply_type
            TYPE checked_type RECORD ok BOOLEAN value maybe_reply
            TYPE sample_message RECORD r ANY
            TYPE level_message RECORD v maybe
            REG incoming sample_message
            REG outgoing level_message
            REG sample ANY
            REG reply reply_type
            REG data snapshot
            REG values components
            REG component redstone
            REG level maybe
            REG selected maybe_reply
            REG checked checked_type
            REG signal INTEGER
            REG maximum INTEGER
            REG zero INTEGER
            REG ok BOOLEAN
            REG invalid BOOLEAN
            REG code TEXT
            REG expected TEXT
            CONST level null
            CONST ok false
            CONST maximum 15
            await_sample:
            SLEEP interval
            XBUS.STATUS signal x0
            EQUAL_INTEGER ok signal maximum
            JFALSE ok await_sample
            XBUS.READ incoming x0
            FIELD sample incoming r
            VALUE.CHECK checked sample
            FIELD ok checked ok
            JFALSE ok done
            FIELD selected checked value
            REQUIRE_VALUE reply selected
            FIELD code reply code
            CONST expected "ok"
            VALUE.EQUALS ok code expected
            JFALSE ok done
            FIELD data reply value
            FIELD values data components
            FIELD component values create_biotech:redstone
            FIELD signal component signal
            CONST zero 0
            CONST ok false
            LESS_INTEGER invalid signal zero
            JFALSE invalid upper
            JMP done
            upper:
            LESS_INTEGER invalid maximum signal
            JFALSE invalid compute
            JMP done
            compute:
            SUBTRACT_INTEGER signal maximum signal
            MOV level signal
            CONST ok true
            done:
            RECORD.MAKE outgoing v level
            XBUS.WRITE x1 outgoing
            """);
        var steps = List.<FlowProgram.Step>of(
            new FlowProgram.Invoke("invert_sample", key, new FlowCallBindings(Map.of("interval", "interval"), Map.of("sample", "sample", "level", "level", "ok", "ok"))),
            new FlowProgram.Procedure("invert_definition", key, inputs, outputs, Map.of("sample", "sample", "level", "level", "ok", "ok"), FlowBlockRules.NONE, Optional.of(source)),
            new FlowProgram.EndProcedure("invert_end"));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), record("interval", 5))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("ok", "level", "sample")));
        return workspace.snapshot();
    }
    private static String replace(String source, String anchor, String text) {
        if (source.indexOf(anchor) < 0 || source.indexOf(anchor) != source.lastIndexOf(anchor)) throw new IllegalStateException("Review edited driver: " + anchor);
        return source.replace(anchor, text);
    }
    @org.junit.jupiter.api.Test void publishedApplicationMatchesEditableSources() throws Exception {
        for (String role : List.of("a", "b", "c", "d", "e")) {
            var document = build(role);
            System.out.println("READER_OUTPUT " + role + " " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096, 65536, 8)));
            document.validate(ReaderDriverDocumentTest.HARDWARE);
            if (!role.equals("b") && "1".equals(System.getenv("GENERATE_READER_OUTPUT"))) {
                var bytes = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, ReaderDriverDocumentTest.ARCHIVE)) + "\n";
                for (String dir : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing"))
                    java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "reader_output_" + role + ".bcp"), bytes);
            }
            if (!"1".equals(System.getenv("GENERATE_READER_OUTPUT"))) org.junit.jupiter.api.Assertions.assertEquals(document, ReaderOutputDocumentTest.load(role));
        }
    }
}
