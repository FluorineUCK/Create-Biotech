package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.LongConsumer;

/** Explicit software reassembly. Input segments remain immutable; no device or wire side effects. */
final class RegisterByteJoining {
    private final long paid,cursor,rebuilt;
    private final Engine engine;
    private Map<Integer,DataValue> source;
    private String image;
    private int pc;
    private RegisterByteJoining(long paid,long cursor,long rebuilt,Engine engine) {
        if(cursor<1 || paid<cursor || rebuilt<0 || rebuilt>cursor)throw new IllegalArgumentException("Invalid byte joining cursor");
        this.paid=paid;this.cursor=cursor;this.rebuilt=rebuilt;this.engine=engine;
    }
    static RegisterByteJoining saved(long paid,long cursor) {return new RegisterByteJoining(paid,cursor,0,null);}
    private RegisterByteJoining bind(RegisterState state) {source=state.registers();image=state.image();pc=state.pc();return this;}
    void validate(RegisterState state,RegisterNbt.Instruction op) {
        if(!op.joining() || source!=null && (source!=state.registers() || !image.equals(state.image()) || pc!=state.pc()))invalid();
        capacity(state,op);
        if(engine!=null && engine.done() && rebuilt!=cursor)invalid();
    }
    private static int capacity(RegisterState state,RegisterNbt.Instruction op) {
        long count=((DataValue.IntegerValue)state.registers().get(op.operand())).value();
        if(count<0 || count>Integer.MAX_VALUE)throw RegisterNbt.fault(ComputationException.Code.INVALID_ARGUMENT,"BYTES.JOIN capacity must be a nonnegative byte count");
        return (int)count;
    }
    static RegisterByteJoining start(RegisterState state,RegisterNbt.Instruction op,LongConsumer reserve) {
        return new RegisterByteJoining(1,1,1,Engine.start((DataValue.ListValue)state.registers().get(op.from()),capacity(state,op),reserve)).bind(state);
    }
    RegisterByteJoining advance(RegisterState state,RegisterNbt.Instruction op,LongConsumer reserve) {
        validate(state,op);
        var next=engine==null?Engine.start((DataValue.ListValue)state.registers().get(op.from()),capacity(state,op),reserve):engine.advance(reserve);
        long rebuiltNext=Math.incrementExact(rebuilt);
        if(next.done() && rebuiltNext<cursor)invalid();
        return new RegisterByteJoining(Math.incrementExact(paid),Math.max(cursor,rebuiltNext),rebuiltNext,next).bind(state);
    }
    boolean done(){return engine!=null && rebuilt==cursor && engine.done();}
    long paid(){return paid;}
    long units(){return engine==null?8:engine.units();}
    long resultUnits(){return engine.proof.storageUnits()-3;}
    DataValue result(){return engine.proof.finish();}
    DataValue.RecordValue value(){return new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("bytes_join"),"version",new DataValue.IntegerValue(1),"paid",new DataValue.IntegerValue(paid),"cursor",new DataValue.IntegerValue(cursor)));}
    @Override public boolean equals(Object other){return other instanceof RegisterByteJoining j && paid==j.paid && cursor==j.cursor;}
    @Override public int hashCode(){return Objects.hash(paid,cursor);}
    private static void invalid(){throw RegisterNbt.fault(ComputationException.Code.INVALID_CHECKPOINT,"Byte joining cursor differs from its installed operands");}

    // byte-joining-1: eight control units plus the owned immutable prefix or validation workspace.
    // The source list is borrowed from registers and already charged by the owner.
    private record Engine(DataValue.ListValue input,int capacity,int part,int offset,int copied,DataValue.Construction bytes,DataValue.NativeValidation proof) {
        static Engine start(DataValue.ListValue input,int capacity,LongConsumer reserve) {
            reserve.accept(9);return new Engine(input,capacity,0,-1,0,DataValue.Construction.list(),null);
        }
        long units(){return Math.addExact(8,proof==null?bytes.storageUnits():proof.storageUnits());}
        boolean done(){return proof!=null && proof.done();}
        Engine advance(LongConsumer reserve) {
            if(proof!=null){reserve.accept(units());return new Engine(input,capacity,part,offset,copied,null,proof.advance());}
            if(part==input.values().size()) {
                reserve.accept(Math.addExact(12,bytes.storageUnits()));
                return new Engine(input,capacity,part,offset,copied,null,DataValue.NativeValidation.begin(7,bytes.finish()));
            }
            var segment=(DataValue.NbtValue)input.values().get(part);
            if(segment.tagId()!=7)throw RegisterNbt.fault(ComputationException.Code.TYPE_MISMATCH,"BYTES.JOIN requires byte-array segments");
            var values=((DataValue.ListValue)segment.value()).values();
            if(offset<0) {
                if(values.size()>capacity-copied)throw RegisterNbt.fault(ComputationException.Code.INVALID_ARGUMENT,"BYTES.JOIN capacity exceeded");
                reserve.accept(units());return new Engine(input,capacity,part,0,copied,bytes,null);
            }
            if(offset==values.size()) {reserve.accept(units());return new Engine(input,capacity,part+1,-1,copied,bytes,null);}
            reserve.accept(Math.addExact(units(),1));
            return new Engine(input,capacity,part,offset+1,copied+1,bytes.append(values.get(offset)),null);
        }
    }
}
