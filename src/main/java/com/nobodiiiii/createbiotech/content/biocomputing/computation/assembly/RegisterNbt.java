package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.*;

/** Concrete native data operations, not an ANY register or semantic-format exemption. */
public final class RegisterNbt {
    private RegisterNbt() {}
    private static long sum(long... parts) {long result=0;for(long part:parts)result=Math.addExact(result,part);return result;}
    public enum Kind { TAG, LENGTH, CHILD, KEY, READ_TEXT, READ_INTEGER, PACK, ENCODE_VALUE, DECODE_VALUE, JOIN_BYTES, DECODE_UNITS }
    static DataType decodedType(DataType type) {
        if(!(type instanceof DataType.RecordType r) || !r.fields().keySet().equals(Set.of("code","value"))
            || r.fields().get("code")!=DataType.Scalar.TEXT || !(r.fields().get("value") instanceof DataType.OptionalType))
            throw new IllegalArgumentException("VALUE.DECODE destination must declare {code:TEXT,value:OPTIONAL T}");
        return ((DataType.OptionalType)r.fields().get("value")).value();
    }
    public static final DataType.RecordType ENTRY = new DataType.RecordType(Map.of("key",DataType.Scalar.TEXT,"value",DataType.Scalar.NBT));
    public static DataType checked(DataType type) { return new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(type))); }
    public record Instruction(Kind kind,int to,int from,int operand) implements RegisterProgram.Instruction {
        public Instruction { Objects.requireNonNull(kind); }
        public boolean encoding() { return kind==Kind.ENCODE_VALUE; }
        public boolean decoding() { return kind==Kind.DECODE_VALUE || kind==Kind.DECODE_UNITS; }
        public boolean unitWorkspace() { return kind==Kind.DECODE_UNITS; }
        public boolean joining() { return kind==Kind.JOIN_BYTES; }
        public boolean codec() { return encoding() || decoding(); }
        public boolean indexed() { return kind==Kind.CHILD || kind==Kind.KEY || codec() || joining(); }
        public boolean packing() { return kind==Kind.PACK && Set.of(7,9,10,11,12).contains(operand); }
        public int words() { return indexed() || kind==Kind.PACK ? 4 : 3; }
        public List<Integer> reads() { return indexed()?List.of(from,operand):List.of(from); }
        public Instruction remap(Map<Integer,Integer> map) { return new Instruction(kind,map.get(to),map.get(from),indexed()?map.get(operand):operand); }
        public void verify(IntFunction<DataType> type) {
            DataType source=DataType.Scalar.NBT;
            DataType target=switch(kind) {
                case TAG,LENGTH -> DataType.Scalar.INTEGER;
                case CHILD,PACK,ENCODE_VALUE,JOIN_BYTES -> DataType.Scalar.NBT;
                case KEY -> DataType.Scalar.TEXT;
                case READ_INTEGER -> checked(DataType.Scalar.INTEGER);
                case READ_TEXT -> checked(DataType.Scalar.TEXT);
                case DECODE_VALUE,DECODE_UNITS -> { decodedType(type.apply(to)); yield type.apply(to); }
            };
            if(kind==Kind.PACK)source=bodyType(operand);
            if(encoding())source=type.apply(from);
            if(joining())source=new DataType.ListType(DataType.Scalar.NBT);
            if(!type.apply(from).equals(source) || !type.apply(to).equals(target) || indexed() && type.apply(operand)!=DataType.Scalar.INTEGER)
                throw new IllegalArgumentException("NBT."+kind+" type mismatch");
            if(!indexed() && kind!=Kind.PACK && operand!=-1)throw new IllegalArgumentException("Unexpected NBT operand");
        }
        public DataValue execute(RegisterState state,LongConsumer reserve) {
            if(codec() || joining())throw new IllegalStateException("Byte processing requires its incremental continuation");
            var raw=state.registers().get(from);
            if(kind==Kind.PACK) { reserve.accept(sum(1,raw.storageUnits())); return new DataValue.NbtValue(operand,raw); }
            var nativeValue=(DataValue.NbtValue)raw;int tag=nativeValue.tagId();var body=nativeValue.value();
            if(kind==Kind.TAG) {reserve.accept(1);return new DataValue.IntegerValue(tag);}
            if(kind==Kind.READ_TEXT || kind==Kind.READ_INTEGER) {
                boolean ok=kind==Kind.READ_TEXT?tag==8:tag>=1 && tag<=6;var value=ok?body:DataValue.NullValue.INSTANCE;
                reserve.accept(sum(11,value.storageUnits()));
                return new DataValue.RecordValue(Map.of("ok",new DataValue.BooleanValue(ok),"value",value));
            }
            int size=size(nativeValue);
            if(kind==Kind.LENGTH) {reserve.accept(1);return new DataValue.IntegerValue(size);}
            if(kind==Kind.KEY && tag!=10)throw fault(ComputationException.Code.TYPE_MISMATCH,"NBT.KEY needs compound");
            long index=((DataValue.IntegerValue)state.registers().get(operand)).value();
            if(index<0 || index>=size)throw fault(ComputationException.Code.INDEX_OUT_OF_BOUNDS,"Native child index out of bounds");
            if(kind==Kind.KEY) {
                String key=((DataValue.RecordValue)body).indexedFields().get((int)index).getKey();reserve.accept(1L+key.length());return new DataValue.TextValue(key);
            }
            var child=tag==10?((DataValue.RecordValue)body).indexedFields().get((int)index).getValue():((DataValue.ListValue)body).values().get((int)index);
            boolean array=tag==7 || tag==11 || tag==12;
            reserve.accept(sum(child.storageUnits(),array?1:0));
            return array?new DataValue.NbtValue(tag==7?1:tag==11?3:4,child):child;
        }
    }
    static DataType bodyType(int tag) {
        return switch(tag) {
            case 0 -> DataType.Scalar.NULL;
            case 1,2,3,4,5,6 -> DataType.Scalar.INTEGER;
            case 8 -> DataType.Scalar.TEXT;
            case 7,11,12 -> new DataType.ListType(DataType.Scalar.INTEGER);
            case 9 -> new DataType.ListType(DataType.Scalar.NBT);
            case 10 -> new DataType.ListType(ENTRY);
            default -> throw new IllegalArgumentException("Unknown native tag");
        };
    }
    private static int size(DataValue.NbtValue value) {
        return switch(value.tagId()) {
            case 7,9,11,12 -> ((DataValue.ListValue)value.value()).values().size();
            case 10 -> ((DataValue.RecordValue)value.value()).indexedFields().size();
            default -> throw fault(ComputationException.Code.TYPE_MISMATCH,"Native container required");
        };
    }
    static ComputationException fault(ComputationException.Code code,String message) { return new ComputationException(code,"assembly",message); }

    /** Immutable private construction state. Wire records contain only tag and paid cursor. */
    static final class Build {
        final int tag,index;
        final DataValue.ListValue input;
        final DataValue.NamedConstruction named;
        final DataValue.NamedConstruction.Insertion insertion;
        final DataValue.NativeValidation validation;
        Build(int tag,int index,DataValue.ListValue input,DataValue.NamedConstruction named,DataValue.NamedConstruction.Insertion insertion,DataValue.NativeValidation validation) {
            this.tag=tag;this.index=index;this.input=input;this.named=named;this.insertion=insertion;this.validation=validation;
        }
        static Build start(int tag,DataValue.ListValue input,LongConsumer reserve) {
            reserve.accept(tag==10?9:sum(12,input.storageUnits()));
            return new Build(tag,0,input,tag==10?DataValue.NamedConstruction.empty():null,null,tag==10?null:DataValue.NativeValidation.begin(tag,input));
        }
        // native-data-1: eight logical build-control units, plus each owned validation/insertion/prefix.
        long units() {return sum(8,named==null?0:named.storageUnits(),insertion==null?0:insertion.storageUnits(),validation==null?0:validation.storageUnits());}
        boolean done() {return validation!=null && validation.done();}
        DataValue.NbtValue finish() {return validation.finish();}
        long resultUnits() {return validation.storageUnits()-3;}
        Build advance(LongConsumer reserve) {
            if(done())throw new IllegalStateException("Native build complete");
            if(validation!=null) {reserve.accept(units());return new Build(tag,index,input,null,null,validation.advance());}
            if(insertion!=null) {
                if(!insertion.done()) {reserve.accept(units());return new Build(tag,index,input,named,insertion.advance(),null);}
                var entry=(DataValue.RecordValue)input.values().get(index);
                String key=((DataValue.TextValue)entry.field("key")).value();var value=entry.field("value");
                reserve.accept(sum(8,named.storageUnits(),1,key.length(),value.storageUnits()));
                return new Build(tag,index+1,input,insertion.finish(),null,null);
            }
            if(index==input.values().size()) {
                reserve.accept(sum(12,named.storageUnits()));
                return new Build(tag,index,input,null,null,DataValue.NativeValidation.begin(10,named.record()));
            }
            var entry=(DataValue.RecordValue)input.values().get(index);
            String key=((DataValue.TextValue)entry.field("key")).value();var value=(DataValue.NbtValue)entry.field("value");
            if(value.tagId()==0)throw new IllegalArgumentException("END compound child");
            reserve.accept(sum(8,named.storageUnits(),named.storageUnits(),key.length(),value.storageUnits(),128));
            return new Build(tag,index,input,named,named.insert(key,value,false),null);
        }
    }
}
