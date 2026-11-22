package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome;
import java.util.*;

/** Test-source author only. Hardware never recognizes this example or implements its protocol. */
public final class NativeClusterSerialDocuments {
    private NativeClusterSerialDocuments() {}
    public static final DataType.RecordType OUTCOME = new DataType.RecordType(Map.of("ok", BOOLEAN, "v", ANY));
    private static final String SERIAL_REGISTERS = """
        TYPE outcome_type RECORD ok BOOLEAN v ANY
        REG outcome outcome_type
        REG received SERIAL_RECEIVE
        REG sent SERIAL_SEND
        REG serial SERIAL_STATUS
        REG detail ANY
        REG incoming ANY
        REG candidate ANY
        REG empty TEXT
        REG ready TEXT
        REG clear TEXT
        REG success BOOLEAN
        CONST empty "empty"
        CONST ready "ready"
        CONST clear ""
        """;
    private static final String RECEIVE = """
        poll:
        SERIAL.RECEIVE received s0
        FIELD success received ok
        JFALSE success receive_status
        FIELD incoming received value
        """;
    private static final String WAIT = """
        receive_status:
        MOV detail received
        FIELD candidate received status
        VALUE.EQUALS success candidate empty
        JFALSE success done
        connection:
        SERIAL.STATUS serial s0
        MOV detail serial
        FIELD candidate serial connection
        VALUE.EQUALS success candidate ready
        JFALSE success done
        FIELD candidate serial failure
        VALUE.EQUALS success candidate clear
        JFALSE success done
        SLEEP interval
        JMP poll
        done:
        RECORD.MAKE outcome ok success v detail
        """;
    private static final String GUARD_REGISTERS = """
        TYPE keys_type LIST TEXT
        REG keys keys_type
        REG key TEXT
        REG index INTEGER
        REG one INTEGER
        CONST one 1
        """;
    private static String guard(String fail) {
        return """
            LIST.LENGTH index keys
            guard:
            SUBTRACT_INTEGER index index one
            LIST.GET key keys index
            HAS.FIELD success incoming key
            JFALSE success FAIL
            LESS_INTEGER success index one
            JFALSE success guard
            FIELD candidate incoming t
            VALUE.EQUALS success candidate target
            JFALSE success FAIL
            """.replace("FAIL", fail);
    }

