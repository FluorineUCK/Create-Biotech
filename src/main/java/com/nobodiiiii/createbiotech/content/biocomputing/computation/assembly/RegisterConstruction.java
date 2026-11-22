package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Cursor-only wire state, with an identity-bound, private immutable live witness. */
public final class RegisterConstruction {
    sealed interface TextProgress permits RegisterTextConcat, RegisterTextContains {
        long paid();
        DataValue.RecordValue value();
    }
    private final TextProgress text;
    private final int cursor;
    private int nativeTag=-1;
    private long nativePaid;
    private RegisterNbt.Build nativeBuild;
    private RegisterValueEncoding encoding;
    private RegisterValueDecoding decoding;
    private RegisterByteJoining joining;
    public boolean byteJoining() {return joining!=null;}
    public static RegisterConstruction savedJoining(long paid,long cursor) {
        var value=new RegisterConstruction(0);value.joining=RegisterByteJoining.saved(paid,cursor);return value;
    }
    static RegisterConstruction startJoining(RegisterState state,RegisterNbt.Instruction op,java.util.function.LongConsumer reserve) {
        var value=new RegisterConstruction(0);value.joining=RegisterByteJoining.start(state,op,reserve);return value;
    }
    void validateJoining(RegisterState state,RegisterNbt.Instruction op) {if(!byteJoining())invalid();joining.validate(state,op);}
    RegisterConstruction advanceJoining(RegisterState state,RegisterNbt.Instruction op,java.util.function.LongConsumer reserve) {
        var value=new RegisterConstruction(0);value.joining=joining.advance(state,op,reserve);return value;
    }
    boolean joiningDone(){return joining.done();}
    long joiningResultUnits(){return joining.resultUnits();}
    DataValue joiningResult(){return joining.result();}
    public boolean valueDecoding() { return decoding != null; }
    public static RegisterConstruction savedDecoding(long paid, long cursor) {
        var value=new RegisterConstruction(0); value.decoding=RegisterValueDecoding.saved(paid,cursor); return value;
    }
    public static RegisterConstruction savedUnitDecoding(long paid,long cursor) {
        var value=new RegisterConstruction(0);value.decoding=RegisterValueDecoding.savedUnits(paid,cursor);return value;
    }
    static RegisterConstruction startDecoding(RegisterState state, RegisterNbt.Instruction op, java.util.function.LongConsumer reserve) {
        var value=new RegisterConstruction(0); value.decoding=RegisterValueDecoding.start(state,op,reserve); return value;
    }
    void validateDecoding(RegisterState state, RegisterNbt.Instruction op) { if(!valueDecoding())invalid(); decoding.validate(state,op); }
    RegisterConstruction advanceDecoding(RegisterState state, RegisterNbt.Instruction op, ValueTypeTask.Schema schema, java.util.function.LongConsumer reserve) {
        var value=new RegisterConstruction(0); value.decoding=decoding.advance(state,op,schema,reserve); return value;
    }
    boolean decodingDone() { return decoding.done(); }
    long decodingResultUnits() { return decoding.resultUnits(); }
    DataValue decodingResult() { return decoding.result(); }
    public boolean valueEncoding() { return encoding != null; }
    public static RegisterConstruction savedEncoding(long paid, long cursor) {
        var value = new RegisterConstruction(0); value.encoding = RegisterValueEncoding.saved(paid, cursor); return value;
    }
    static RegisterConstruction startEncoding(RegisterState state, RegisterNbt.Instruction op, java.util.function.LongConsumer reserve) {
        var value = new RegisterConstruction(0); value.encoding = RegisterValueEncoding.start(state, op, reserve); return value;
    }
    void validateEncoding(RegisterState state, RegisterNbt.Instruction op) { if (!valueEncoding()) invalid(); encoding.validate(state, op); }
    RegisterConstruction advanceEncoding(RegisterState state, RegisterNbt.Instruction op, java.util.function.LongConsumer reserve) {
        var value = new RegisterConstruction(0); value.encoding = encoding.advance(state, op, reserve); return value;
    }
    boolean encodingDone() { return encoding.done(); }
    long encodingResultUnits() { return encoding.resultUnits(); }
    DataValue encodingResult() { return encoding.result(); }
    public boolean nativePack() { return nativeTag>=0; }
    public static RegisterConstruction savedNative(int tag,long paid) {
        if(!Set.of(7,9,10,11,12).contains(tag) || paid<1)throw new IllegalArgumentException("Invalid native construction cursor");
        var value=new RegisterConstruction(0);value.nativeTag=tag;value.nativePaid=paid;return value;
    }
    static RegisterConstruction startNative(RegisterState state,RegisterNbt.Instruction op,java.util.function.LongConsumer reserve) {
        var value=savedNative(op.operand(),1);
        value.nativeBuild=RegisterNbt.Build.start(op.operand(),(DataValue.ListValue)state.registers().get(op.from()),reserve);
        value.bindNative(state);return value;
    }
    private void bindNative(RegisterState state) {source=state.registers();image=state.image();pc=state.pc();}
    void validateNative(RegisterState state,RegisterNbt.Instruction op,java.util.function.LongConsumer reserve) {
        if(!nativePack() || !op.packing() || nativeTag!=op.operand())invalid();
        if(nativeBuild!=null) {if(source!=state.registers() || !state.image().equals(image) || pc!=state.pc())invalid();return;}
        try {
            nativeBuild=RegisterNbt.Build.start(nativeTag,(DataValue.ListValue)state.registers().get(op.from()),reserve);
            for(long paid=1;paid<nativePaid;paid++) {
                if(nativeBuild.done())invalid();
                long retained=nativeBuild.units();
                nativeBuild=nativeBuild.advance(candidate->reserve.accept(Math.addExact(retained,candidate)));
            }
        } catch(IllegalArgumentException invalid) { invalid(); }
        bindNative(state);
    }
    RegisterConstruction advanceNative(RegisterState state,java.util.function.LongConsumer reserve) {
        var next=savedNative(nativeTag,Math.incrementExact(nativePaid));next.nativeBuild=nativeBuild.advance(reserve);next.bindNative(state);return next;
    }
    boolean nativeDone() {return nativeBuild.done();}
    long nativeResultUnits() {return nativeBuild.resultUnits();}
    DataValue nativeResult() {return nativeBuild.finish();}

