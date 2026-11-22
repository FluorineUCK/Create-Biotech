package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;

/** Maintainer-only four-owner authoring; runtime loads ordinary public archives, never this class. */
public final class NativeLibraryWriteDocuments {
    private NativeLibraryWriteDocuments() {}
    public static final DataType.RecordType PREPARATION = new DataType.RecordType(Map.of("r", ROW, "t", TARGET, "s", ANY, "v", ANY, "w", ANY));
    public static final String PREPARATION_TYPE = "TYPE row_type RECORD o TEXT d ANY e TEXT l BOOLEAN\nTYPE preparation_type RECORD r row_type t target_type s ANY v ANY w ANY\n";
    // Retained payload changes only after an expected successful result, never on ACK/busy.
    public static final String A = coordinatorSource();
    private static String coordinatorSource() {
        String source = NativeLibraryReadDocuments.A;
        source = replaceOnce(source, "REG packet packet_type", "REG packet packet_type\nREG context preparation_type");
        source = replaceOnce(source, "CONST read \"read\"", "CONST read \"result\"");
        source = replaceOnce(source, "CORRELATION request\nRECORD.MAKE packet kind kind target target request request operation op sequence seq data data",
            "RECORD.MAKE context r row t target s seq v read_result w replacement\nXBUS.WRITE x1 context\nXBUS.READ packet x1");
        source = replaceOnce(source, "VALUE.EQUALS flag code read", "VALUE.EQUALS flag op read");
        return replaceOnce(source, "fail:\nMOV error reply", "fail:\nMOV error reply\nCONST read_result null");
    }
    private static String replaceOnce(String source, String anchor, String replacement) {
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0)
            throw new IllegalStateException("READ authoring changed; review write candidate anchor: " + anchor);
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }
    public static final String D = PACKET_TYPE + PREPARATION_TYPE + """
        TYPE mutation_type RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
        REG row row_type
        REG target target_type
        REG seq ANY
        REG retained ANY
        REG op TEXT
        REG data ANY
        REG write TEXT
        REG flag BOOLEAN
        REG expected ANY
        REG replacement ANY
        REG mutation mutation_type
        REG put TEXT
        REG entry_kind TEXT
        REG kind TEXT
        REG request TEXT
        REG packet packet_type
        FIELD row context r
        FIELD target context t
        FIELD seq context s
        FIELD op row o
        FIELD data row d
        CONST write "write"
        VALUE.EQUALS flag op write
        JFALSE flag packet
        FIELD retained context v
        FIELD expected retained version
        FIELD replacement context w
        CONST put "put"
        CONST entry_kind "DATA"
        RECORD.MAKE mutation op put volume volume entry entry expected expected kind entry_kind value replacement
        MOV data mutation
        packet:
        CONST kind "library_command"
        CORRELATION request
        RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
        """;

    public static ProgramDocument build(String role) {
        if (!Set.of("a", "d").contains(role)) throw new IllegalArgumentException(role);
        var key = new ModuleDefinition.Key("library_write_" + role, 1);
        Map<String, DataType> inputs, arguments, outputs;
        Map<String, String> returns;
        DataValue.RecordValue initial;
        String source;
        var steps = new ArrayList<FlowProgram.Step>();
        if (role.equals("a")) {
            inputs = Map.of("target", TARGET, "commands", ROWS, "replacement", ANY);
            arguments = inputs;
            outputs = Map.of("success", BOOLEAN, "read_result", ANY, "error", ANY);
            returns = Map.of("success", "success", "read_result", "read_result", "error", "error");
            initial = record("target", record("x", 0, "y", 0, "z", 0), "replacement", 44, "commands", new DataValue.ListValue(List.of(
                row("status", null, "idle", false),
                row("seek", record("x", 1, "y", 0, "z", 0), "accepted", false),
                row("result", null, "positioned", false), row("ack", null, "acknowledged", false),
                row("select", 2, "accepted", false), row("result", null, "selected", false), row("ack", null, "acknowledged", false),
                row("stat", "reading", "accepted", false), row("result", null, "stat", false), row("ack", null, "acknowledged", false),
                row("write", null, "accepted", false), row("result", null, "written", false), row("ack", null, "acknowledged", false),
                row("read", "reading", "accepted", false), row("result", null, "read", false), row("ack", null, "acknowledged", true))));
            source = PACKET_TYPE + PREPARATION_TYPE + A.substring(PACKET_TYPE.length());
            steps.add(new FlowProgram.Invoke("query", key, new FlowCallBindings(Map.of("target", "target", "commands", "commands", "replacement", "replacement"), returns)));
        } else {
            // Explicit local inputs, not names resolved from A or shared cluster storage.
            inputs = Map.of("interval", INTEGER, "volume", TEXT, "entry", TEXT);
            arguments = Map.of("context", PREPARATION, "volume", TEXT, "entry", TEXT);
            outputs = Map.of("packet", PACKET);
            returns = Map.of("packet", "packet");
            initial = record("interval", 20, "volume", "plant", "entry", "reading");
            source = D;
            steps.add(new FlowProgram.Forever("service"));
            steps.add(new FlowProgram.XBusStatus("sender_status", "x0", "bus_status"));
            steps.add(new FlowProgram.If("sender_gate", new FlowInput.Expression("sender_present", "value", Map.of("status", "bus_status"))));
            steps.add(new FlowProgram.Read("context", "x0", "context", PREPARATION));
            steps.add(new FlowProgram.Invoke("prepare", key, new FlowCallBindings(Map.of("context", "context", "volume", "volume", "entry", "entry"), returns)));
            steps.add(new FlowProgram.Write("response", "x0", "packet"));
            steps.add(new FlowProgram.EndIf("sender_gate_end"));
            steps.add(new FlowProgram.Sleep("service_yield", "interval"));
            steps.add(new FlowProgram.EndForever("service_end"));
        }
        steps.add(new FlowProgram.Procedure("implementation", key, arguments, outputs, returns, FlowBlockRules.NONE, Optional.of(new AssemblySource(source))));
        steps.add(new FlowProgram.EndProcedure("implementation_end"));
        var body = FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial);
        if (role.equals("d")) {
            var guard = new ComputationGraph(Map.of("status", INTEGER), Map.of(
                "status", new ComputationGraph.Input("status"),
                "sender", new ComputationGraph.Constant(new DataValue.IntegerValue(15)),
                "present", new ComputationGraph.Scalar(ScalarOperation.EQUAL_INTEGER, Map.of("left", "status", "right", "sender"))), Map.of("value", "present"));
            body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(), Map.of("sender_gate", guard), body.execution());
        }
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body));
        if(role.equals("a")) workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("success","read_result","error")));
        return workspace.snapshot();
    }
}
