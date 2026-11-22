package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Codec-cost v1. Each advance visits one member, scans one UTF-16 unit, emits at most
 * eight fixed bytes, or copies one final byte. No full codec is called by this engine.
 * Input and all cursor stacks are immutable; private output buffers are append-only.
 * A rejected candidate can only write beyond its predecessor's logical prefix. */
public final class SerialEncoding {
    public static final int VERSION = 1;
    private SerialEncoding() {}
    public static final class Encoded {
        private final byte[] bytes;
        private final SerialRecordCodec.Limits limits;
        private final String error;
        private Encoded(byte[] bytes, SerialRecordCodec.Limits limits, String error) { this.bytes=bytes;this.limits=limits;this.error=error; }
        byte[] bytes() { return bytes; }
        /** Immutable result view for explicitly segmented device protocols; never exposes the backing array. */
        public int size() { if(error!=null) throw new IllegalStateException(error); return bytes.length; }
        public int byteAt(int index) { if(error!=null) throw new IllegalStateException(error); return bytes[index]&255; }
        SerialRecordCodec.Limits limits() { return limits; }
        public String error() { return error; }
    }
    private record Node(int tag, Object body,int depth) {Node(int tag,Object body){this(tag,body,0);}}
    private interface Fields { int size(); String name(int i); Node node(int i); }
    private interface Items { int size(); Node node(int i); int tag(); }
    private record Field(String name, Node node) {}
    private record FixedFields(List<Field> values) implements Fields {
        public int size(){return values.size();} public String name(int i){return values.get(i).name();} public Node node(int i){return values.get(i).node();}
    }
    private record Utf16(String text) {}
    private record Numbers(int width, DataValue.ListValue values,int depth) { Numbers(int width,DataValue.ListValue values){this(width,values,0);} }
    private record Task(int kind,Object value,int index,int phase,Task next,long units) {
        Task(int kind,Object value,int index,int phase,Task next) { this(kind,value,index,phase,next,12+(next==null?0:next.units)); }
    }
    private record Visit(DataValue value,int index,DataValue.Construction refs,Visit parent,long units) {
        Visit(DataValue value,int index,DataValue.Construction refs,Visit parent) {this(value,index,refs,parent,8+value.storageUnits()+refs.storageUnits()+(parent==null?0:parent.units));}
    }
    /** One frozen logical step. Storage uses actual reserved buffers, not just written bytes. */
    public static final class State {
        private final DataValue input; private final SerialRecordCodec.Limits limits;
        private final Visit visit; private final DataValue.Construction rows;
        private final Task task; private final byte[] output, exact;
        private final int length, copied; private final long nodes, chars, steps;
        private final int archiveVersion; private final boolean done; private final String error;
        private State(DataValue input,SerialRecordCodec.Limits limits,Visit visit,DataValue.Construction rows,Task task,
                byte[] output,byte[] exact,int length,int copied,long nodes,long chars,long steps,int archiveVersion,boolean done,String error) {
            this.input=input;this.limits=limits;this.visit=visit;this.rows=rows;this.task=task;this.output=output;this.exact=exact;
            this.length=length;this.copied=copied;this.nodes=nodes;this.chars=chars;this.steps=steps;this.archiveVersion=archiveVersion;this.done=done;this.error=error;
        }
        public long steps(){return steps;} public boolean done(){return done;}
        public long storageUnits(){return 16L+(output==null?0:output.length)+(exact==null?0:exact.length)+(visit==null?0:visit.units)+(rows==null?0:rows.storageUnits())+(task==null?0:task.units);}
        /** Device workspace accounting: byte buffers plus eight bytes per non-buffer logical unit. */
        public long storageBytes(){long buffers=(output==null?0L:output.length)+(exact==null?0L:exact.length);return Math.addExact(buffers,Math.multiplyExact(Long.BYTES,storageUnits()-buffers));}
        public Encoded encoded(){if(!done)throw new IllegalStateException("Encoding unfinished");return new Encoded(exact,limits,error);}
        /** Cold replay reserves both immutable cursor generations, sharing existing byte arrays once.
         * The small frame-growth bound follows the closed transition set below; no replay allocates
         * a cursor or final byte buffer before its whole-owner reservation has succeeded. */
        public State advance(java.util.function.LongConsumer reserve) {
            if(done)throw new IllegalStateException("Encoding finished");
            // Visit descent adds 9+child units; sealing a row adds at most 17. A task
            // replaces one 12-unit frame with at most three (+24); final copy allocates length.
            long growth = visit != null ? (visit.index < children(visit.value) ?
                Math.addExact(9, child(visit.value, visit.index).storageUnits()) : 17) :
                copied >= 0 ? 0 : task == null ? length : 24;
            long sharedBuffers = (output==null?0L:output.length)+(exact==null?0L:exact.length);
            reserve.accept(Math.subtractExact(Math.addExact(Math.multiplyExact(2,storageUnits()),growth),sharedBuffers));
            return advance();
        }
        public State advance() {
            if(done)throw new IllegalStateException("Encoding finished");
            var w=new Work(this);
            try{w.advance();}catch(Invalid invalid){w.error=invalid.error;w.done=true;w.task=null;w.visit=null;w.rows=null;w.output=null;w.exact=null;}
            return w.freeze();
        }
    }
    /** Allocation-free preflight for the actual initial TX buffer and traversal controls. */
    public static State begin(DataValue input,SerialRecordCodec.Limits limits,java.util.function.LongConsumer reserve) {
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(input);
        boolean valid=(input instanceof DataValue.RecordValue || input instanceof DataValue.NbtValue)
            && input.storageUnits()<=limits.values().maxValueUnits();
        long units=valid?Math.addExact(16L+limits.maxEncodedBytes(),input instanceof DataValue.NbtValue?12:Math.addExact(10,input.storageUnits())):16;
        reserve.accept(units);
        return begin(input,limits);
    }
    public static State begin(DataValue input,SerialRecordCodec.Limits limits) {
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(input);
        Objects.requireNonNull(input);Objects.requireNonNull(limits);
        String error=input instanceof DataValue.RecordValue || input instanceof DataValue.NbtValue ? null : "invalid_record";
        if(error==null && input.storageUnits()>limits.values().maxValueUnits())error="value_limit";
        boolean nativeInput=input instanceof DataValue.NbtValue;
        return new State(input,limits,error==null&&!nativeInput?new Visit(input,0,DataValue.Construction.list(),null):null,
            error==null&&!nativeInput?DataValue.Construction.list():null,
            error==null&&nativeInput?new Task(0,nativeNode((DataValue.NbtValue)input),0,0,null):null,
            error==null?new byte[limits.maxEncodedBytes()]:null,null,0,-1,0,0,1,2,error!=null,error);
    }
    private static final class Invalid extends RuntimeException {final String error;Invalid(String error){this.error=error;}}
    private static void invalid(String error){throw new Invalid(error);}
    private static final class Work {
        final DataValue input;final SerialRecordCodec.Limits limits;
        Visit visit;DataValue.Construction rows;Task task;byte[] output,exact;int length,copied;long nodes,chars,steps;int archiveVersion;boolean done;String error;
        Work(State s){input=s.input;limits=s.limits;visit=s.visit;rows=s.rows;task=s.task;output=s.output;exact=s.exact;length=s.length;copied=s.copied;nodes=s.nodes;chars=s.chars;steps=s.steps+1;archiveVersion=s.archiveVersion;done=s.done;error=s.error;}
        State freeze(){return new State(input,limits,visit,rows,task,output,exact,length,copied,nodes,chars,steps,archiveVersion,done,error);}
        void push(int kind,Object value,int index,int phase){task=new Task(kind,value,index,phase,task);}
        void bytes(long value,int width){if(width>output.length-length)invalid("payload_too_large");for(int i=width-1;i>=0;i--)output[length++]=(byte)(value>>>(8*i));}
        void textBudget(String text){if(text.length()>limits.values().maxStringChars()-chars)invalid("value_limit");chars+=text.length();}
        void advance(){
            if(visit!=null){table();return;}
            if(copied>=0){if(copied<length){exact[copied]=output[copied];copied++;}else{done=true;output=null;rows=null;}return;}
            if(task==null){exact=new byte[length];copied=0;return;}
            var t=task;task=t.next;
            switch(t.kind){
                case 0 -> { // unnamed root; TAG_END has no name or body
                    Node n=(Node)t.value;bytes(n.tag,1);if(n.tag!=0){push(1,n,0,0);push(4,"",0,0);}
                }
                case 1 -> body((Node)t.value,t.index,t.phase);
                case 2 -> {Fields f=(Fields)t.value;if(t.index==f.size()){bytes(0,1);return;}
                    Node n=f.node(t.index);if(input instanceof DataValue.NbtValue)textBudget(f.name(t.index));push(2,f,t.index+1,0);push(1,n,0,0);push(4,f.name(t.index),0,0);bytes(n.tag,1);
                }
                case 3 -> {Items list=(Items)t.value;if(t.index<list.size()){push(3,list,t.index+1,0);push(1,list.node(t.index),0,0);}}
                case 4 -> utf((String)t.value,t.index,t.phase);
                case 5 -> {String text=((Utf16)t.value).text;if(t.index<text.length()){bytes(text.charAt(t.index),2);push(5,t.value,t.index+1,0);}}
                case 6 -> {Numbers a=(Numbers)t.value;if(t.index<a.values.values().size()){if(input instanceof DataValue.NbtValue && (a.depth>=128 || ++nodes>limits.values().maxNodes()))invalid("value_limit");bytes(((DataValue.IntegerValue)a.values.values().get(t.index)).value(),a.width);push(6,a,t.index+1,0);}}
                default -> throw new AssertionError();
            }
        }
        void table(){
            Visit v=visit;int count=children(v.value);
            if(v.index<count){DataValue child=child(v.value,v.index);visit=new Visit(child,0,DataValue.Construction.list(),new Visit(v.value,v.index+1,v.refs,v.parent));return;}
            if(++nodes>limits.values().maxNodes())invalid("value_limit");
            archiveVersion=Math.max(archiveVersion,v.value instanceof DataValue.RecipeValue?4:v.value instanceof DataValue.NbtValue?3:2);
            var refs=v.refs.finish();
            var row=new DataValue.RecordValue(Map.of("source",v.value,"refs",refs));
            int id=((DataValue.ListValue)rows.finish()).values().size();rows=rows.append(row);
            if(v.parent!=null){Visit p=v.parent;visit=new Visit(p.value,p.index,p.refs.append(new DataValue.IntegerValue(id)),p.parent);return;}
            visit=null;var table=(DataValue.ListValue)rows.finish();
            task=new Task(0,compound(new Field("Version",new Node(3,(long)archiveVersion)),new Field("Root",new Node(3,(long)id)),
                new Field("Values",new Node(9,new Items(){public int size(){return table.values().size();}public int tag(){return 10;}public Node node(int i){return row((DataValue.RecordValue)table.values().get(i));}}))),0,0,null);
        }
        void body(Node n,int index,int phase){
            boolean nativeInput=input instanceof DataValue.NbtValue;
            if(nativeInput){if(++nodes>limits.values().maxNodes() || n.depth>=128)invalid("value_limit");}
            switch(n.tag){
                case 0 -> {}
                case 1 -> bytes((long)n.body,1);
                case 2 -> bytes((long)n.body,2);
                case 3,5 -> bytes((long)n.body,4);
                case 4,6 -> bytes((long)n.body,8);
                case 7 -> {if(n.body instanceof Utf16 utf){textBudget(utf.text);bytes((long)utf.text.length()*2,4);push(5,utf,0,0);}else{Numbers a=(Numbers)n.body;bytes(a.values.values().size(),4);push(6,a,0,0);}}
                case 8 -> {if(nativeInput)textBudget((String)n.body);push(4,n.body,0,0);}
                case 9 -> {Items list=(Items)n.body;bytes(list.tag(),1);bytes(list.size(),4);push(3,list,0,0);}
                case 10 -> push(2,n.body,0,0);
                case 11,12 -> {Numbers a=(Numbers)n.body;bytes(a.values.values().size(),4);push(6,a,0,0);}
                default -> throw new AssertionError();
            }
        }
        void utf(String text,int index,int phase){
            // phase >=0 scans real code units, storing the encoded modified-UTF byte length.
            // phase <0 emits the already checked string one code unit per cycle.
            if(phase>=0){
                if(index<text.length()){char c=text.charAt(index);int bytes=c>=1&&c<=127?1:c<=2047?2:3;
                    if(phase>65535-bytes)invalid(input instanceof DataValue.NbtValue?"invalid_nbt":"invalid_record");push(4,text,index+1,phase+bytes);
                }else{bytes(phase,2);push(4,text,0,-1);}return;
            }
            if(index>=text.length())return;
            char c=text.charAt(index);
            if(c>=1&&c<=127)bytes(c,1);else if(c<=2047){bytes(0xc0|(c>>6),1);bytes(0x80|(c&63),1);}else{bytes(0xe0|(c>>12),1);bytes(0x80|((c>>6)&63),1);bytes(0x80|(c&63),1);}
            push(4,text,index+1,-1);
        }
    }
    private static int children(DataValue v){return switch(v){case DataValue.RecipeValue r->1;case DataValue.NbtValue n->1;case DataValue.ListValue l->l.values().size();case DataValue.RecordValue r->r.indexedFields().size();case DataValue.MapValue m->m.indexedEntries().size();default->0;};}
    private static DataValue child(DataValue v,int i){return switch(v){case DataValue.RecipeValue r->r.projection();case DataValue.NbtValue n->n.value();case DataValue.ListValue l->l.values().get(i);case DataValue.RecordValue r->r.indexedFields().get(i).getValue();case DataValue.MapValue m->m.indexedEntries().get(i).getValue();default->throw new AssertionError();};}
    private static Node compound(Field... fields){return new Node(10,new FixedFields(List.of(fields)));}
    private static Field kind(String kind){return new Field("Kind",new Node(8,kind));}
    private static Node row(DataValue.RecordValue row){
        DataValue source=row.field("source");var refs=(DataValue.ListValue)row.field("refs");
        return switch(source){
            case DataValue.NullValue ignored -> compound(kind("null"));
            case DataValue.BooleanValue b -> compound(kind("boolean"),new Field("Value",new Node(1,b.value()?1L:0L)));
            case DataValue.IntegerValue n -> compound(kind("integer"),new Field("Value",new Node(4,n.value())));
            case DataValue.DecimalValue n -> compound(kind("decimal"),new Field("Value",new Node(4,Double.doubleToRawLongBits(n.value()))));
            case DataValue.TextValue text -> compound(kind("text"),new Field("Value",new Node(7,new Utf16(text.value()))));
            case DataValue.ErrorValue e -> compound(kind("error"),new Field("Code",new Node(7,new Utf16(e.code()))),new Field("Message",new Node(7,new Utf16(e.message()))));
            case DataValue.LocalReference ignored -> throw new IllegalArgumentException("Local reference cannot be encoded");
            case DataValue.RecipeValue r -> compound(kind("recipe"),new Field("Fmt",new Node(8,r.fmt())),new Field("SemanticVersion",new Node(3,(long)r.version())),new Field("Value",new Node(3,((DataValue.IntegerValue)refs.values().getFirst()).value())));
            case DataValue.NbtValue n -> compound(kind("nbt"),new Field("Tag",new Node(3,(long)n.tagId())),new Field("Value",new Node(3,((DataValue.IntegerValue)refs.values().getFirst()).value())));
            case DataValue.ListValue ignored -> compound(kind("list"),new Field("Items",new Node(11,new Numbers(4,refs))));
            case DataValue.RecordValue record -> compound(kind("record"),new Field("Fields",fieldRows(record.indexedFields(),refs)));
            case DataValue.MapValue map -> compound(kind("map"),new Field("Fields",fieldRows(map.indexedEntries(),refs)));
        };
    }
    private static Node fieldRows(List<Map.Entry<String,DataValue>> fields,DataValue.ListValue refs){return new Node(9,new Items(){
        public int size(){return fields.size();}public int tag(){return 10;}public Node node(int i){return compound(new Field("Name",new Node(7,new Utf16(fields.get(i).getKey()))),new Field("Value",new Node(3,((DataValue.IntegerValue)refs.values().get(i)).value())));}
    });}
    private static Node nativeNode(DataValue.NbtValue n){return nativeNode(n,0);}
    private static Node nativeNode(DataValue.NbtValue n,int depth){return switch(n.tagId()){
        case 0 -> new Node(0,null,depth);
        case 1,2,3,4,5,6 -> new Node(n.tagId(),((DataValue.IntegerValue)n.value()).value(),depth);
        case 7,11,12 -> new Node(n.tagId(),new Numbers(n.tagId()==7?1:n.tagId()==11?4:8,(DataValue.ListValue)n.value(),depth+1),depth);
        case 8 -> new Node(8,((DataValue.TextValue)n.value()).value(),depth);
        case 9 -> {var list=(DataValue.ListValue)n.value();yield new Node(9,new Items(){public int size(){return list.values().size();}public int tag(){return size()==0?0:((DataValue.NbtValue)list.values().getFirst()).tagId();}public Node node(int i){return nativeNode((DataValue.NbtValue)list.values().get(i),depth+1);}},depth);}
        case 10 -> {var fields=((DataValue.RecordValue)n.value()).indexedFields();yield new Node(10,new Fields(){public int size(){return fields.size();}public String name(int i){return fields.get(i).getKey();}public Node node(int i){return nativeNode((DataValue.NbtValue)fields.get(i).getValue(),depth+1);}},depth);}
        default -> throw new AssertionError();
    };}
}
