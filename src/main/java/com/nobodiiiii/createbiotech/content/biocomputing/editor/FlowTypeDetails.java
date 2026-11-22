package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.network.chat.Component;

/** Read-only source contracts and bounded compiler inference, never runtime sample inspection. */
final class FlowTypeDetails {
    private static final Map<String, DataType> EQUALITY_INPUTS = Map.of("left", DataType.Scalar.ANY, "right", DataType.Scalar.ANY);
    private static final Map<String, DataType> INDEX_INPUTS = Map.of("list", new DataType.ListType(DataType.Scalar.ANY), "index", DataType.Scalar.INTEGER);
    private static final Map<String, DataType> SEARCH_INPUTS = Map.of("list", new DataType.ListType(DataType.Scalar.ANY), "value", DataType.Scalar.ANY);
    record Hint(String kind, String name, DataType type) {}
    static Hint declared(String name, DataType type) { return new Hint("type_declared", name, type); }
    static Hint proven(String name, DataType type) { return new Hint("type_proven", name, type); }
    static List<Hint> source(InlineGraphEdits.Source source) {
        if (source instanceof InlineGraphEdits.RandomRange range) return List.of(proven("", range.type()),
            new Hint("type_accepted", "from", range.type()), new Hint("type_accepted", "through", range.type()));
        if (source instanceof InlineGraphEdits.Timer) return List.of(proven("", DataType.Scalar.DECIMAL));
        if(source instanceof InlineGraphEdits.KeyDown)return List.of(proven("",DataType.Scalar.BOOLEAN));
        if (source instanceof InlineGraphEdits.ProcedureParameter p) return List.of(declared(p.name(), p.type()));
        if (source instanceof InlineGraphEdits.Variable v) return List.of(declared(v.label(), field(v.type(),v.path())));
        if (source instanceof InlineGraphEdits.PublicVariable v) return List.of(declared(v.label(), field(v.type(),v.path())));
        if (source instanceof InlineGraphEdits.Literal v) return List.of(proven("", DataType.typeOf(v.value())));
        if (source instanceof InlineGraphEdits.ListSearch search) return List.of(
            proven("", search.contains() ? DataType.Scalar.BOOLEAN : DataType.Scalar.INTEGER),
            new Hint("type_accepted", "list", SEARCH_INPUTS.get("list")), new Hint("type_accepted", "value", DataType.Scalar.ANY));
        Map<String,DataType> inputs = source instanceof InlineGraphEdits.Operator o ? o.operation().inputs()
            : source instanceof InlineGraphEdits.TextOperator o ? o.operation().inputs()
            : source instanceof InlineGraphEdits.ValueEquality ? EQUALITY_INPUTS
            : source instanceof InlineGraphEdits.ListItem ? INDEX_INPUTS : Map.of();
        return inputs.isEmpty() ? List.of(proven("",null)) : inputs.entrySet().stream().map(e->new Hint("type_accepted",e.getKey(),e.getValue())).toList();
    }
    static Hint local(String name, Map<String, DataType> scope) { return new Hint("type_local", name, scope.get(name)); }
    static List<Hint> signature(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure procedure) {
        var hints=new ArrayList<Hint>(); procedure.inputs().forEach((name,type)->hints.add(declared(name,type)));
        procedure.outputs().forEach((name,type)->hints.add(declared("→ "+name,type))); return hints;
    }
    static List<Hint> expression(ComputationGraph graph,String id,Map<String,DataType> locals,
            Map<String,String> bindings,Map<String,ProgramDefinition.Cell> cells) {
        // The compiler uses the graph's declared inputs; call-site bindings never narrow them.
        var invalid = new ArrayList<Hint>();
        graph.inputs().forEach((name, declared) -> {
            var local = bindings.get(name);
            var actual = local == null ? null : locals.get(local);
            if (actual == null || !declared.compatibleWith(actual))
                invalid.add(new Hint("type_binding_invalid", name, null));
        });
        if (bindings.keySet().stream().anyMatch(name -> !graph.inputs().containsKey(name)))
            invalid.add(new Hint("type_binding_extra", "", null));
        var types=new HashMap<String,DataType>();
        DataType inferred=invalid.isEmpty() ? infer(graph,id,graph.inputs(),cells,types,new HashSet<>(),new int[]{128},0) : null;
        var result=new ArrayList<Hint>(); result.add(proven("",inferred));
        result.addAll(invalid);
        var node=graph.nodes().get(id);
        if(node instanceof ComputationGraph.Input n)result.add(declared(n.name(),graph.inputs().get(n.name())));
        if(node instanceof ComputationGraph.ReadMemory n)result.add(declared(n.cell(),Optional.ofNullable(cells.get(n.cell())).map(ProgramDefinition.Cell::type).orElse(null)));
        Map<String,DataType> accepted=node instanceof ComputationGraph.Scalar n?n.operation().inputs()
            : node instanceof ComputationGraph.Text n?n.operation().inputs()
            : node instanceof ComputationGraph.ValueEquals ? EQUALITY_INPUTS
            : node instanceof ComputationGraph.DynamicIndex ? INDEX_INPUTS
            : node instanceof ComputationGraph.RandomRange range ? Map.of("from", range.type(), "through", range.type())
            : node instanceof ComputationGraph.ListSearch ? SEARCH_INPUTS : Map.of();
        accepted.forEach((name,type)->result.add(new Hint("type_accepted",name,type)));
        return result;
    }
    private static DataType infer(ComputationGraph graph,String id,Map<String,DataType> inputs,Map<String,ProgramDefinition.Cell> cells,
            Map<String,DataType> types,Set<String> path,int[] remaining,int depth) {
        if(types.containsKey(id))return types.get(id);
        if(id==null || depth>=32 || remaining[0]--<=0 || !path.add(id))return null;
        var node=graph.nodes().get(id); if(node==null)return null;
        for(String dependency:node.dependencies())if(infer(graph,dependency,inputs,cells,types,path,remaining,depth+1)==null){path.remove(id);return null;}
        path.remove(id);
        try {var type=ProgramCompiler.inferNode(node,types,inputs,cells,Map.of(),false,id);types.put(id,type);return type;}
        catch(ComputationException|IllegalArgumentException invalid){return null;}
    }
    static DataType field(DataType type,List<String> path) {
        for (String name:path) { if(type instanceof DataType.RecordType r) type=r.fields().get(name); else if(type instanceof DataType.MapType m) type=m.element(); else return type==DataType.Scalar.ANY?type:null; }
        return type;
    }
    static Component tooltip(Component base,List<Hint> hints,boolean shift,Boolean isa1b) {
        return tooltip(base,hints,shift,isa1b,false);
    }
    static Component tooltip(Component base,List<Hint> hints,boolean shift,Boolean isa1b,boolean closed) {
        return tooltip(base,hints,shift,isa1b,closed,false);
    }
    static Component tooltip(Component base,List<Hint> hints,boolean shift,Boolean isa1b,boolean closed,boolean software) {
        var result=Component.empty();
        // Keep actionable compatibility advice first: long names must not truncate it.
        if (Boolean.FALSE.equals(isa1b) && !closed && !software) {
            if (hints.stream().anyMatch(h -> !h.kind().equals("type_accepted") && any(h.type())))
                result.append(text("type_any_advice")).append("\n");
            else if (hints.stream().anyMatch(h -> any(h.type())))
                result.append(text("type_any_generic_advice")).append("\n");
        }
        if (software) result.append(text("type_software_signature")).append("\n");
        result.append(shorten(base.getString(),320));
        if (shift && closed) result.append("\n").append(text("type_closed_signature"));
        if(shift) for(var hint:hints.stream().limit(12).toList()) {
            String type=hint.type()==null?text("type_unknown").getString():label(hint.type());
            result.append("\n").append(text(hint.kind(),shorten(hint.name(),80),type));
            if(result.getString().length()>1500)break;
        }
        String bounded=result.getString();
        return bounded.length()>1800?Component.literal(bounded.substring(0,1799)+"…"):result;
    }
    static List<net.minecraft.util.FormattedCharSequence> visibleLines(net.minecraft.client.gui.Font font, Component hint, int width, int height) {
        var lines = font.split(hint, Math.max(16, Math.min(300, width - 32)));
        // Vanilla uses ten-pixel line spacing plus the tooltip margins.
        int maxLines = Math.max(1, (height - 24) / 10);
        if (lines.size() <= maxLines) return lines;
        var visible = new ArrayList<>(lines.subList(0, maxLines - 1));
        visible.add(Component.literal("…").getVisualOrderText());
        return visible;
    }
    static boolean any(DataType type) {
        if(type==null)return false;
        var pending=new ArrayDeque<DataType>();pending.add(type);
        var seen=Collections.newSetFromMap(new IdentityHashMap<DataType,Boolean>());
        while(!pending.isEmpty()) {
            var next=pending.removeLast(); if(!seen.add(next))continue;
            if(next==DataType.Scalar.ANY)return true;
            if(next instanceof DataType.ListType l)pending.add(l.element());
            else if(next instanceof DataType.MapType m)pending.add(m.element());
            else if(next instanceof DataType.OptionalType o)pending.add(o.value());
            else if(next instanceof DataType.RecordType r)pending.addAll(r.fields().values());
        }
        return false;
    }
    private static String shorten(String value,int max) {return value.length()>max?value.substring(0,max-1)+"…":value;}
    private static String label(DataType type) {
        var out=new StringBuilder();label(type,0,new int[]{48},out);return out.toString();
    }
    private static void append(StringBuilder out,String text) {
        if(out.length()<600)out.append(shorten(text,Math.min(600-out.length(),text.length())==0?1:600-out.length()));
    }
    private static void label(DataType type,int depth,int[] budget,StringBuilder out) {
        if(out.length()>=600)return;
        if(depth>=5||budget[0]--<=0){append(out,"…");return;}
        if(type instanceof DataType.RecordType r) {
            append(out,ComputerGraphPorts.typeLabel(type)+"{");int index=0;
            for(var entry:r.fields().entrySet()) {
                if(index++==6){append(out,", …");break;} if(index>1)append(out,", ");
                append(out,shorten(entry.getKey(),60)+": ");label(entry.getValue(),depth+1,budget,out);
            }
            append(out,"}");
        } else if(type instanceof DataType.ListType l){append(out,Component.translatable("create_biotech.biocomputing.tree.type.list").getString()+"<");label(l.element(),depth+1,budget,out);append(out,">");}
        else if(type instanceof DataType.MapType m){append(out,Component.translatable("create_biotech.biocomputing.tree.type.map").getString()+"<");label(m.element(),depth+1,budget,out);append(out,">");}
        else if(type instanceof DataType.OptionalType o){label(o.value(),depth+1,budget,out);append(out,"?");}
        else append(out,ComputerGraphPorts.typeLabel(type));
    }
    static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.flow_editor."+key,args);}
}
