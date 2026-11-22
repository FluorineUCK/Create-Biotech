package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;

/** Immutable, resumable versioned recipe proof. One advance checks one bounded shape/member or
 * one identifier character. Retains only immutable values, no registry/world facts.
 * A proof is tied to its exact projection; finish never revalidates a large list. */
public final class RecipeValidation {
    private record Check(int kind, DataValue value, int index, int offset, Check next, int depth) {
        Check(int kind, DataValue value, int index, int offset, Check next) {
            this(kind, value, index, offset, next, 1 + (next == null ? 0 : next.depth));
        }
    }
    private final DataValue.RecordValue projection;
    private final Check pending;
    private final int version;
    int version() { return version; }
    private RecipeValidation(int version, DataValue.RecordValue projection, Check pending) { this.version=version; this.projection=projection; this.pending=pending; }
    public static RecipeValidation begin(String fmt, int version, DataValue.RecordValue projection) {
        DataType.Semantic.recipe(fmt,version);
        return new RecipeValidation(version,Objects.requireNonNull(projection), new Check(0,projection,0,0,null));
    }
    public boolean done() { return pending == null; }
    public long storageUnits() { return 4L + projection.storageUnits() + 6L*(pending==null?0:pending.depth); }
    public DataValue.RecordValue projection() { if (!done()) throw new IllegalStateException("Unfinished recipe proof"); return projection; }
    public DataValue.RecipeValue finish() { return new DataValue.RecipeValue(this); }
    /** Untrusted control descriptors only. No method accepts these as a sealing witness;
     * an owner must reconstruct/replay the proof from its exact immutable source. */
    public DataValue.ListValue controls() {
        var frames=new ArrayList<DataValue>();
        for(var c=pending;c!=null;c=c.next) frames.add(new DataValue.RecordValue(Map.of(
            "kind",new DataValue.IntegerValue(c.kind),"index",new DataValue.IntegerValue(c.index),"offset",new DataValue.IntegerValue(c.offset))));
        return new DataValue.ListValue(frames);
    }
    public long controlsStorageUnits() { return 1L+22L*(pending==null?0:pending.depth); }
    public RecipeValidation advance() {
        if (done()) throw new IllegalStateException("Recipe proof complete");
        Check c=pending, next=c.next;
        switch(c.kind) {
            case 0 -> {
                var r=shape(c.value,"page","pages","pattern_id","type","inputs","outputs","body","priority","fingerprint","source");
                long page=integer(r.field("page")), pages=integer(r.field("pages"));
                if (page<0 || pages<=page) throw bad();
                String id=text(r.field("pattern_id"));
                if (id.length()!=36) throw bad();
                next=new Check(8,r.field("pattern_id"),0,0,next);
                String fingerprint=text(r.field("fingerprint"));
                if(fingerprint.length()!=64) throw bad();
                next=new Check(7,r.field("fingerprint"),0,0,next);
                if(r.field("priority")!=DataValue.NullValue.INSTANCE) {
                    long priority=integer(r.field("priority"));
                    if(priority<Integer.MIN_VALUE || priority>Integer.MAX_VALUE) throw bad();
                }
                compound(r.field("source"));
                String kind=text(r.field("type"));
                var outputs=list(r.field("outputs"));
                if(outputs.values().isEmpty()) throw bad();
                next=new Check(2,outputs,0,0,next);
                next=new Check(1,list(r.field("inputs")),0,0,next);
                if(kind.equals("standard")) {
                    if(version==2 && r.field("body") instanceof DataValue.RecordValue body && body.fields().containsKey("crafting_slots")) {
                        text(shape(body,"address","crafting_slots").field("address"));
                        var slots=list(body.field("crafting_slots"));
                        int n=slots.values().size();
                        if(n<1 || n>9 || n!=list(r.field("inputs")).values().size()) throw bad();
                        next=new Check(10,slots,0,0,next);
                    } else text(shape(r.field("body"),"address").field("address"));
                }
                else if(kind.equals("sequenced")) {
                    var stages=list(shape(r.field("body"),"stages").field("stages"));
                    if(stages.values().isEmpty()) throw bad();
                    next=new Check(3,stages,0,0,next);
                } else throw bad();
            }
            case 1,2,3 -> {
                var values=list(c.value).values();
                if(c.index<values.size()) {
                    next=new Check(c.kind,c.value,c.index+1,0,next);
                    next=new Check(c.kind+3,values.get(c.index),c.index,0,next);
                }
            }
            case 4 -> {
                var r=shape(c.value,"item","tag","count","components");
                boolean item=r.field("item")!=DataValue.NullValue.INSTANCE, tag=r.field("tag")!=DataValue.NullValue.INSTANCE;
                if(item==tag || integer(r.field("count"))<=0) throw bad();
                compound(r.field("components"));
                next=new Check(9,r.field(item?"item":"tag"),0,0,next);
            }
            case 5 -> {
                var r=shape(c.value,"item","count","components");
                if(integer(r.field("count"))<=0) throw bad();
                compound(r.field("components"));
                next=new Check(9,r.field("item"),0,0,next);
            }
            case 6 -> {
                var r=shape(c.value,"index","address","inputs");
                if(integer(r.field("index"))!=c.index) throw bad();
                text(r.field("address"));
                next=new Check(1,list(r.field("inputs")),0,0,next);
            }
            case 7,8 -> {
                String s=text(c.value);
                if(c.index<s.length()) {
                    char ch=s.charAt(c.index);
                    boolean dash=c.kind==8 && (c.index==8||c.index==13||c.index==18||c.index==23);
                    if(dash ? ch!='-' : !(ch>='0'&&ch<='9'||ch>='a'&&ch<='f')) throw bad();
                    next=new Check(c.kind,c.value,c.index+1,0,next);
                }
            }
            case 9 -> {
                String s=text(c.value);
                if(c.index==s.length()) {
                    if(c.offset==0 || c.offset==s.length()) throw bad();
                } else {
                    char ch=s.charAt(c.index); int colon=c.offset;
                    if(ch==':') { if(colon!=0||c.index==0) throw bad(); colon=c.index+1; }
                    else if(!(ch>='a'&&ch<='z'||ch>='0'&&ch<='9'||ch=='_'||ch=='-'||ch=='.'||ch=='/'&&colon!=0)) throw bad();
                    next=new Check(9,c.value,c.index+1,colon,next);
                }
            }
            case 10 -> {
                var slots=list(c.value).values();
                long slot=integer(slots.get(c.index));
                if(slot<0 || slot>8 || (c.offset & (1 << (int)slot))!=0) throw bad();
                var input=list(projection.field("inputs")).values().get(c.index);
                if(!(input instanceof DataValue.RecordValue row) || integer(row.fields().get("count"))!=1) throw bad();
                if(c.index+1<slots.size()) next=new Check(10,c.value,c.index+1,c.offset | (1 << (int)slot),next);
            }
            default -> throw new AssertionError();
        }
        return new RecipeValidation(version,projection,next);
    }
    private static DataValue.RecordValue shape(DataValue value,String... fields) {
        if(!(value instanceof DataValue.RecordValue r)||r.fields().size()!=fields.length) throw bad();
        for(String name:fields) if(!r.fields().containsKey(name)) throw bad();
        return r;
    }
    private static DataValue.ListValue list(DataValue v) { if(v instanceof DataValue.ListValue l)return l;throw bad(); }
    private static String text(DataValue v) { if(v instanceof DataValue.TextValue t)return t.value();throw bad(); }
    private static long integer(DataValue v) { if(v instanceof DataValue.IntegerValue n)return n.value();throw bad(); }
    private static void compound(DataValue v) { if(!(v instanceof DataValue.NbtValue n)||n.tagId()!=10)throw bad(); }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Invalid recipe projection"); }
}