    // Derived validation cache only. No mutable payload or caller-supplied "trusted" prefix.
    private DataValue.Construction prefix;
    private Map<Integer, DataValue> source;
    private String image;
    private int pc;

    public RegisterConstruction(int cursor) { this(cursor, null); }
    private RegisterConstruction(int cursor, TextProgress text) {
        if (cursor < 0) throw new IllegalArgumentException("Invalid construction cursor");
        this.cursor = cursor; this.text = text;
    }

    public static RegisterConstruction savedTextConcat(int left, int right, long points) {
        return new RegisterConstruction(0, new RegisterTextConcat(left, right, points));
    }
    static RegisterConstruction textConcat(RegisterState state, RegisterTextConcat progress) {
        var next = new RegisterConstruction(0, progress);
        next.bindNative(state);
        return next;
    }
    RegisterTextConcat textConcat() {
        if (text instanceof RegisterTextConcat concat) return concat;
        throw new IllegalStateException("Not a text concatenation cursor");
    }
    void validateTextConcat(RegisterState state, RegisterProgram.TextConcat op) {
        if (!(text instanceof RegisterTextConcat)) invalid();
        if (source != null) {
            if (source != state.registers() || !state.image().equals(image) || pc != state.pc()) invalid();
            return;
        }
        if (!(state.registers().get(op.left()) instanceof DataValue.TextValue first)
                || !(state.registers().get(op.right()) instanceof DataValue.TextValue second)
                || !textConcat().matches(first.value(), second.value())) invalid();
        bindNative(state);
    }


    public static RegisterConstruction savedTextContains(int start, int offset, int needle, long compared) {
        return new RegisterConstruction(0, new RegisterTextContains(start, offset, needle, compared));
    }
    static RegisterConstruction textContains(RegisterState state, RegisterTextContains progress) {
        var next = new RegisterConstruction(0, progress);
        next.bindNative(state);
        return next;
    }
    RegisterTextContains textContains() {
        if (text instanceof RegisterTextContains search) return search;
        throw new IllegalStateException("Not a text search cursor");
    }
    void validateTextContains(RegisterState state, RegisterProgram.TextContains op) {
        if (!(text instanceof RegisterTextContains)) invalid();
        if (source != null) {
            if (source != state.registers() || !state.image().equals(image) || pc != state.pc()) invalid();
            return;
        }
        if (!(state.registers().get(op.text()) instanceof DataValue.TextValue first)
                || !(state.registers().get(op.needle()) instanceof DataValue.TextValue second)
                || !textContains().matches(first.value(), second.value())) invalid();
        bindNative(state);
    }

    public int cursor() { return cursor; }
    public long paidWork() { return text != null ? text.paid() : byteJoining()?joining.paid():valueDecoding()?decoding.paid():valueEncoding()?encoding.paid():nativePack()?nativePaid:1L + cursor; }

