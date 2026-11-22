package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
public final class TextProgramFixtures {
    private TextProgramFixtures() {}
    public static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    public static DataValue.IntegerValue integer(long n) { return new DataValue.IntegerValue(n); }
    public static DataValue.BooleanValue bool(boolean b) { return new DataValue.BooleanValue(b); }
    public record Example(TextOperation operation, Map<String,DataValue> arguments, DataValue expected) {}
    public static List<Example> examples() {
        return List.of(
            new Example(TextOperation.LENGTH,Map.of("text",text("A😀中")),integer(3)),
            new Example(TextOperation.LETTER_OF,Map.of("text",text("A😀中"),"index",integer(2)),text("😀")),
            new Example(TextOperation.CONCAT,Map.of("left",text("A😀"),"right",text("中")),text("A😀中")),
            new Example(TextOperation.CHARACTER_AT,Map.of("text",text("A😀中"),"index",integer(1)),text("😀")),
            new Example(TextOperation.SUBSTRING,Map.of("text",text("A😀中"),"start",integer(1),"end",integer(3)),text("😀中")),
            new Example(TextOperation.EQUAL,Map.of("left",text("😀"),"right",text("😀")),bool(true)),
            new Example(TextOperation.COMPARE,Map.of("left",text("😀"),"right",text("\uE000")),integer(1)),
            new Example(TextOperation.INDEX_OF,Map.of("text",text("ababa"),"needle",text("aba")),integer(0)),
            new Example(TextOperation.CONTAINS,Map.of("text",text("A😀中"),"needle",text("😀")),bool(true)),
            new Example(TextOperation.CONTAINS_IGNORE_CASE,Map.of("text",text("Sensor-A"),"needle",text("sensor")),bool(true)),
            new Example(TextOperation.STARTS_WITH,Map.of("text",text("ababa"),"needle",text("aba")),bool(true)),
            new Example(TextOperation.ENDS_WITH,Map.of("text",text("A😀中"),"needle",text("😀中")),bool(true)),
            new Example(TextOperation.SPLIT,Map.of("text",text(",a,,"),"delimiter",text(",")),new DataValue.ListValue(List.of(text(""),text("a"),text(""),text("")))),
            new Example(TextOperation.JOIN,Map.of("items",new DataValue.ListValue(List.of(text(""),text("a"),text(""),text(""))),"separator",text("|")),text("|a||")),
            new Example(TextOperation.REPLACE,Map.of("text",text("ababa"),"needle",text("aba"),"replacement",text("X")),text("Xba")));
    }
    public static ProgramArchive archive(TextOperation operation, Map<String,DataValue> args) {
        var nodes=new LinkedHashMap<String,ComputationGraph.Node>();var bindings=new LinkedHashMap<String,String>();
        args.forEach((name,value)->{nodes.put(name,new ComputationGraph.Constant(value));bindings.put(name,name);});
        nodes.put("result",new ComputationGraph.Text(operation,bindings));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),nodes,Map.of("result","result")),Map.of(),List.of()),Map.of());
    }
    /** Public ordinary-record station filtering: no world service or hidden lookup operation. */
    public static ProgramArchive stationArchive(){
        var stationType=new DataType.RecordType(Map.of("name",DataType.Scalar.TEXT));
        var matchKey=new ModuleDefinition.Key("match_station_name",1);
        var callKey=new ModuleDefinition.Key("call_name_match",1);
        var predicateKey=new ModuleDefinition.Key("station_predicate",1);
        var mapperKey=new ModuleDefinition.Key("station_name",1);
        var foldKey=new ModuleDefinition.Key("concat_names",1);
        var match=new ComputationGraph(Map.of("name",DataType.Scalar.TEXT,"needle",DataType.Scalar.TEXT),Map.of(
            "name",new ComputationGraph.Input("name"),"needle",new ComputationGraph.Input("needle"),
            "keep",new ComputationGraph.Text(TextOperation.CONTAINS_IGNORE_CASE,Map.of("text","name","needle","needle"))),Map.of("keep","keep"));
        var call=new ComputationGraph(match.inputs(),Map.of(
            "name",new ComputationGraph.Input("name"),"needle",new ComputationGraph.Input("needle"),
            "call",new ComputationGraph.Call(matchKey,Map.of("name","name","needle","needle")),
            "keep",new ComputationGraph.Field("call","keep")),Map.of("keep","keep"));
        var predicate=new ComputationGraph(Map.of("station",stationType,"needle",DataType.Scalar.TEXT),Map.of(
            "station",new ComputationGraph.Input("station"),"needle",new ComputationGraph.Input("needle"),
            "name",new ComputationGraph.Field("station","name"),"condition",new ComputationGraph.Constant(bool(true)),
            "branch",new ComputationGraph.Branch("condition",callKey,callKey,Map.of("name","name","needle","needle")),
            "keep",new ComputationGraph.Field("branch","keep")),Map.of("keep","keep"));
        var mapper=new ComputationGraph(Map.of("station",stationType),Map.of(
            "station",new ComputationGraph.Input("station"),"name",new ComputationGraph.Field("station","name"),
            "empty",new ComputationGraph.Constant(text("")),
            "name_copy",new ComputationGraph.Text(TextOperation.CONCAT,Map.of("left","name","right","empty"))),Map.of("name","name_copy"));
        var folder=new ComputationGraph(Map.of("item",DataType.Scalar.TEXT,"total",DataType.Scalar.TEXT),Map.of(
            "item",new ComputationGraph.Input("item"),"total",new ComputationGraph.Input("total"),
            "next",new ComputationGraph.Text(TextOperation.CONCAT,Map.of("left","total","right","item"))),Map.of("next","next"));
        var nodes=new LinkedHashMap<String,ComputationGraph.Node>();
        nodes.put("stations",new ComputationGraph.Constant(new DataValue.ListValue(List.of(station("Sensor-A"),station("Pump-B"),station("SENSOR-C")))));
        nodes.put("needle",new ComputationGraph.Constant(text("sensor")));nodes.put("empty",new ComputationGraph.Constant(text("")));
        nodes.put("filtered",new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER,"stations",predicateKey,"station",Map.of("needle","needle"),"keep"));
        nodes.put("names",new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP,"filtered",mapperKey,"station",Map.of(),"name"));
        nodes.put("joined",new ComputationGraph.Fold("names","empty",foldKey,"item","total",Map.of(),"next"));
        var root=new ComputationGraph(Map.of(),nodes,Map.of("filtered","filtered","names","names","joined","joined"));
        return new ProgramArchive(new ProgramDefinition(root,Map.of(),List.of()),Map.of(
            matchKey,new ModuleDefinition(matchKey,match),callKey,new ModuleDefinition(callKey,call),
            predicateKey,new ModuleDefinition(predicateKey,predicate),mapperKey,new ModuleDefinition(mapperKey,mapper),foldKey,new ModuleDefinition(foldKey,folder)));
    }
    public static DataValue.RecordValue station(String name){return new DataValue.RecordValue(Map.of("name",text(name)));}
}
