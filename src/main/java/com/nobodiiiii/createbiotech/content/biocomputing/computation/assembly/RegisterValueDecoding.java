package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import java.util.function.LongConsumer;

/** Typed byte-stream decode and declared-result validation, without an implicit ANY register.
 * Private caches are reconstructed in billed steps. Malformed data is a branchable result. */
final class RegisterValueDecoding {
    private final long paid, cursor, rebuilt;
    private final Engine engine;
    private final boolean unitWorkspace;
    private Map<Integer, DataValue> source;
    private String image;
    private int pc;
    private RegisterValueDecoding(long paid, long cursor, long rebuilt, Engine engine,boolean unitWorkspace) {
        if (cursor < 1 || paid < cursor || rebuilt < 0 || rebuilt > cursor) throw new IllegalArgumentException("Invalid value decoding cursor");
        this.paid=paid; this.cursor=cursor; this.rebuilt=rebuilt; this.engine=engine;this.unitWorkspace=unitWorkspace;
    }
    static RegisterValueDecoding saved(long paid, long cursor) { return new RegisterValueDecoding(paid,cursor,0,null,false); }
    static RegisterValueDecoding savedUnits(long paid,long cursor) {return new RegisterValueDecoding(paid,cursor,0,null,true);}
    private RegisterValueDecoding bind(RegisterState state) { source=state.registers(); image=state.image(); pc=state.pc(); return this; }
    void validate(RegisterState state, RegisterNbt.Instruction op) {
        if (!op.decoding() || unitWorkspace!=op.unitWorkspace() || source != null && (source != state.registers() || !image.equals(state.image()) || pc != state.pc())) invalid();
        workspace(state,op);
        if (engine != null && engine.done() && rebuilt != cursor) invalid();
    }
    private static long workspace(RegisterState state, RegisterNbt.Instruction op) {
        long size=((DataValue.IntegerValue)state.registers().get(op.operand())).value();
        if (size < 1 || size > Integer.MAX_VALUE) throw RegisterNbt.fault(ComputationException.Code.INVALID_ARGUMENT,"VALUE.DECODE workspace must be a positive bounded unit count");
        return size;
    }
    static RegisterValueDecoding start(RegisterState state, RegisterNbt.Instruction op, LongConsumer reserve) {
        return new RegisterValueDecoding(1,1,1,Engine.start((DataValue.NbtValue)state.registers().get(op.from()),workspace(state,op),op.unitWorkspace(),reserve),op.unitWorkspace()).bind(state);
    }
    RegisterValueDecoding advance(RegisterState state, RegisterNbt.Instruction op, ValueTypeTask.Schema schema, LongConsumer reserve) {
        validate(state,op);
        var next=engine==null?Engine.start((DataValue.NbtValue)state.registers().get(op.from()),workspace(state,op),op.unitWorkspace(),reserve):engine.advance(schema);
        long rebuiltNext=Math.incrementExact(rebuilt);
        if(next.done() && rebuiltNext<cursor)invalid();
        return new RegisterValueDecoding(Math.incrementExact(paid),Math.max(cursor,rebuiltNext),rebuiltNext,next,unitWorkspace).bind(state);
    }
    boolean done() { return engine!=null && rebuilt==cursor && engine.done(); }
    long paid() { return paid; }
    long units() { return engine==null?8:engine.units(); }
    long resultUnits() { return Math.addExact(13L+engine.code.length(),engine.value.storageUnits()); }
    DataValue result() { return new DataValue.RecordValue(Map.of("code",new DataValue.TextValue(engine.code),"value",engine.value)); }
    DataValue.RecordValue value() { return new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("value_decode"),"version",new DataValue.IntegerValue(unitWorkspace?2:1),"paid",new DataValue.IntegerValue(paid),"cursor",new DataValue.IntegerValue(cursor))); }
    @Override public boolean equals(Object other) { return other instanceof RegisterValueDecoding p && paid==p.paid && cursor==p.cursor && unitWorkspace==p.unitWorkspace; }
    @Override public int hashCode() { return unitWorkspace?Objects.hash(paid,cursor,true):Objects.hash(paid,cursor); }
    private static void invalid() { throw RegisterNbt.fault(ComputationException.Code.INVALID_CHECKPOINT,"Value decoding cursor differs from its installed operands"); }

    private record Engine(long workspace, long reserved, SerialDecoding.State decoder, ValueTypeTask typing, DataValue value, String code) {
        static Engine start(DataValue.NbtValue input,long workspace,boolean unitWorkspace,LongConsumer reserve) {
            if(input.tagId()!=7) { reserve.accept(8); return new Engine(workspace,8,null,null,DataValue.NullValue.INSTANCE,"invalid_bytes"); }
            var bytes=((DataValue.ListValue)input.value()).values();
            // Parser allocation and typed-container workspace are independent. Also reserve the
            // maximum source-bounded UTF buffer before the parser can allocate any character array.
            long reserved=Math.addExact(8,Math.addExact(2*workspace,2L*bytes.size()));
            reserve.accept(reserved);
            var stream=new SerialDecoding.Input() {
                public int size(){return bytes.size();}
                public int byteAt(int index){return (int)((DataValue.IntegerValue)bytes.get(index)).value()&255;}
            };
            // Heap accounting is bytes, while Computer RAM and the typed workspace use logical units.
            // Match LibraryUploadCommit/SerialEncoding's fixed eight-byte unit conversion. V1 stays unchanged.
            long heapBytes=unitWorkspace?Math.multiplyExact(workspace,Long.BYTES):workspace;
            var limits=new SerialRecordCodec.Limits(Math.max(1,bytes.size()),heapBytes,
                new ArchiveLimits((int)workspace,workspace,workspace));
            return new Engine(workspace,reserved,SerialDecoding.begin(stream,false,limits),null,DataValue.NullValue.INSTANCE,null);
        }
        long units(){return reserved;}
        boolean done(){return code!=null;}
        private Engine finish(String code,DataValue value){return new Engine(workspace,reserved,null,null,value,code);}
        private Engine failure(String code){return finish(code,DataValue.NullValue.INSTANCE);}
        Engine advance(ValueTypeTask.Schema schema) {
            // The owner already retains this fixed pool through units(). start() (including cold
            // reconstruction) reserves it before allocation; steps reuse that pool rather than
            // requesting a second full pool alongside the first. Parser/live-value checks below
            // still bound every advance, and final result publication is reserved separately.
            if(typing!=null) {
                if(typing.complete())return typing.matches()?finish("ok",value):failure("type_mismatch");
                try {
                    var next=typing.advance(extra->{if(value.storageUnits()+typing.liveUnits()+extra>workspace)throw new WorkspaceFull();});
                    return new Engine(workspace,reserved,null,next,value,null);
                } catch(WorkspaceFull full) {return failure("decode_limit");}
            }
            if(!decoder.done()) {
                var next=decoder.advance();
                if(next.storageUnits()>workspace)return failure("decode_limit");
                return new Engine(workspace,reserved,next,null,value,null);
            }
            var decoded=decoder.decoded();
            if(decoded.error()!=null)return failure(decoded.error());
            if(!(decoded.value() instanceof DataValue.RecordValue envelope) || envelope.fields().size()!=1 || !envelope.fields().containsKey("value"))return failure("invalid_record");
            var payload=envelope.field("value");
            try {
                var check=ValueTypeTask.start(schema,payload,extra->{if(payload.storageUnits()+extra>workspace)throw new WorkspaceFull();});
                return new Engine(workspace,reserved,null,check,payload,null);
            } catch(WorkspaceFull full) {return failure("decode_limit");}
        }
    }
    private static final class WorkspaceFull extends RuntimeException {}
}