    public long ownedUnits() {
        if (text != null) return 0; // Fixed cursor is charged by the wire-state ledger; operands own the strings.
        if(byteJoining())return joining.units();
        if(valueDecoding())return decoding.units();
        if(valueEncoding())return encoding.units();
        if(nativePack()) {if(nativeBuild==null)throw new IllegalStateException("Unvalidated native construction");return nativeBuild.units();}
        if (prefix == null) throw new IllegalStateException("Unvalidated construction");
        return prefix.storageUnits();
    }

    /** Empty only while a cold wire cursor has no allocated prefix. */
    public OptionalLong materializedUnits() {
        if (text != null) return OptionalLong.empty();
        if(byteJoining())return OptionalLong.of(joining.units());
        if(valueDecoding())return OptionalLong.of(decoding.units());
        if(valueEncoding())return OptionalLong.of(encoding.units());
        if(nativePack())return nativeBuild==null?OptionalLong.empty():OptionalLong.of(nativeBuild.units());
        return prefix == null ? OptionalLong.empty() : OptionalLong.of(prefix.storageUnits());
    }

    void validate(RegisterState state, List<Integer> operands, DataValue.RecordShape shape, java.util.function.LongConsumer reserve) {
        validate(state, operands.size(), i -> state.registers().get(operands.get(i)), shape, reserve);
    }

    void validate(RegisterState state, int count, java.util.function.IntFunction<DataValue> values, DataValue.RecordShape shape, java.util.function.LongConsumer reserve) {
        if (text != null || nativePack() || valueEncoding() || valueDecoding() || byteJoining() || cursor > count) invalid();
        if (prefix != null) {
            // Owner restore recompiles instructions. Bind to logical image/PC and the private
            // frozen source snapshot, not to the new Instruction or RecordShape object.
            if (source != state.registers() || !state.image().equals(image) || state.pc() != pc) invalid();
            return;
        }
        reserve.accept(1);
        var rebuilt = shape == null ? DataValue.Construction.list() : DataValue.Construction.record(shape);
        for (int i = 0; i < cursor; i++) {
            var value = values.apply(i);
            if (value == null) invalid();
            long next = Math.addExact(rebuilt.storageUnits(), value.storageUnits());
            if (shape != null) next = Math.addExact(next, 1L + shape.keys().get(i).length());
            // The old immutable prefix stays live until append has returned.
            reserve.accept(Math.addExact(rebuilt.storageUnits(), next));
            rebuilt = rebuilt.append(value);
        }
        bind(state, rebuilt);
    }

    private void bind(RegisterState state, DataValue.Construction value) {
        prefix = value;
        source = state.registers();
        image = state.image();
        pc = state.pc();
    }

    static RegisterConstruction start(RegisterState state, DataValue.RecordShape shape) {
        var next = new RegisterConstruction(0);
        next.bind(state, shape == null ? DataValue.Construction.list() : DataValue.Construction.record(shape));
        return next;
    }

    RegisterConstruction append(RegisterState state, DataValue value) {
        var next = new RegisterConstruction(Math.addExact(cursor, 1));
        next.bind(state, prefix.append(value));
        return next;
    }

    DataValue finish() { return prefix.finish(); }

    public DataValue.RecordValue value() {
        if (text != null) return text.value();
        if(byteJoining())return joining.value();
        if(valueDecoding())return decoding.value();
        if(valueEncoding())return encoding.value();
        if(nativePack())return new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("native_pack"),"version",new DataValue.IntegerValue(1),"tag",new DataValue.IntegerValue(nativeTag),"paid",new DataValue.IntegerValue(nativePaid)));
        return new DataValue.RecordValue(Map.of("cursor", new DataValue.IntegerValue(cursor)));
    }

    @Override public boolean equals(Object other) {
        return other instanceof RegisterConstruction progress && progress.cursor == cursor && Objects.equals(text, progress.text) && progress.nativeTag==nativeTag && progress.nativePaid==nativePaid && Objects.equals(encoding,progress.encoding) && Objects.equals(decoding,progress.decoding) && Objects.equals(joining,progress.joining);
    }
    @Override public int hashCode() { return text != null ? text.hashCode() : byteJoining()?joining.hashCode():valueDecoding()?decoding.hashCode():valueEncoding()?encoding.hashCode():nativePack()?Objects.hash(cursor,nativeTag,nativePaid):cursor; }

    private static void invalid() {
        throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly",
            "Construction cursor differs from its installed operands");
    }
}
