package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;

/** Lossless diagnostic notation. Iterative so a nested input cannot overflow the game thread's stack. */
public final class DiagnosticValueText {
    private DiagnosticValueText() {}
    private record Quoted(String value) {}
    private record Items(Iterator<DataValue> elements) {}
    private record Fields(Iterator<Map.Entry<String,DataValue>> elements) {}
    public static Optional<String> render(DataValue value,int limit) {
        var out=new StringBuilder();var todo=new ArrayDeque<Object>();todo.push(value);
        while(!todo.isEmpty()) {
            Object next=todo.pop();
            if(next instanceof String text) {
                if (text.length()>limit-out.length())return Optional.empty();
                out.append(text);
            } else if(next instanceof Quoted q) {
                if(q.value().length()+2L>limit-out.length())return Optional.empty();
                out.append('"');
                for(int i=0;i<q.value().length();i++){
                    char c=q.value().charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);
                    else if(c=='\n')out.append("\\n");else out.append(c);
                    if(out.length()>=limit)return Optional.empty();
                }
                out.append('"');
            } else if(next instanceof Items items) {
                if(items.elements().hasNext()){
                    var item=items.elements().next();todo.push(items);
                    if(items.elements().hasNext())todo.push(",");todo.push(item);
                }
            } else if(next instanceof Fields fields) {
                if(fields.elements().hasNext()){
                    var field=fields.elements().next();todo.push(fields);
                    if(fields.elements().hasNext())todo.push(",");todo.push(field.getValue());todo.push(":");todo.push(new Quoted(field.getKey()));
                }
            } else switch((DataValue)next) {
                case DataValue.LocalReference ref -> todo.push(ref.toString());
                case DataValue.RecipeValue recipe -> {todo.push(")");todo.push(recipe.projection());todo.push("recipe@"+recipe.version()+"(");}
                case DataValue.NbtValue n -> {
                    String kind=switch(n.tagId()){case 0->"end";case 1->"byte";case 2->"short";case 3->"int";case 4->"long";case 5->"float";case 6->"double";case 7->"byte_array";case 8->"string";case 9->"list";case 10->"compound";case 11->"int_array";case 12->"long_array";default->throw new IllegalArgumentException("Unknown NBT tag");};
                    if(n.tagId()==5)todo.push(Float.toString(Float.intBitsToFloat((int)((DataValue.IntegerValue)n.value()).value())));
                    else if(n.tagId()==6)todo.push(Double.toString(Double.longBitsToDouble(((DataValue.IntegerValue)n.value()).value())));
                    else todo.push(n.value());todo.push("nbt<"+kind+">");
                }
                case DataValue.BooleanValue v -> todo.push(Boolean.toString(v.value()));
                case DataValue.IntegerValue v -> todo.push(Long.toString(v.value()));
                case DataValue.DecimalValue v -> todo.push(Double.toString(v.value()));
                case DataValue.TextValue v -> todo.push(new Quoted(v.value()));
                case DataValue.NullValue ignored -> todo.push("null");
                case DataValue.ErrorValue e -> {todo.push("}");todo.push(new Quoted(e.message()));todo.push(",message:");todo.push(new Quoted(e.code()));todo.push("error{code:");}
                case DataValue.ListValue l -> {todo.push("]");todo.push(new Items(l.values().iterator()));todo.push("[");}
                case DataValue.RecordValue r -> {todo.push("}");todo.push(new Fields(r.fields().entrySet().iterator()));todo.push("record{");}
                case DataValue.MapValue m -> {todo.push("}");todo.push(new Fields(m.entries().entrySet().iterator()));todo.push("map{");}
            }
        }
        return Optional.of(out.toString());
    }
}
