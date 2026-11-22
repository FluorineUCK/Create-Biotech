package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome;
import java.util.*;

/** Maintainer-only source authoring; no runtime-specific service execution path. */
public final class NativeLibraryServiceDocuments {
    private NativeLibraryServiceDocuments() {}

    public static ProgramDocument build(String role) {
        if (!Set.of("b", "c", "d").contains(role)) throw new IllegalArgumentException(role);
        var original = role.equals("d") ? NativeLibraryWriteDocuments.build(role) : NativeLibraryReadDocuments.build(role);
        var originalBody = original.flow().orElseThrow();
        var old = originalBody.program(original.archive());
        var implementation = old.steps().stream().filter(FlowProgram.Procedure.class::isInstance)
            .map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow();
        var receive = old.steps().stream().filter(FlowProgram.Read.class::isInstance)
            .map(FlowProgram.Read.class::cast).findFirst().orElseThrow();
        var send = old.steps().stream().filter(FlowProgram.Write.class::isInstance)
            .map(FlowProgram.Write.class::cast).findFirst().orElseThrow();
        var arguments = new LinkedHashMap<>(implementation.inputs());
        arguments.remove(receive.local());
        var bindings = new LinkedHashMap<String, String>();
        arguments.keySet().forEach(name -> bindings.put(name, name));
        // Current ISA charges the fixed local procedure identifier in each task instruction.
        // This short, visible name is local to this document, not a hidden hardware capability.
        var key = new ModuleDefinition.Key("i", 1);
        String source = implementation.assembly().orElseThrow().text();
        source = switch (role) {
            case "b" -> replace(source, "REG outcome outcome_type", "REG packet packet_type\nREG outcome outcome_type");
            case "c" -> replace(source, "REG packet packet_type", "TYPE context_type RECORD p packet_type r ANY\nREG context context_type\nREG packet packet_type");
            case "d" -> replace(source, "REG row row_type", "REG context preparation_type\nREG row row_type");
            default -> throw new IllegalArgumentException(role);
        };
        String first = switch (role) {
            case "b" -> "SERIAL.SEND s0 packet sent";
            case "c" -> "FIELD packet context p";
            default -> "FIELD row context r";
        };
        source = replace(source, first, "XBUS.READ " + receive.local() + " " + receive.port() + "\n" + first);
        if (role.equals("b")) {
            // The successful match is already the success boolean; every non-match remains false.
            // Keep all original failure results, yields and typed correlation checks.
            source = replace(source, "REG matched BOOLEAN\n", "");
            source = replace(source, "FIELD matched match m", "FIELD success match m");
            source = replace(source, "JFALSE matched connection", "JFALSE success connection");
            source = replace(source, "MOV success matched\n", "");
            source = replace(source, "SLEEP interval\nJMP done", "JMP wire_failure");
            source = replace(source, "CONST success false\n", "");
            source = replace(source, "FIELD flag sent ok\nJFALSE flag done", "FIELD success sent ok\nJFALSE success done");
            source = replace(source, "FIELD got received ok\nJFALSE got receive_status", "FIELD success received ok\nJFALSE success receive_status");
            source = replace(source, "REG got BOOLEAN\n", "");
            // Keep each existing yield, but let connection checks fall through to RECEIVE
            // and a matched reply fall through to the final yield rather than jumping there.
            String connection = source.substring(source.indexOf("receive_status:\n"), source.indexOf("wire_failure:\n"));
            source = replace(source, connection, "");
            source = replace(source, "JMP wire_failure\n", "");
            source = replace(source, "poll:\n", "JMP poll\n" + replace(connection, "JMP poll\n", "") + "poll:\n");
            source = source.replace("wire_failure", "settle");
        } else if (role.equals("c")) {
            // Every early exit already has flag=false. Reuse it as the final match result.
            source = replace(source, "REG matched BOOLEAN\n", "");
            source = replace(source, "CONST matched false\n", "");
            source = replace(source, "VALUE.EQUALS matched left right", "VALUE.EQUALS flag left right");
            source = replace(source, "RECORD.MAKE outcome m matched", "RECORD.MAKE outcome m flag");
            // Count down from length to zero; reverse the literal to retain the same check order.
            source = replace(source, "REG size INTEGER\n", "");
            source = replace(source, "CONST keys [\"kind\",\"target\",\"request\",\"code\",\"sequence\",\"value\"]\nCONST index 0",
                "CONST keys [\"value\",\"sequence\",\"code\",\"request\",\"target\",\"kind\"]");
            source = replace(source, "LIST.LENGTH size keys\nguard:", "LIST.LENGTH index keys\nguard:\nSUBTRACT_INTEGER index index one");
            source = replace(source, "ADD_INTEGER index index one\nLESS_INTEGER flag index size\nJFALSE flag compare\nJMP guard",
                "LESS_INTEGER flag index one\nJFALSE flag guard");
        }
        source += "XBUS.WRITE " + send.port() + " " + send.local() + "\n";
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure) break;
            if (step instanceof FlowProgram.Read) {
                steps.add(new FlowProgram.TaskStart("start_service", key, bindings, "service_task"));
                steps.add(new FlowProgram.TaskJoin("join_service", key, "service_task", "service_result"));
                // Explicitly require the successful task result. Discard its empty value, not
                // the full service_result: a failed child stays inspectable when this faults.
                steps.add(new FlowProgram.Evaluate("require_service_result", "successful_service",
                    new FlowCallBindings(Map.of("result", "service_result"), Map.of())));
            } else if (!(step instanceof FlowProgram.Invoke) && !(step instanceof FlowProgram.Write)) steps.add(step);
        }
        // The reply has already crossed XBus; do not return a redundant local payload copy.
        steps.add(new FlowProgram.Procedure("implementation", key, arguments, Map.of(), Map.of(),
            implementation.rules(), Optional.of(new AssemblySource(source))));
        steps.add(new FlowProgram.EndProcedure("implementation_end"));
        var body = FlowDocumentBody.fromProgram(new FlowProgram(old.inputs(), steps).withAssembly(), originalBody.initial());
        var inline = new LinkedHashMap<>(originalBody.inline());
        inline.put("require_service_result", new ComputationGraph(Map.of("result", TaskOutcome.type(Map.of())), Map.of(
            "result", new ComputationGraph.Input("result"), "value", new ComputationGraph.Field("result", "value"),
            "required", new ComputationGraph.Scalar(ScalarOperation.REQUIRE_VALUE, Map.of("value", "value"))), Map.of("value", "required")));
        body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(), inline, body.execution());
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body));
        return workspace.snapshot();
    }

    private static String replace(String source, String anchor, String replacement) {
        int at = source.indexOf(anchor);
        if (at < 0 || source.indexOf(anchor, at + anchor.length()) >= 0)
            throw new IllegalStateException("Review changed public driver authoring: " + anchor);
        return source.substring(0, at) + replacement + source.substring(at + anchor.length());
    }
}
