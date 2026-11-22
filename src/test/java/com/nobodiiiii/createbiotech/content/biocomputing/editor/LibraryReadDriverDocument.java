package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;

/** Maintainer-only authoring of ordinary graphs. Nothing here is loaded by the game or executes device work. */
public final class LibraryReadDriverDocument {
    static final DataType.RecordType TARGET = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    static final DataType.RecordType COMMAND = new DataType.RecordType(Map.of("operation", TEXT, "argument", TEXT, "expected", TEXT, "last", BOOLEAN));
    static final DataType.ListType COMMANDS = new DataType.ListType(COMMAND);
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("kind", TEXT, "target", TARGET, "request", TEXT, "operation", TEXT, "sequence", ANY, "data", ANY));
    private final Map<ModuleDefinition.Key, ModuleDefinition> modules = new LinkedHashMap<>();
    private final Map<String, ProgramDefinition.Entry> entries = new LinkedHashMap<>();
    private final String query;
    private LibraryReadDriverDocument(String query) { this.query = query; }
    private ModuleDefinition.Key key(String name) { return new ModuleDefinition.Key("library_" + query + "." + name, 1); }
    private void entry(String name, Graph graph, Map<String, String> outputs) {
        var body = graph.finish(outputs);
        if (!Set.of("prepare", "match", "advance").contains(name)) {
            entries.put(name, new ProgramDefinition.Entry(body, List.of(), List.of())); return;
        }
        modules.put(key(name), new ModuleDefinition(key(name), body));
        var call = new Graph(body.inputs()); var args = new LinkedHashMap<String, String>();
        body.inputs().keySet().forEach(input -> args.put(input, call.input(input)));
        String result = call.node(new ComputationGraph.Call(key(name), args));
        var exported = new LinkedHashMap<String, String>(); new TreeSet<>(outputs.keySet()).forEach(out -> exported.put(out, call.field(result, out)));
        entries.put(name, new ProgramDefinition.Entry(call.finish(exported), List.of(), List.of()));
    }
    public static ProgramDocument document() { return new LibraryReadDriverDocument("read").build(); }
    public static ProgramDocument statDocument() { return new LibraryReadDriverDocument("stat").build(); }
    public static ProgramDocument writeDocument() { return new LibraryReadDriverDocument("write").build(); }
    private ProgramDocument build() {
        boolean mutationDriver = query.equals("write") || query.equals("delete");
        boolean failureRecovery = query.equals("failure_recovery");
        boolean recoveryDriver = query.equals("write_recovery") || query.equals("query_recovery") || failureRecovery;
        var prepareInputs = new LinkedHashMap<>(Map.of("commands", COMMANDS, "index", INTEGER, "arguments", ANY, "target", TARGET, "request", TEXT, "sequence", ANY));
        if (mutationDriver) prepareInputs.put("read_result", ANY);
        var prepare = new Graph(prepareInputs);
        String command = prepare.node(new ComputationGraph.DynamicIndex(prepare.input("commands"), prepare.input("index")));
        String data;
        if (mutationDriver) {
            var ordinary = new Graph(Map.of("arguments", ANY, "command", COMMAND, "read_result", ANY));
            String argument = ordinary.node(new ComputationGraph.DynamicField(ordinary.input("arguments"), ordinary.field(ordinary.input("command"), "argument")));
            modules.put(key("ordinary_data"), new ModuleDefinition(key("ordinary_data"), ordinary.finish(Map.of("data", argument))));
            var write = new Graph(ordinary.inputs); var fields = new LinkedHashMap<String, String>();
            fields.put("op", write.constant(query.equals("delete") ? "delete" : "put"));
            for (String field : query.equals("delete") ? List.of("volume", "entry") : List.of("volume", "entry", "kind", "value"))
                fields.put(field, write.dynamic(write.input("arguments"), field));
            fields.put("expected", write.dynamic(write.input("read_result"), "version"));
            modules.put(key("write_data"), new ModuleDefinition(key("write_data"), write.finish(Map.of("data", write.node(new ComputationGraph.MakeRecord(fields))))));
            String selectedData = prepare.node(new ComputationGraph.Branch(prepare.equals(prepare.field(command, "operation"), prepare.constant("write")),
                key("write_data"), key("ordinary_data"), Map.of("arguments", prepare.input("arguments"), "command", command, "read_result", prepare.input("read_result"))));
            data = prepare.field(selectedData, "data");
        } else data = prepare.node(new ComputationGraph.DynamicField(prepare.input("arguments"), prepare.field(command, "argument")));
        String packet = prepare.node(new ComputationGraph.MakeRecord(Map.of("kind", prepare.constant("library_command"), "target", prepare.input("target"),
            "request", prepare.input("request"), "operation", prepare.field(command, "operation"), "sequence", prepare.input("sequence"), "data", data)));
        entry("prepare", prepare, Map.of("packet", packet, "matched", prepare.constant(false), "finished", prepare.constant(false)));

        var sent = new Graph(Map.of("sent", SerialResult.SEND)); entry("sent", sent, Map.of("send_ok", sent.field(sent.input("sent"), "ok")));
        var received = new Graph(Map.of("received", SerialResult.RECEIVE)); entry("received", received, Map.of("got", received.field(received.input("received"), "ok")));

        // Guard field presence before extracting. Unrelated ordinary records remain legal cable traffic.
        var matchInputs = Map.<String, DataType>of("received", SerialResult.RECEIVE, "packet", PACKET);
        var exact = new Graph(matchInputs); String reply = exact.field(exact.input("received"), "value");
        String matches = exact.equals(exact.dynamic(reply, "kind"), exact.constant("library_reply"));
        for (String field : List.of("target", "request")) matches = exact.and(matches, exact.equals(exact.dynamic(reply, field), exact.field(exact.input("packet"), field)));
        modules.put(key("match_fields"), new ModuleDefinition(key("match_fields"), exact.finish(Map.of("matched", matches))));
        var noMatch = new Graph(matchInputs); modules.put(key("unrelated"), new ModuleDefinition(key("unrelated"), noMatch.finish(Map.of("matched", noMatch.constant(false)))));
        var match = new Graph(matchInputs); String body = match.field(match.input("received"), "value"); String guard = match.constant(true);
        for (String field : List.of("kind", "target", "request", "code", "sequence", "value"))
            guard = match.and(guard, match.node(new ComputationGraph.HasField(body, match.constant(field))));
        String selected = match.node(new ComputationGraph.Branch(guard, key("match_fields"), key("unrelated"), Map.of("received", match.input("received"), "packet", match.input("packet"))));
        entry("match", match, Map.of("matched", match.field(selected, "matched")));

        var io = new Graph(Map.of("received", SerialResult.RECEIVE, "serial", SerialResult.STATUS, "matched", BOOLEAN));
        String receiveOk = io.or(io.field(io.input("received"), "ok"), io.equals(io.field(io.input("received"), "status"), io.constant("empty")));
        String wireOk = io.and(io.equals(io.field(io.input("serial"), "connection"), io.constant("ready")), io.equals(io.field(io.input("serial"), "failure"), io.constant("")));
        entry("io", io, Map.of("finished", io.or(io.input("matched"), io.not(io.and(receiveOk, wireOk)))));

        var advance = new Graph(Map.of("commands", COMMANDS, "index", INTEGER, "received", SerialResult.RECEIVE, "read_result", ANY));
        String current = advance.node(new ComputationGraph.DynamicIndex(advance.input("commands"), advance.input("index")));
        String response = advance.field(advance.input("received"), "value"), code = advance.dynamic(response, "code");
        String expected = advance.equals(code, advance.field(current, "expected"));
        if (recoveryDriver && !failureRecovery) expected = advance.or(expected,
            advance.and(advance.equals(advance.field(current, "operation"), advance.constant("status")),
                advance.equals(code, advance.constant("busy"))));
        String busyQuery = failureRecovery ? advance.constant(false)
            : advance.and(advance.equals(advance.field(current, "operation"), advance.constant("result")), advance.equals(code, advance.constant("busy")));
        String failed = advance.not(advance.or(expected, busyQuery));
        String next = advance.select(expected, advance.add(advance.input("index"), advance.constant(1)), advance.input("index"));
        String completed = advance.and(expected, advance.field(current, "last"));
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("index", next); outputs.put("sequence", advance.dynamic(response, "sequence"));
        outputs.put("done", advance.or(failed, completed)); outputs.put("success", completed);
        String capture = mutationDriver ? advance.or(advance.equals(code, advance.constant("stat")), advance.equals(code, advance.constant("read")))
            : advance.equals(code, advance.constant(query.equals("write_recovery") ? "written" : query));
        if (query.equals("query_recovery")) capture = advance.and(expected, advance.or(advance.equals(code, advance.constant("list")),
            advance.or(advance.equals(code, advance.constant("read")), advance.equals(code, advance.constant("stat")))));
        if (failureRecovery) capture = advance.and(expected, advance.equals(advance.field(current, "operation"), advance.constant("result")));
        outputs.put("read_result", advance.select(capture, failureRecovery ? response : advance.dynamic(response, "value"), advance.input("read_result")));
        outputs.put("error", advance.select(failed, response, advance.constant(DataValue.NullValue.INSTANCE)));
        if (query.equals("delete")) {
            // This check is an editable lazy subgraph, not a privileged device operation.
            var check = new Graph(Map.of("read_result", ANY));
            String absent = check.equals(check.dynamic(check.input("read_result"), "present"), check.constant(false));
            String error = check.node(new ComputationGraph.MakeRecord(Map.of("code", check.constant("entry_still_present"), "value", check.input("read_result"))));
            modules.put(key("confirm_absent"), new ModuleDefinition(key("confirm_absent"), check.finish(Map.of("confirmed", absent,
                "error", check.select(absent, check.constant(DataValue.NullValue.INSTANCE), error)))));
            var pending = new Graph(check.inputs);
            modules.put(key("not_final"), new ModuleDefinition(key("not_final"), pending.finish(Map.of("confirmed", pending.constant(true), "error", pending.constant(DataValue.NullValue.INSTANCE)))));
            String confirmation = advance.node(new ComputationGraph.Branch(completed, key("confirm_absent"), key("not_final"), Map.of("read_result", advance.input("read_result"))));
            outputs.put("success", advance.and(completed, advance.field(confirmation, "confirmed")));
            outputs.put("error", advance.select(failed, response, advance.field(confirmation, "error")));
        }
        entry("advance", advance, outputs);
        var sendError = new Graph(Map.of("sent", SerialResult.SEND, "error", ANY));
        entry("send_error", sendError, Map.of("done", sendError.constant(true), "error", sendError.select(sendError.constant(true), sendError.input("sent"), sendError.input("error"))));
        var ioError = new Graph(Map.of("serial", SerialResult.STATUS, "error", ANY));
        entry("io_error", ioError, Map.of("done", ioError.constant(true), "error", ioError.select(ioError.constant(true), ioError.input("serial"), ioError.input("error"))));

        var initial = new LinkedHashMap<String, DataValue>();
        initial.put("target", new DataValue.RecordValue(Map.of("x", integer(0), "y", integer(0), "z", integer(0))));
        initial.put("arguments", new DataValue.RecordValue(Map.of("track", new DataValue.RecordValue(Map.of("x", integer(1), "y", integer(0), "z", integer(0))),
            "slot", integer(2), "entry", text(query.equals("stat") ? "new_entry" : "reading"), "none", DataValue.NullValue.INSTANCE)));
        initial.put("commands", new DataValue.ListValue(List.of(
            command("status", "none", "idle", false), command("seek", "track", "accepted", false), command("result", "none", "positioned", false), command("ack", "none", "acknowledged", false),
            command("select", "slot", "accepted", false), command("result", "none", "selected", false), command("ack", "none", "acknowledged", false),
            command(query, query.equals("list") ? "page" : "entry", "accepted", false), command("result", "none", query, false), command("ack", "none", "acknowledged", true))));
        if (query.equals("list")) {
            var arguments = new LinkedHashMap<>(((DataValue.RecordValue)initial.get("arguments")).fields());
            arguments.remove("entry");
            arguments.put("page", new DataValue.RecordValue(Map.of("after", DataValue.NullValue.INSTANCE, "count", integer(4))));
            initial.put("arguments", new DataValue.RecordValue(arguments));
        }
        if (mutationDriver) {
            var arguments = new LinkedHashMap<>(((DataValue.RecordValue)initial.get("arguments")).fields());
            arguments.put("volume", text("日志"));
            if (query.equals("write")) { arguments.put("kind", text("DATA")); arguments.put("value", integer(44)); }
            initial.put("arguments", new DataValue.RecordValue(arguments));
            initial.put("commands", new DataValue.ListValue(List.of(
                command("status", "none", "idle", false), command("seek", "track", "accepted", false), command("result", "none", "positioned", false), command("ack", "none", "acknowledged", false),
                command("select", "slot", "accepted", false), command("result", "none", "selected", false), command("ack", "none", "acknowledged", false),
                command("stat", "entry", "accepted", false), command("result", "none", "stat", false), command("ack", "none", "acknowledged", false),
                command("write", "none", "accepted", false), command("result", "none", "written", false), command("ack", "none", "acknowledged", false),
                command(query.equals("delete") ? "stat" : "read", "entry", "accepted", false),
                command("result", "none", query.equals("delete") ? "stat" : "read", false), command("ack", "none", "acknowledged", true))));
        }
        if (recoveryDriver) {
            initial.put("arguments", new DataValue.RecordValue(Map.of("none", DataValue.NullValue.INSTANCE)));
            initial.put("commands", new DataValue.ListValue(List.of(command("status", "none", "result_pending", false),
                command("result", "none", failureRecovery ? "denied" : query.equals("query_recovery") ? "list" : "written", false), command("ack", "none", "acknowledged", true))));
        }
        initial.put("index", integer(0)); initial.put("sequence", integer(0)); initial.put("request", text("")); initial.put("interval", integer(20));
        for (String field : List.of("done", "success", "matched", "finished", "send_ok", "got")) initial.put(field, new DataValue.BooleanValue(false));
        initial.put("error", DataValue.NullValue.INSTANCE); initial.put("read_result", DataValue.NullValue.INSTANCE);
        initial.put("packet", new DataValue.RecordValue(Map.of("kind", text("library_command"), "target", initial.get("target"), "request", text(""), "operation", text("status"), "sequence", integer(0), "data", DataValue.NullValue.INSTANCE)));
        initial.put("received", SerialResult.receive("empty", null));
        initial.put("serial", new DataValue.RecordValue(Map.of("transmit", text("idle"), "connection", text("unobserved"), "failure", text(""),
            "receive_frames", integer(0), "receive_bytes", integer(0), "receive_faults", new DataValue.ListValue(List.of()))));
        var types = new LinkedHashMap<String, DataType>(); initial.forEach((name, value) -> types.put(name, DataType.typeOf(value)));
        types.put("arguments", ANY); types.put("sequence", ANY); types.put("error", ANY); types.put("read_result", ANY);
        types.put("received", SerialResult.RECEIVE); types.put("packet", PACKET); types.put("commands", COMMANDS);
        types.put("serial", SerialResult.STATUS);
        var definition = new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), entries);
        var workspace = new ProgramWorkspace(ProgramDocument.of(new ProgramArchive(definition, modules)));
        workspace.setFlow(Optional.of(new FlowDocumentBody(types, List.of(
            new FlowProgram.Until("read_until_done", "done"), new FlowProgram.Correlation("new_request", "request"), new FlowProgram.Evaluate("build_packet", "prepare"),
            new FlowProgram.SerialSend("send_once", "s0", "packet", "sent"), new FlowProgram.Evaluate("check_send", "sent"), new FlowProgram.If("queued", "send_ok"),
            new FlowProgram.Until("wait_reply", "finished"), new FlowProgram.SerialReceive("receive", "s0", "received"), new FlowProgram.Evaluate("check_receive", "received"),
            new FlowProgram.If("has_frame", "got"), new FlowProgram.Evaluate("match_reply", "match"), new FlowProgram.EndIf("end_frame"),
            new FlowProgram.SerialStatus("inspect_serial", "s0", "serial"), new FlowProgram.Evaluate("check_io", "io"), new FlowProgram.Sleep("poll_interval", "interval"), new FlowProgram.EndUntil("end_wait"),
            new FlowProgram.If("matched_reply", "matched"), new FlowProgram.Evaluate("next_command", "advance"), new FlowProgram.Otherwise("io_failure"), new FlowProgram.Evaluate("save_io_failure", "io_error"), new FlowProgram.EndIf("end_reply"),
            new FlowProgram.Otherwise("send_failure"), new FlowProgram.Evaluate("save_send_failure", "send_error"), new FlowProgram.EndIf("end_send"), new FlowProgram.EndUntil("end_read")),
            new DataValue.RecordValue(initial))));
        var document = workspace.snapshot();
        if (!failureRecovery) return document;
        var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
        nodes.put("title", new TerminalDefinition.Text(new TerminalBinding.Literal(text("Library: claim failure, not retry"))));
        var paths = new LinkedHashMap<String, ValuePath>();
        var root = new ValuePath("output", List.of());
        paths.put("acknowledged", root.key("success"));
        paths.put("receipt_code", root.key("read_result").key("code"));
        paths.put("receipt_sequence", root.key("read_result").key("sequence"));
        paths.put("receipt", root.key("read_result"));
        paths.put("handling_error", root.key("error"));
        var rows = new ArrayList<String>(); rows.add("title");
        paths.forEach((name, path) -> {
            rows.add(name + "_row");
            nodes.put(name + "_row", new TerminalDefinition.Row(List.of(name + "_label", name)));
            nodes.put(name + "_label", new TerminalDefinition.Text(new TerminalBinding.Literal(text(name))));
            nodes.put(name, new TerminalDefinition.Text(new TerminalBinding.Path(path)));
        });
        nodes.put("root", new TerminalDefinition.Column(rows));
        return new ProgramDocument(document.archive(), document.positions(), Optional.of(new TerminalDefinition("root", nodes)), document.flow());
    }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue command(String operation, String argument, String expected, boolean last) {
        return new DataValue.RecordValue(Map.of("operation", text(operation), "argument", text(argument), "expected", text(expected), "last", new DataValue.BooleanValue(last)));
    }
    static final class Graph {
        final Map<String, DataType> inputs; final Map<String, ComputationGraph.Node> nodes = new LinkedHashMap<>();
        final Map<DataValue, String> constants = new HashMap<>();
        Graph(Map<String, DataType> inputs) { this.inputs = new TreeMap<>(inputs); }
        String input(String name) { nodes.putIfAbsent("input_" + name, new ComputationGraph.Input(name)); return "input_" + name; }
        String node(ComputationGraph.Node node) { String id = "n" + nodes.size(); nodes.put(id, node); return id; }
        String constant(Object value) {
            DataValue data = value instanceof DataValue v ? v : value instanceof Boolean b ? new DataValue.BooleanValue(b) : value instanceof Number n ? integer(n.longValue()) : text((String)value);
            return constants.computeIfAbsent(data, v -> node(new ComputationGraph.Constant(v)));
        }
        String field(String source, String name) { return node(new ComputationGraph.Field(source, name)); }
        String dynamic(String source, String name) { return node(new ComputationGraph.DynamicField(source, constant(name))); }
        String equals(String a, String b) { return node(new ComputationGraph.ValueEquals(a, b)); }
        String and(String a, String b) { return scalar(ScalarOperation.AND_BOOLEAN, a, b); }
        String or(String a, String b) { return scalar(ScalarOperation.OR_BOOLEAN, a, b); }
        String add(String a, String b) { return scalar(ScalarOperation.ADD_INTEGER, a, b); }
        String scalar(ScalarOperation operation, String a, String b) { return node(new ComputationGraph.Scalar(operation, Map.of("left", a, "right", b))); }
        String not(String value) { return node(new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", value))); }
        String select(String condition, String yes, String no) { return node(new ComputationGraph.Scalar(ScalarOperation.SELECT, Map.of("condition", condition, "when_true", yes, "when_false", no))); }
        ComputationGraph finish(Map<String, String> outputs) { return new ComputationGraph(inputs, nodes, outputs); }
    }
    public static void main(String[] args) throws Exception {
        String mode = args.length > 1 ? args[1] : "read";
        var document = new LibraryReadDriverDocument(mode).build(); document.validate(new ResourceLimits(256, 65536, 8));
        System.out.println("Program units: " + (document.archive().compile(new ResourceLimits(256, 65536, 8)).usage().programUnits() + document.flow().orElseThrow().steps().size()));
        var diagnostic = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeAnyTag(EditorValueTableCompaction.compact(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(ProgramDocumentCodec.encode(document), LibraryReadDriverTest.ARCHIVE)), new java.io.DataOutputStream(diagnostic));
        System.out.println("Document bytes: " + diagnostic.size());
        var root = Path.of(args[0]); var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, LibraryReadDriverTest.ARCHIVE)) + "\n";
        String filename = "library_" + mode + "_driver.bcp";
        Files.writeString(root.resolve("src/main/resources/assets/create_biotech/biocomputing/examples/" + filename), encoded);
        Files.writeString(root.resolve("examples/biocomputing/" + filename), encoded);
        System.out.println("Driver document generated; program units: " + (document.archive().compile(new ResourceLimits(256, 65536, 8)).usage().programUnits() + document.flow().orElseThrow().steps().size()));
    }
}
