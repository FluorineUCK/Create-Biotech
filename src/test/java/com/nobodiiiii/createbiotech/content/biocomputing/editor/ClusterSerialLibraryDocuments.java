package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.LibraryReadDriverDocument.*;

/** Authors public documents only. The game does not load this builder or orchestrate the application in Java. */
public final class ClusterSerialLibraryDocuments {
    private static final DataType.RecordType READING = new DataType.RecordType(Map.of("reading", ANY));
    private final Map<String, ProgramDefinition.Entry> entries = new LinkedHashMap<>();
    private final Map<ModuleDefinition.Key, ModuleDefinition> modules = new LinkedHashMap<>();
    private final Map<String, DataValue> initial = new LinkedHashMap<>();
    private final Map<String, DataType> types = new LinkedHashMap<>();
    private final List<FlowProgram.Step> steps = new ArrayList<>();

    private void entry(String name, Graph graph, Map<String, String> outputs) {
        entries.put(name, new ProgramDefinition.Entry(graph.finish(outputs), List.of(), List.of()));
    }
    private void local(String name, DataType type, DataValue value) { types.put(name, type); initial.put(name, value); }
    private ProgramDocument finish() {
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), entries), modules);
        var workspace = new ProgramWorkspace(ProgramDocument.of(archive));
        workspace.setFlow(Optional.of(new FlowDocumentBody(types, steps, new DataValue.RecordValue(initial))));
        return workspace.snapshot();
    }
    private void base(ProgramDocument document) {
        modules.putAll(document.archive().modules()); entries.putAll(document.archive().program().entries());
        var flow = document.flow().orElseThrow(); initial.putAll(flow.initial().fields()); types.putAll(flow.inputs());
    }
    private void waitFrame(String prefix, String match) {
        steps.addAll(List.of(new FlowProgram.Until(prefix + "_wait", "finished"),
            new FlowProgram.SerialReceive(prefix + "_receive", "s0", "received"), new FlowProgram.Evaluate(prefix + "_got", "received"),
            new FlowProgram.If(prefix + "_has_frame", "got"), new FlowProgram.Evaluate(prefix + "_match", match),
            new FlowProgram.EndIf(prefix + "_end_frame"), new FlowProgram.SerialStatus(prefix + "_status", "s0", "serial"),
            new FlowProgram.Evaluate(prefix + "_io", "io"), new FlowProgram.Sleep(prefix + "_sleep", "interval"),
            new FlowProgram.EndUntil(prefix + "_end_wait")));
    }
    private void matcher(String name, String kind, boolean response) {
        var inputs = new TreeMap<String, DataType>(Map.of("received", SerialResult.RECEIVE, "service", TEXT));
        if (response) inputs.put("client_request", TEXT);
        var guard = new Graph(inputs); String body = guard.field(guard.input("received"), "value"); String valid = null;
        for (String field : response ? List.of("kind", "target", "request", "ok", "value", "error") : List.of("kind", "target", "request", "reading")) {
            String present = guard.node(new ComputationGraph.HasField(body, guard.constant(field)));
            valid = valid == null ? present : guard.and(valid, present);
        }
        var yes = new Graph(inputs); String value = yes.field(yes.input("received"), "value");
        String matched = yes.and(yes.equals(yes.dynamic(value, "kind"), yes.constant(kind)),
            yes.equals(yes.dynamic(value, "target"), yes.input("service")));
        if (response) matched = yes.and(matched, yes.equals(yes.dynamic(value, "request"), yes.input("client_request")));
        var no = new Graph(inputs);
        var yesKey = new ModuleDefinition.Key("cluster_serial_library." + name + "_fields", 1);
        var noKey = new ModuleDefinition.Key("cluster_serial_library." + name + "_unrelated", 1);
        modules.put(yesKey, new ModuleDefinition(yesKey, yes.finish(Map.of("matched", matched))));
        modules.put(noKey, new ModuleDefinition(noKey, no.finish(Map.of("matched", no.constant(false)))));
        var args = new LinkedHashMap<String, String>(); inputs.keySet().forEach(n -> args.put(n, guard.input(n)));
        String branch = guard.node(new ComputationGraph.Branch(valid, yesKey, noKey, args));
        entry(name, guard, Map.of("matched", guard.field(branch, "matched")));
    }
    private void handoff(String local, DataType type, String name) {
        var graph = new Graph(Map.of(local, type)); String value = graph.input(local);
        entry(name, graph, Map.of("compute_ok", graph.field(value, "ok"), "compute_error", graph.field(value, "error")));
    }

    static ProgramDocument worker() {
        var b = new ClusterSerialLibraryDocuments();
        var g = new Graph(Map.of("input", READING));
        String reading = g.scalar(ScalarOperation.MULTIPLY_INTEGER, g.field(g.input("input"), "reading"), g.constant(2));
        b.entry("calculate", g, Map.of("output", g.node(new ComputationGraph.MakeRecord(Map.of("reading", reading)))));
        b.steps.addAll(List.of(new FlowProgram.Read("receive", "x0", "input", READING),
            new FlowProgram.Evaluate("double_reading", "calculate"), new FlowProgram.Write("reply", "x0", "output")));
        return b.finish();
    }

    static ProgramDocument coordinator() {
        var b = new ClusterSerialLibraryDocuments(); var driver = LibraryReadDriverDocument.writeDocument(); b.base(driver);
        // Inline these visible call wrappers, not their device behavior. Fixed reusable pure dependencies remain ordinary modules.
        for (String name : List.of("prepare", "match", "advance")) {
            var key = new ModuleDefinition.Key("library_write." + name, 1);
            b.entries.put(name, new ProgramDefinition.Entry(b.modules.remove(key).graph(), List.of(), List.of()));
        }
        b.local("service", TEXT, text("journal")); b.local("client_request", ANY, DataValue.NullValue.INSTANCE);
        b.local("reply_sent", SerialResult.SEND, SerialResult.send("not_attempted"));
        b.matcher("incoming", "cluster_log", false);
        var capture = new Graph(Map.of("received", SerialResult.RECEIVE)); String body = capture.field(capture.input("received"), "value");
        b.entry("capture", capture, Map.of("client_request", capture.dynamic(body, "request"),
            "input", capture.node(new ComputationGraph.MakeRecord(Map.of("reading", capture.dynamic(body, "reading"))))));
        b.handoff("handoff", XBusResult.type(), "sent_compute");
        b.handoff("computed", XBusResult.readType(READING), "received_compute");
        var argumentFields = new LinkedHashMap<>(((DataType.RecordType)DataType.typeOf(b.initial.get("arguments"))).fields());
        argumentFields.put("value", ANY);
        var argumentType = new DataType.RecordType(argumentFields); b.types.put("arguments", argumentType);
        var args = new Graph(Map.of("arguments", argumentType, "computed", XBusResult.readType(READING)));
        var fields = new LinkedHashMap<String, String>();
        for (String field : List.of("track", "slot", "entry", "volume", "kind", "none")) fields.put(field, args.field(args.input("arguments"), field));
        fields.put("value", args.dynamic(args.dynamic(args.input("computed"), "value"), "reading"));
        b.entry("write_arguments", args, Map.of("arguments", args.node(new ComputationGraph.MakeRecord(fields))));
        var reply = new Graph(Map.of("service", TEXT, "client_request", ANY, "success", BOOLEAN, "read_result", ANY,
            "error", ANY, "compute_ok", BOOLEAN, "compute_error", new DataType.OptionalType(ERROR)));
        b.entry("external_reply", reply, Map.of("reply", reply.node(new ComputationGraph.MakeRecord(Map.of(
            "kind", reply.constant("cluster_log_result"), "target", reply.input("service"), "request", reply.input("client_request"),
            "ok", reply.input("success"), "value", reply.input("read_result"),
            "error", reply.select(reply.input("compute_ok"), reply.input("error"), reply.input("compute_error")))))));
        b.waitFrame("external", "incoming"); b.steps.add(new FlowProgram.If("external_matched", "matched"));
        b.steps.addAll(List.of(new FlowProgram.Evaluate("capture_request", "capture"),
            new FlowProgram.Write("compute", "x0", "input", Optional.of("handoff")), new FlowProgram.Evaluate("check_compute_send", "sent_compute"),
            new FlowProgram.If("compute_sent", "compute_ok"), new FlowProgram.Read("computed", "x0", "computed", READING, true),
            new FlowProgram.Evaluate("check_compute_reply", "received_compute"), new FlowProgram.If("compute_received", "compute_ok"),
            new FlowProgram.Evaluate("set_value", "write_arguments")));
        b.steps.addAll(driver.flow().orElseThrow().steps());
        b.steps.addAll(List.of(new FlowProgram.EndIf("end_compute_received"), new FlowProgram.EndIf("end_compute_sent"),
            new FlowProgram.Evaluate("reply_record", "external_reply"), new FlowProgram.SerialSend("reply_once", "s0", "reply", "reply_sent"),
            new FlowProgram.EndIf("end_external")));
        return b.finish();
    }

    static ProgramDocument caller() {
        var b = new ClusterSerialLibraryDocuments(); b.base(LibraryReadDriverDocument.document());
        b.entries.keySet().retainAll(Set.of("sent", "received", "io", "send_error", "io_error")); b.modules.clear();
        b.initial.keySet().retainAll(Set.of("done", "success", "matched", "finished", "send_ok", "got", "error", "received", "serial", "interval"));
        b.types.keySet().retainAll(b.initial.keySet());
        b.local("service", TEXT, text("journal")); b.local("reading", INTEGER, integer(21)); b.local("client_request", TEXT, text(""));
        b.local("reply", ANY, DataValue.NullValue.INSTANCE);
        b.local("value", ANY, DataValue.NullValue.INSTANCE);
        b.matcher("response", "cluster_log_result", true);
        var send = new Graph(Map.of("service", TEXT, "reading", INTEGER, "client_request", TEXT));
        b.entry("outgoing", send, Map.of("outgoing", send.node(new ComputationGraph.MakeRecord(Map.of(
            "kind", send.constant("cluster_log"), "target", send.input("service"), "request", send.input("client_request"), "reading", send.input("reading"))))));
        var result = new Graph(Map.of("received", SerialResult.RECEIVE));
        String reply = result.field(result.input("received"), "value");
        String ok = result.and(result.dynamic(reply, "ok"), result.constant(true));
        // Ordinary editable lazy subgraphs: a failed operation's earlier stat is not a saved result.
        var valueKey = new ModuleDefinition.Key("cluster_serial_library.saved_value", 1);
        var emptyKey = new ModuleDefinition.Key("cluster_serial_library.no_saved_value", 1);
        var saved = new Graph(Map.of("reply", ANY));
        b.modules.put(valueKey, new ModuleDefinition(valueKey, saved.finish(Map.of("value", saved.dynamic(saved.dynamic(saved.input("reply"), "value"), "value")))));
        var empty = new Graph(saved.inputs);
        b.modules.put(emptyKey, new ModuleDefinition(emptyKey, empty.finish(Map.of("value", empty.constant(DataValue.NullValue.INSTANCE)))));
        String value = result.node(new ComputationGraph.Branch(ok, valueKey, emptyKey, Map.of("reply", reply)));
        String error = result.dynamic(reply, "error");
        var codeKey = new ModuleDefinition.Key("cluster_serial_library.error_code", 1);
        var rawKey = new ModuleDefinition.Key("cluster_serial_library.error_value", 1);
        var code = new Graph(Map.of("error", ANY));
        b.modules.put(codeKey, new ModuleDefinition(codeKey, code.finish(Map.of("error", code.dynamic(code.input("error"), "code")))));
        var raw = new Graph(code.inputs);
        b.modules.put(rawKey, new ModuleDefinition(rawKey, raw.finish(Map.of("error", raw.input("error")))));
        String scalarError = result.or(result.node(new ComputationGraph.Scalar(ScalarOperation.IS_ERROR, Map.of("value", error))),
            result.node(new ComputationGraph.Scalar(ScalarOperation.IS_NULL, Map.of("value", error))));
        String diagnostic = result.node(new ComputationGraph.Branch(scalarError, rawKey, codeKey, Map.of("error", error)));
        b.entry("result", result, Map.of("reply", reply, "done", result.constant(true),
            "success", ok, "value", result.field(value, "value"), "error", result.field(diagnostic, "error")));
        b.steps.addAll(List.of(new FlowProgram.Correlation("correlate", "client_request"), new FlowProgram.Evaluate("request_record", "outgoing"),
            new FlowProgram.SerialSend("send_once", "s0", "outgoing", "sent"), new FlowProgram.Evaluate("check_send", "sent"),
            new FlowProgram.If("sent", "send_ok")));
        b.waitFrame("external", "response");
        b.steps.addAll(List.of(new FlowProgram.If("matched", "matched"), new FlowProgram.Evaluate("save_result", "result"),
            new FlowProgram.Otherwise("failed_io"), new FlowProgram.Evaluate("io_error", "io_error"), new FlowProgram.EndIf("end_match"),
            new FlowProgram.Otherwise("failed_send"), new FlowProgram.Evaluate("send_error", "send_error"), new FlowProgram.EndIf("end_send")));
        var document = b.finish();
        var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
        nodes.put("root", new TerminalDefinition.Column(List.of("title", "success_row", "value_row", "reply_row", "error_row")));
        nodes.put("title", new TerminalDefinition.Text(new TerminalBinding.Literal(text("Cluster -> Library"))));
        for (String field : List.of("success", "value", "reply", "error")) {
            nodes.put(field + "_row", new TerminalDefinition.Row(List.of(field + "_label", field)));
            nodes.put(field + "_label", new TerminalDefinition.Text(new TerminalBinding.Literal(text(field))));
            var path = new ValuePath("output", List.of()).key(field);
            nodes.put(field, new TerminalDefinition.Text(new TerminalBinding.Path(path)));
        }
        return new ProgramDocument(document.archive(), document.positions(), Optional.of(new TerminalDefinition("root", nodes)), document.flow());
    }

    public static void main(String[] arguments) throws Exception {
        var root = Path.of(arguments[0]);
        for (String role : List.of("worker", "coordinator", "caller")) {
            var document = switch (role) { case "worker" -> worker(); case "coordinator" -> coordinator(); default -> caller(); };
            var diagnosticLimits = new ResourceLimits(1000, 65536, 8);
            System.out.println(role + " program units: " + (document.archive().compile(diagnosticLimits).usage().programUnits() + document.flow().orElseThrow().steps().size()));
            document.validate(role.equals("worker") ? new ResourceLimits(32, 32768, 4) : new ResourceLimits(256, 65536, 8));
            String encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, LibraryReadDriverTest.ARCHIVE)) + "\n";
            String filename = "cluster_serial_library_" + role + ".bcp";
            Files.writeString(root.resolve("src/main/resources/assets/create_biotech/biocomputing/examples/" + filename), encoded);
            Files.writeString(root.resolve("examples/biocomputing/" + filename), encoded);
        }
    }
}