    public static ProgramDocument build(String role) {
        Map<String, DataType> inputs;
        DataValue.RecordValue initial;
        String source;
        switch (role) {
            case "caller" -> {
                inputs = Map.of("target", TEXT, "value", TEXT, "interval", INTEGER);
                initial = record("target", "text_length", "value", "cluster", "interval", 20);
                source = SERIAL_REGISTERS + GUARD_REGISTERS + """
                    TYPE request_type RECORD q TEXT t TEXT v TEXT
                    REG packet request_type
                    REG request TEXT
                    CONST keys ["v","r","t"]
                    CORRELATION request
                    RECORD.MAKE packet q request t target v value
                    SERIAL.SEND s0 packet sent
                    MOV detail sent
                    FIELD success sent ok
                    JFALSE success done
                    """ + RECEIVE + guard("connection") + """
                    FIELD candidate incoming r
                    VALUE.EQUALS success candidate request
                    JFALSE success connection
                    MOV detail incoming
                    JMP done
                    """ + WAIT;
            }
            case "gateway" -> {
                inputs = Map.of("interval", INTEGER); initial = record("interval", 20);
                source = SERIAL_REGISTERS + """
                    TYPE input_type RECORD v ANY
                    REG work input_type
                    REG result outcome_type
                    JMP connection
                    """ + RECEIVE + """
                    RECORD.MAKE work v incoming
                    XBUS.WRITE x0 work
                    XBUS.READ result x0
                    FIELD success result ok
                    FIELD detail result v
                    JFALSE success ignored
                    SERIAL.SEND s0 detail sent
                    MOV detail sent
                    FIELD success sent ok
                    JMP done
                    ignored:
                    CONST success true
                    JMP done
                    """ + WAIT;
            }
            case "worker" -> {
                inputs = Map.of("target", TEXT, "interval", INTEGER);
                initial = record("target", "text_length", "interval", 20);
                source = GUARD_REGISTERS + """
                    TYPE input_type RECORD v ANY
                    TYPE reply_type RECORD r ANY t TEXT v INTEGER
                    TYPE outcome_type RECORD ok BOOLEAN v ANY
                    REG work input_type
                    REG outcome outcome_type
                    REG reply reply_type
                    REG incoming ANY
                    REG detail ANY
                    REG candidate ANY
                    REG request ANY
                    REG value ANY
                    REG length INTEGER
                    REG success BOOLEAN
                    CONST keys ["v","q","t"]
                    CONST detail null
                    XBUS.READ work x0
                    FIELD incoming work v
                    """ + guard("done") + """
                    FIELD request incoming q
                    FIELD value incoming v
                    TEXT.LENGTH length value
                    RECORD.MAKE reply r request t target v length
                    MOV detail reply
                    done:
                    RECORD.MAKE outcome ok success v detail
                    XBUS.WRITE x0 outcome
                    """;
            }
            default -> throw new IllegalArgumentException(role);
        }
        boolean worker = role.equals("worker"), caller = role.equals("caller");
        var outputs = worker ? Map.<String, DataType>of() : Map.<String, DataType>of("o", OUTCOME);
        var returns = worker ? Map.<String, String>of() : Map.of("o", "outcome");
        var key = new ModuleDefinition.Key("i", 1);
        var bindings = new LinkedHashMap<String, String>();
        inputs.keySet().forEach(name -> bindings.put(name, name));
        var steps = new ArrayList<FlowProgram.Step>();
        var inline = new LinkedHashMap<String, ComputationGraph>();
        if (caller) steps.add(new FlowProgram.Invoke("request", key, new FlowCallBindings(bindings, returns)));
        else {
            if (worker) {
                steps.add(new FlowProgram.Forever("service"));
                steps.add(new FlowProgram.XBusStatus("sender", "x0", "status"));
                steps.add(new FlowProgram.If("sender_gate", new FlowInput.Expression("sender_present", "value", Map.of("status", "status"))));
                inline.put("sender_gate", new ComputationGraph(Map.of("status", INTEGER), Map.of("status", new ComputationGraph.Input("status"),
                    "busy", new ComputationGraph.Constant(new DataValue.IntegerValue(15)),
                    "present", new ComputationGraph.Scalar(ScalarOperation.EQUAL_INTEGER, Map.of("left", "status", "right", "busy"))), Map.of("value", "present")));
            } else {
                steps.add(new FlowProgram.Evaluate("ready", "ready", new FlowCallBindings(Map.of(), Map.of("failed", "failed"))));
                steps.add(new FlowProgram.Until("service", "failed"));
                inline.put("ready", new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.BooleanValue(false))), Map.of("failed", "value")));
            }
            steps.add(new FlowProgram.TaskStart("transaction", key, bindings, "task"));
            steps.add(new FlowProgram.TaskJoin("collect", key, "task", "result"));
            steps.add(new FlowProgram.Evaluate("require_result", "require_result", new FlowCallBindings(Map.of("result", "result"), worker ? Map.of() : Map.of("outcome", "outcome", "failed", "failed"))));
            var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
            nodes.put("result", new ComputationGraph.Input("result")); nodes.put("value", new ComputationGraph.Field("result", "value"));
            nodes.put("required", new ComputationGraph.Scalar(ScalarOperation.REQUIRE_VALUE, Map.of("value", "value")));
            if (!worker) {
                nodes.put("outcome", new ComputationGraph.Field("required", "o"));
                nodes.put("ok", new ComputationGraph.Field("outcome", "ok"));
                nodes.put("failed", new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", "ok")));
            }
            inline.put("require_result", new ComputationGraph(Map.of("result", TaskOutcome.type(outputs)), nodes, worker ? Map.of("value", "required") : Map.of("outcome", "outcome", "failed", "failed")));
            if (worker) {
                steps.add(new FlowProgram.EndIf("sender_end")); steps.add(new FlowProgram.Sleep("yield", "interval"));
                steps.add(new FlowProgram.EndForever("service_end"));
            } else steps.add(new FlowProgram.EndUntil("service_end"));
        }
        steps.add(new FlowProgram.Procedure("implementation", key, inputs, outputs, returns, FlowBlockRules.NONE, Optional.of(new AssemblySource(source))));
        steps.add(new FlowProgram.EndProcedure("implementation_end"));
        var body = FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial);
        body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(), inline, body.execution());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body));
        if (!worker) workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("outcome")));
        return workspace.snapshot();
    }
}
