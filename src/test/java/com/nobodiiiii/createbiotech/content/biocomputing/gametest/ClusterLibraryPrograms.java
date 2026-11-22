package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;

/** Editable reference application assembled exclusively from public nodes and effects. No service calls. */
public final class ClusterLibraryPrograms {
    private ClusterLibraryPrograms() {}
    public static DataValue.RecordValue obj(Object... pairs) {
        var values = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String)pairs[i],
            pairs[i + 1] instanceof String text ? new DataValue.TextValue(text) : (DataValue)pairs[i + 1]);
        return new DataValue.RecordValue(values);
    }
    private static ComputationGraph.Constant text(String value) { return new ComputationGraph.Constant(new DataValue.TextValue(value)); }
    public static ProgramArchive worker() {
        var valueType = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        var requestNodes = new LinkedHashMap<String, ComputationGraph.Node>();
        requestNodes.put("value", new ComputationGraph.Input("value"));
        requestNodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        requestNodes.put("sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "one"));
        requestNodes.put("saved", new ComputationGraph.MakeRecord(Map.of("value", "sum")));
        requestNodes.put("target", text("storage"));
        requestNodes.put("stat", new ComputationGraph.Constant(obj("op", "stat", "volume", "日志", "entry", "joint_reading")));
        var request = new ProgramDefinition.Entry(new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), requestNodes,
            Map.of("saved", "saved", "target", "target", "payload", "stat")),
            List.of(),
            List.of(ProgramDefinition.Send.continueRequest("target", "payload", "stat", "checked", "saved", "saved")));

        var versionKey = new ModuleDefinition.Key("read_version", 1);
        var emptyKey = new ModuleDefinition.Key("no_version", 1);
        var moduleInputs = Map.of("data", (DataType) DataType.Scalar.ANY);
        var version = new ModuleDefinition(versionKey, new ComputationGraph(moduleInputs, Map.of(
            "data", new ComputationGraph.Input("data"), "version", new ComputationGraph.Field("data", "version")),
            Map.of("version", "version")));
        var noVersion = new ModuleDefinition(emptyKey, new ComputationGraph(moduleInputs,
            Map.of("null", new ComputationGraph.Constant(DataValue.NullValue.INSTANCE)), Map.of("version", "null")));
        var replyInputs = Map.of("code", (DataType)DataType.Scalar.TEXT, "data", DataType.Scalar.ANY, "saved", valueType);
        var checkedNodes = new LinkedHashMap<String, ComputationGraph.Node>();
        checkedNodes.put("code", new ComputationGraph.Input("code"));
        checkedNodes.put("data", new ComputationGraph.Input("data"));
        checkedNodes.put("saved", new ComputationGraph.Input("saved"));
        checkedNodes.put("ok", text("ok"));
        checkedNodes.put("success", new ComputationGraph.Text(TextOperation.EQUAL, Map.of("left", "code", "right", "ok")));
        checkedNodes.put("failure", new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", "success")));
        checkedNodes.put("target", text("storage"));
        checkedNodes.put("branch", new ComputationGraph.Branch("success", versionKey, emptyKey, Map.of("data", "data")));
        checkedNodes.put("version", new ComputationGraph.Field("branch", "version"));
        checkedNodes.put("op", text("put")); checkedNodes.put("volume", text("日志")); checkedNodes.put("entry", text("joint_reading")); checkedNodes.put("kind", text("DATA"));
        checkedNodes.put("put", new ComputationGraph.MakeRecord(Map.of("op", "op", "volume", "volume", "entry", "entry",
            "kind", "kind", "expected", "version", "value", "saved")));
        checkedNodes.put("error", new ComputationGraph.MakeRecord(Map.of("code", "code", "data", "data", "value", "saved")));
        var checked = new ProgramDefinition.Entry(new ComputationGraph(replyInputs, checkedNodes,
            Map.of("success", "success", "failure", "failure", "target", "target", "put", "put", "error", "error", "saved", "saved")),
            List.of(), List.of(ProgramDefinition.Send.reply("error").when("failure"),
                ProgramDefinition.Send.continueRequest("target", "put", "put", "stored", "saved", "saved").when("success")));
        var stored = new ProgramDefinition.Entry(new ComputationGraph(replyInputs, Map.of(
            "code", new ComputationGraph.Input("code"), "data", new ComputationGraph.Input("data"), "saved", new ComputationGraph.Input("saved"),
            "reply", new ComputationGraph.MakeRecord(Map.of("code", "code", "data", "data", "value", "saved"))),
            Map.of("reply", "reply")), List.of(), List.of(ProgramDefinition.Send.reply("reply")));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(),
            Map.of("request", request, "checked", checked, "stored", stored)), Map.of(versionKey, version, emptyKey, noVersion));
    }
    public static ProgramArchive caller() {
        var root = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "value", new ComputationGraph.Input("value"), "target", text("calculate"),
            "payload", new ComputationGraph.MakeRecord(Map.of("value", "value"))), Map.of("target", "target", "payload", "payload"));
        var reply = new ComputationGraph(Map.of("code", DataType.Scalar.TEXT, "data", DataType.Scalar.ANY, "value", DataType.Scalar.ANY),
            Map.of("code", new ComputationGraph.Input("code"), "data", new ComputationGraph.Input("data"),
                "value", new ComputationGraph.Input("value"), "old", new ComputationGraph.ReadMemory("calls"),
                "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
                "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
            Map.of("code", "code", "data", "data", "value", "value", "calls", "next"));
        return new ProgramArchive(new ProgramDefinition(root, Map.of(
            "code", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue("")),
            "data", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE),
            "value", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE),
            "calls", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "job", "result")),
            Map.of("result", new ProgramDefinition.Entry(reply, List.of(new ProgramDefinition.Write("code", "code"),
                new ProgramDefinition.Write("data", "data"), new ProgramDefinition.Write("value", "value"),
                new ProgramDefinition.Write("calls", "calls")), List.of()))), Map.of());
    }
}
