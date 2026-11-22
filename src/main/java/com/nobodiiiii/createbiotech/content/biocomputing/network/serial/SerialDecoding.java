package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Deterministic RX codec work v1: at most eight fixed bytes, one modified/raw UTF-16
 * unit, one named-tree comparison/rebuild, one reference/member validation per step.
 * No Minecraft tags or synchronous codecs. Append-only character buffers retain paid
 * prefixes. String sealing is the explicitly charged, frame-bounded Java O(n) boundary;
 * it is not a claim that all host allocations or owner archive scans are O(1). */
public final class SerialDecoding {
    /** Borrowed immutable bytes; no transport ownership, dequeue or acknowledgement is implied. */
    public interface Input { int size(); int byteAt(int index); }
    public static final int VERSION=1;
    private static final int MAX_KIND_CHARS=7; // longest closed kind: boolean/integer/decimal
    private SerialDecoding() {}
    public static final class Decoded {
        private final DataValue value;private final String error;
        private Decoded(DataValue value,String error){this.value=value;this.error=error;}
        public DataValue value(){return value;}public String error(){return error;}
    }
    private record Task(int kind,int tag,int depth,int phase,int index,Object data,Task next,long units,long bufferUnits) {
        Task(int kind,int tag,int depth,int phase,int index,Object data,Task next){this(kind,tag,depth,phase,index,data,next,
            16+owned(data)+(next==null?0:next.units),16+bufferOwned(data)+(next==null?0:next.bufferUnits));}
    }
    private record Sequence(int count,int child,DataValue.Construction prefix) {}
    private record Named(DataValue.NamedConstruction prefix,String key,DataValue.NamedConstruction.Insertion insertion,long borrowed) {
        Named(DataValue.NamedConstruction prefix,String key,DataValue.NamedConstruction.Insertion insertion){this(prefix,key,insertion,0);}
    }
    private record Text(char[] chars,int count,int encoded,int offset,int end,int seal,int hash,DataValue.ListValue raw) {}
    private record Table(DataValue.ListValue rows,int version,int row,DataValue.Construction values,long allocations) {}
    private record Row(Table table,DataValue.RecordValue source,String kind,int index,DataValue.Construction list,DataValue.NamedConstruction named,long borrowed) {}
    private static long owned(Object data){
        if(data instanceof Object[] pair){long units=0;for(Object item:pair)units=Math.addExact(units,owned(item));return units;}
        if(data instanceof Sequence s)return s.prefix.storageUnits();
        if(data instanceof Named n)return n.prefix.storageUnits()+ (n.key==null?0:n.key.length())+32L*(n.prefix.size()+1)+(n.insertion==null?0:n.insertion.storageUnits());
        if(data instanceof String text)return 1L+text.length();
        if(data instanceof Text t)return 2L*t.chars.length+(t.raw==null?0:t.raw.storageUnits());
        if(data instanceof Table t)return t.rows.storageUnits()+t.values.storageUnits();
        if(data instanceof Row r)return owned(r.table)+r.source.storageUnits()+(r.list==null?0:r.list.storageUnits())+(r.named==null?0:r.named.storageUnits()+32L*r.named.size());
        if(data instanceof DataValue v)return v.storageUnits();
        if(data instanceof RecipeValidation p)return p.storageUnits();
        if(data instanceof DataValue.NativeValidation p)return p.storageUnits();
        return 0;
    }
    /** Device workspace v2: table members borrow already decoded immutable values instead of cloning them.
     * Counters are updated in the existing paid member steps, never by walking the retained object graph.
     * Legacy logical-value accounting above is unchanged for existing ISA/serial continuations. */
    private static long bufferOwned(Object data) {
        if(data instanceof Object[] pair){long units=0;for(Object item:pair)units=Math.addExact(units,bufferOwned(item));return units;}
        if(data instanceof Table t)return t.rows.storageUnits()+t.allocations;
        // source is the current row of table.rows, not an independently owned record.
        // Completed members reference table.values; charge their local slots/tree, not the values again.
        if(data instanceof Row r)return bufferOwned(r.table)
            +(r.list==null?0:r.list.storageUnits())+(r.named==null?0:r.named.storageUnits()+32L*r.named.size())
            -r.borrowed+(r.kind.equals("list")?r.index:0);
        // rawText only borrows bytes from the Row retained by its continuation. UTF buffers are still owned.
        if(data instanceof Text t && t.raw!=null)return 2L*t.chars.length;
        if(data instanceof Named n)return 32L*(n.prefix.size()+1)+(n.insertion==null
            ?n.prefix.storageUnits()+(n.key==null?0:n.key.length()):n.insertion.storageUnits())-n.borrowed;
        return owned(data);
    }
    public static final class State {
        private final Input head;private final boolean nativeNbt;private final SerialRecordCodec.Limits limits;
        private final Task task;private final int offset;private final long heap,nodes,chars,steps,witness,kindDispatchChars;
        private final DataValue last;private final String text,error;private final boolean done;
        private State(Input head,boolean nativeNbt,SerialRecordCodec.Limits limits,Task task,int offset,long heap,long nodes,long chars,long steps,long witness,long kindDispatchChars,DataValue last,String text,String error,boolean done){this.head=head;this.nativeNbt=nativeNbt;this.limits=limits;this.task=task;this.offset=offset;this.heap=heap;this.nodes=nodes;this.chars=chars;this.steps=steps;this.witness=witness;this.kindDispatchChars=kindDispatchChars;this.last=last;this.text=text;this.error=error;this.done=done;}
        public long steps(){return steps;}public int consumedBytes(){return offset;}public long parsedNodes(){return nodes;}public long decodedChars(){return chars;}
        public long wireWitness(){return witness;} public long maxKindDispatchChars(){return kindDispatchChars;}
        /** Conservative parser allocation charge, distinct from live typed-container units. */
        public long parserAllocatedBytes(){return heap;}
        public boolean done(){return done;}
        public long storageUnits(){return 24+(task==null?0:task.units)+(last==null?0:last.storageUnits())+(text==null?0:text.length());}
        public long bufferStorageUnits(){return 24+(task==null?0:task.bufferUnits)
            +(last==null||task!=null&&task.kind==7&&task.data==last?0:last.storageUnits())+(text==null?0:text.length());}
        public Decoded decoded(){if(!done)throw new IllegalStateException("Receive decode unfinished");return new Decoded(last,error);}
        public State advance(){if(done)throw new IllegalStateException("Receive decode complete");var w=new Work(this);try{w.advance();}catch(Invalid e){w.error=e.error;w.done=true;w.task=null;w.last=null;w.text=null;}catch(IllegalArgumentException e){w.error=nativeNbt?"invalid_nbt":"invalid_record";w.done=true;w.task=null;w.last=null;w.text=null;}catch(ComputationException e){w.error="value_limit";w.done=true;w.task=null;w.last=null;w.text=null;}return w.freeze();}
    }
    public static State begin(Input head,boolean nativeNbt,SerialRecordCodec.Limits limits){Objects.requireNonNull(head);Objects.requireNonNull(limits);String error=head.size()>limits.maxEncodedBytes()?"payload_too_large":null;return new State(head,nativeNbt,limits,error==null?new Task(0,0,0,0,0,null,null):null,0,0,0,0,1,0,0,null,null,error,error!=null);}
    private static final class Invalid extends RuntimeException {final String error;Invalid(String error){this.error=error;}}
    private static void invalid(String error){throw new Invalid(error);}
    private static final class Work {
        final Input head;final boolean nativeNbt;final SerialRecordCodec.Limits limits;
        Task task;int offset;long heap,nodes,chars,steps,witness,kindDispatchChars;DataValue last;String text,error;boolean done;
        Work(State s){head=s.head;nativeNbt=s.nativeNbt;limits=s.limits;task=s.task;offset=s.offset;heap=s.heap;nodes=s.nodes;chars=s.chars;steps=s.steps+1;witness=s.witness;kindDispatchChars=s.kindDispatchChars;last=s.last;text=s.text;error=s.error;done=s.done;}
        State freeze(){return new State(head,nativeNbt,limits,task,offset,heap,nodes,chars,steps,witness,kindDispatchChars,last,text,error,done);}
        void push(int kind,int tag,int depth,int phase,int index,Object data){task=new Task(kind,tag,depth,phase,index,data,task);}
        String invalidCode(){return nativeNbt?"invalid_nbt":"invalid_record";}
        long read(int width){if(width>head.size()-offset)invalid(invalidCode());long n=0;for(int i=0;i<width;i++){int b=head.byteAt(offset++);n=(n<<8)|b;witness=witness*0x100000001b3L+(b+1);}return n;}
        int length(){int n=(int)read(4);if(n<0)invalid(invalidCode());return n;}
        void heap(long n){if(n<0||n>limits.maxDecodeHeapBytes()-heap)invalid("decode_limit");heap+=n;}
        void node(int depth){if(nativeNbt&&(depth>=128||++nodes>limits.values().maxNodes()))invalid("value_limit");if(!nativeNbt&&depth>=512)invalid("decode_limit");}
        void characters(int n){if(n>limits.values().maxStringChars()-chars)invalid("value_limit");chars+=n;}
        void valueLimit(DataValue value){if(value.storageUnits()>limits.values().maxValueUnits())invalid("value_limit");}
        void validate(int tag,DataValue body){push(6,tag,0,0,0,DataValue.NativeValidation.begin(tag,body));}
        void utf(int purpose,int tag,int depth){int n=(int)read(2);if(n>head.size()-offset)invalid(invalidCode());push(4,tag,depth,purpose,0,new Text(new char[n],0,0,offset,offset+n,0,0,null));}
        void rawText(DataValue.NbtValue value){if(value.tagId()!=7)invalid("invalid_record");var bytes=(DataValue.ListValue)value.value();int count=bytes.values().size();if((count&1)!=0)invalid("invalid_record");characters(count/2);push(4,0,0,3,0,new Text(new char[count/2],0,0,0,count,0,0,bytes));}
        void advance(){
            if(task==null){if(offset!=head.size())invalid(invalidCode());if(nativeNbt){valueLimit(last);done=true;}else push(7,0,0,0,0,last);return;}
            var t=task;task=t.next;
            switch(t.kind){
                case 0->{int id=(int)read(1);if(id>12||!nativeNbt&&id!=10)invalid(invalidCode());heap(1);if(id==0){node(0);last=new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE);}else{heap(4);push(1,id,0,0,0,null);utf(0,id,0);}}
                case 1->body(t.tag,t.depth);
                case 2->sequence(t);
                case 3->compound(t);
                case 4->text(t);
                case 5->{var n=(Named)t.data;var insertion=n.insertion;if(!insertion.done()){push(5,t.tag,t.depth,t.phase,t.index,new Named(n.prefix,n.key,insertion.advance()));return;}var next=insertion.finish();if(next.size()>n.prefix.size())heap(36);push(3,t.tag,t.depth,0,0,new Named(next,null,null));last=null;text=null;}
                case 6->{var p=(DataValue.NativeValidation)t.data;if(!p.done()){push(6,t.tag,0,0,0,p.advance());return;}last=p.finish();if(nativeNbt)valueLimit(last);}
                case 7->tableStart((DataValue.NbtValue)t.data);
                case 8->table((Table)t.data);
                case 9->row((Row)t.data,t.phase);
                case 10->{var r=(Row)t.data;var child=reference(r.table,(int)number(field(((DataValue.NbtValue)((DataValue.ListValue)field(r.source,"Fields").value()).values().get(r.index)).value(),"Value"),3));
                    // Both aliases remain owned by the retained Row/table for the whole insertion.
                    var named=new Named(r.named,text,r.named.insert(text,child,false),Math.addExact(r.named.storageUnits(),child.storageUnits()));
                    push(11,0,0,0,0,new Object[]{r,named});}
                case 11->{var pair=(Object[])t.data;var r=(Row)pair[0];var n=(Named)pair[1];if(!n.insertion.done()){push(11,0,0,0,0,new Object[]{r,new Named(n.prefix,n.key,n.insertion.advance(),n.borrowed)});return;}var next=n.insertion.finish();long borrowed=next.storageUnits()-r.named.storageUnits()-1-n.key.length();push(9,0,0,1,0,new Row(r.table,r.source,r.kind,r.index+1,r.list,next,Math.addExact(r.borrowed,borrowed)));text=null;}
                case 12->{var r=(Row)t.data;finishRow(r.table,new DataValue.TextValue(text));text=null;}
                case 17->{var r=(Row)t.data;String code=text;push(14,0,0,0,0,new Object[]{r,code});rawText(field(r.source,"Message"));}
                case 14->{var pair=(Object[])t.data;var r=(Row)pair[0];finishRow(r.table,new DataValue.ErrorValue((String)pair[1],text));text=null;}
                case 18->{var pair=(Object[])t.data;var table=(Table)pair[0];var proof=(RecipeValidation)pair[1];if(!proof.done())push(18,0,0,0,0,new Object[]{table,proof.advance()});else finishRow(table,proof.finish());}
                case 15->{var pair=(Object[])t.data;var table=(Table)pair[0];var proof=(DataValue.NativeValidation)pair[1];if(!proof.done())push(15,0,0,0,0,new Object[]{table,proof.advance()});else finishRow(table,proof.finish(),2);}
                default->throw new AssertionError("RX task "+t.kind);
            }
        }
        void body(int tag,int depth){node(depth);switch(tag){
            case 1,2,3,4,5,6->{int width=tag==1?1:tag==2?2:tag==3||tag==5?4:8;heap(tag==1?9:tag==2?10:width==4?12:16);long n=read(width);if(width==1)n=(byte)n;else if(width==2)n=(short)n;else if(width==4)n=(int)n;last=new DataValue.NbtValue(tag,new DataValue.IntegerValue(n));}
            case 8->{heap(36);utf(2,tag,depth);}
            case 7,9,11,12->{int child=tag==9?(int)read(1):tag==7?1:tag==11?3:4;int n=length();if(child>12||child==0&&n>0)invalid(invalidCode());heap(tag==9?37+4L*n:24+(tag==7?1L:tag==11?4L:8L)*n);if(n>head.size()-offset && tag!=9)invalid(invalidCode());if(nativeNbt&&n>limits.values().maxNodes()-nodes)invalid("value_limit");push(2,tag,depth,0,0,new Sequence(n,child,DataValue.Construction.list()));}
            case 10->{heap(48);push(3,tag,depth,0,0,new Named(DataValue.NamedConstruction.empty(),null,null));}
            default->invalid(invalidCode());
        }}
        void sequence(Task t){var s=(Sequence)t.data;if(t.phase==1){var prefix=s.prefix.append(last);last=null;push(2,t.tag,t.depth,0,t.index+1,new Sequence(s.count,s.child,prefix));return;}if(t.index==s.count){validate(t.tag,s.prefix.finish());return;}push(2,t.tag,t.depth,1,t.index,s);if(t.tag==9)push(1,s.child,t.depth+1,0,0,null);else{node(t.depth+1);int width=t.tag==7?1:t.tag==11?4:8;long n=read(width);if(width==1)n=(byte)n;else if(width==4)n=(int)n;last=new DataValue.IntegerValue(n);}}
        void compound(Task t){var n=(Named)t.data;if(t.phase==1){String name=text;text=null;push(3,t.tag,t.depth,2,0,new Named(n.prefix,name,null));push(1,t.index,t.depth+1,0,0,null);return;}if(t.phase==2){push(5,t.tag,t.depth,0,0,new Named(n.prefix,n.key,n.prefix.insert(n.key,last,true)));last=null;return;}heap(2);int id=(int)read(1);if(id>12)invalid(invalidCode());if(id==0){validate(10,n.prefix.record());return;}heap(4);push(3,t.tag,t.depth,1,id,n);utf(1,id,t.depth);}
        void text(Task t){var v=(Text)t.data;int position=t.index;
            if(t.phase==3&&position<v.end){int a=(int)((DataValue.IntegerValue)v.raw.values().get(position)).value()&255;int b=(int)((DataValue.IntegerValue)v.raw.values().get(position+1)).value()&255;v.chars[v.count]=(char)(a<<8|b);push(4,0,0,3,position+2,new Text(v.chars,v.count+1,0,0,v.end,0,0,v.raw));return;}
            if(t.phase!=3&&offset<v.end){int a=(int)read(1),c;switch(a>>4){case 0,1,2,3,4,5,6,7->c=a;case 12,13->{if(offset>=v.end)invalid(invalidCode());int b=(int)read(1);if((b&192)!=128)invalid(invalidCode());c=(a&31)<<6|(b&63);}case 14->{if(v.end-offset<2)invalid(invalidCode());int b=(int)read(1),d=(int)read(1);if((b&192)!=128||(d&192)!=128)invalid(invalidCode());c=(a&15)<<12|(b&63)<<6|(d&63);}default->{invalid(invalidCode());return;}}
                v.chars[v.count]=(char)c;int encoded=c>=1&&c<=127?1:c<=2047?2:3;push(4,t.tag,t.depth,t.phase,position,new Text(v.chars,v.count+1,v.encoded+encoded,v.offset,v.end,0,0,null));return;}
            // Real paid second character pass computes a deterministic hash and accounts String materialization.
            if(v.seal<v.count){int hash=31*v.hash+v.chars[v.seal];push(4,t.tag,t.depth,t.phase,position,new Text(v.chars,v.count,v.encoded,v.offset,v.end,v.seal+1,hash,v.raw));return;}
            text=new String(v.chars,0,v.count);
            if(t.phase!=3){heap(2L+v.encoded);if(nativeNbt&&t.phase!=0)characters(v.count);}
            if(t.phase==2){last=new DataValue.NbtValue(8,new DataValue.TextValue(text));text=null;}
        }
        DataValue.NbtValue field(DataValue body,String name){if(!(body instanceof DataValue.RecordValue r)||!(r.fields().get(name) instanceof DataValue.NbtValue n))invalid("invalid_record");return (DataValue.NbtValue)((DataValue.RecordValue)body).fields().get(name);}
        long number(DataValue.NbtValue n,int tag){if(n.tagId()!=tag||!(n.value() instanceof DataValue.IntegerValue))invalid("invalid_record");return ((DataValue.IntegerValue)n.value()).value();}
        void tableStart(DataValue.NbtValue root){if(root.tagId()!=10)invalid("invalid_record");int version=(int)number(field(root.value(),"Version"),3);if(version<1||version>4)invalid("unsupported_version");var rowsTag=field(root.value(),"Values");if(rowsTag.tagId()!=9)invalid("invalid_record");var rows=(DataValue.ListValue)rowsTag.value();if(rows.values().isEmpty()||rows.values().size()>limits.values().maxNodes())invalid("value_limit");if(number(field(root.value(),"Root"),3)!=rows.values().size()-1)invalid("invalid_record");last=null;push(8,0,0,0,0,new Table(rows,version,0,DataValue.Construction.list(),1));}
        DataValue reference(Table t,int ref){if(ref<0||ref>=t.row)invalid("invalid_record");return ((DataValue.ListValue)t.values.finish()).values().get(ref);}
        void finishRow(Table t,DataValue value){finishRow(t,value,value.storageUnits());}
        void finishRow(Table t,DataValue value,long allocations){valueLimit(value);if(allocations<1)invalid("invalid_record");push(8,0,0,0,0,new Table(t.rows,t.version,t.row+1,t.values.append(value),Math.addExact(t.allocations,Math.addExact(allocations,1))));last=null;}
        void table(Table t){if(t.row==t.rows.values().size()){last=((DataValue.ListValue)t.values.finish()).values().get(t.row-1);if(!(last instanceof DataValue.RecordValue))invalid("invalid_record");done=true;return;}var n=(DataValue.NbtValue)t.rows.values().get(t.row);if(n.tagId()!=10)invalid("invalid_record");var source=(DataValue.RecordValue)n.value();var kind=field(source,"Kind");if(kind.tagId()!=8)invalid("invalid_record");String name=((DataValue.TextValue)kind.value()).value();push(9,0,0,0,0,new Row(t,source,name,0,null,null,0));}
        void row(Row r,int phase){if(phase==1){members(r);return;}if(r.kind.length()>MAX_KIND_CHARS)invalid("invalid_record");kindDispatchChars=Math.max(kindDispatchChars,r.kind.length());switch(r.kind){
            case "null"->finishRow(r.table,DataValue.NullValue.INSTANCE);
            case "boolean"->{long n=number(field(r.source,"Value"),1);if(n!=0&&n!=1)invalid("invalid_record");finishRow(r.table,new DataValue.BooleanValue(n==1));}
            case "integer"->finishRow(r.table,new DataValue.IntegerValue(number(field(r.source,"Value"),4)));
            case "decimal"->{long n=number(field(r.source,"Value"),r.table.version==1?6:4);finishRow(r.table,new DataValue.DecimalValue(Double.longBitsToDouble(n)));}
            case "text"->{push(12,0,0,0,0,r);rawText(field(r.source,"Value"));}
            case "error"->{push(17,0,0,0,0,r);rawText(field(r.source,"Code"));}
            case "list","record","map"->{var list=field(r.source,r.kind.equals("list")?"Items":"Fields");if(list.tagId()!=(r.kind.equals("list")?11:9))invalid("invalid_record");int count=((DataValue.ListValue)list.value()).values().size();if(count>=limits.values().maxValueUnits())invalid("value_limit");push(9,0,0,1,0,new Row(r.table,r.source,r.kind,0,DataValue.Construction.list(),DataValue.NamedConstruction.empty(),0));}
            case "recipe"->{if(r.table.version<4)invalid("invalid_record");var fmt=field(r.source,"Fmt");if(fmt.tagId()!=8)invalid("invalid_record");var body=reference(r.table,(int)number(field(r.source,"Value"),3));if(!(body instanceof DataValue.RecordValue))invalid("invalid_record");push(18,0,0,0,0,new Object[]{r.table,RecipeValidation.begin(((DataValue.TextValue)fmt.value()).value(),(int)number(field(r.source,"SemanticVersion"),3),(DataValue.RecordValue)body)});}
            case "nbt"->{if(r.table.version<3)invalid("invalid_record");int tag=(int)number(field(r.source,"Tag"),3);DataValue body=reference(r.table,(int)number(field(r.source,"Value"),3));push(15,0,0,0,0,new Object[]{r.table,DataValue.NativeValidation.begin(tag,body)});}
            default->invalid("invalid_record");
        }}
        void members(Row r){boolean list=r.kind.equals("list");var entries=(DataValue.ListValue)field(r.source,list?"Items":"Fields").value();if(r.index==entries.values().size()){var value=list?r.list.finish():r.kind.equals("record")?r.named.record():r.named.mapValue();finishRow(r.table,value,value.storageUnits()-r.borrowed+(list?1L:33L)*entries.values().size());return;}if(list){int ref=(int)((DataValue.IntegerValue)entries.values().get(r.index)).value();var child=reference(r.table,ref);push(9,0,0,1,0,new Row(r.table,r.source,r.kind,r.index+1,r.list.append(child),r.named,Math.addExact(r.borrowed,child.storageUnits())));return;}var member=(DataValue.NbtValue)entries.values().get(r.index);if(member.tagId()!=10)invalid("invalid_record");push(10,0,0,0,0,r);rawText(field(member.value(),"Name"));}
    }
}
