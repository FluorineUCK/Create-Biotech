package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import java.util.function.LongConsumer;

/** typed_record_v1 software encoding. No device, automatic fragmentation or transport effects.
 * A cold cursor is rebuilt one billed ISA cycle at a time, never in checkpoint validation. */
final class RegisterValueEncoding {
    private final long paid, cursor;
    private final long rebuilt;
    private final Engine engine;
    private Map<Integer, DataValue> source;
    private String image;
    private int pc;

    private RegisterValueEncoding(long paid, long cursor, long rebuilt, Engine engine) {
        if (cursor < 1 || paid < cursor || rebuilt < 0 || rebuilt > cursor)
            throw new IllegalArgumentException("Invalid value encoding cursor");
        this.paid = paid; this.cursor = cursor; this.rebuilt = rebuilt; this.engine = engine;
    }
    static RegisterValueEncoding saved(long paid, long cursor) { return new RegisterValueEncoding(paid, cursor, 0, null); }
    private RegisterValueEncoding bind(RegisterState state) { source=state.registers(); image=state.image(); pc=state.pc(); return this; }
    void validate(RegisterState state, RegisterNbt.Instruction op) {
        if (!op.encoding() || source != null && (source != state.registers() || !image.equals(state.image()) || pc != state.pc())) invalid();
        capacity(state, op);
        if (engine != null && engine.done() && rebuilt != cursor) invalid();
    }
    static RegisterValueEncoding start(RegisterState state, RegisterNbt.Instruction op, LongConsumer reserve) {
        return new RegisterValueEncoding(1, 1, 1, Engine.start(state.registers().get(op.from()), capacity(state, op), reserve)).bind(state);
    }
    RegisterValueEncoding advance(RegisterState state, RegisterNbt.Instruction op, LongConsumer reserve) {
        validate(state, op);
        Engine next = engine == null ? Engine.start(state.registers().get(op.from()), capacity(state, op), reserve) : engine.advance(reserve);
        long nextRebuilt = Math.incrementExact(rebuilt);
        if (next.done() && nextRebuilt < cursor) invalid();
        return new RegisterValueEncoding(Math.incrementExact(paid), Math.max(cursor, nextRebuilt), nextRebuilt, next).bind(state);
    }
    private static int capacity(RegisterState state, RegisterNbt.Instruction op) {
        long value = ((DataValue.IntegerValue)state.registers().get(op.operand())).value();
        if (value < 1 || value > Integer.MAX_VALUE)
            throw RegisterNbt.fault(ComputationException.Code.INVALID_ARGUMENT, "VALUE.ENCODE capacity must be a positive byte count");
        return (int)value;
    }
    boolean done() { return engine != null && rebuilt == cursor && engine.done(); }
    long paid() { return paid; }
    long units() { return engine == null ? 8 : engine.units(); }
    long resultUnits() { return engine.proof.storageUnits() - 3; }
    DataValue result() { return engine.proof.finish(); }
    DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("value_encode"), "version", new DataValue.IntegerValue(1),
            "paid", new DataValue.IntegerValue(paid), "cursor", new DataValue.IntegerValue(cursor)));
    }
    @Override public boolean equals(Object other) { return other instanceof RegisterValueEncoding p && paid == p.paid && cursor == p.cursor; }
    @Override public int hashCode() { return Objects.hash(paid, cursor); }
    private static void invalid() { throw RegisterNbt.fault(ComputationException.Code.INVALID_CHECKPOINT, "Value encoding cursor differs from its installed operands"); }

    private record Engine(SerialEncoding.State encoder, DataValue.Construction bytes, int copied, DataValue.NativeValidation proof) {
        static Engine start(DataValue input, int capacity, LongConsumer reserve) {
            LocalReferences.portable(input);
            // One root, one five-character field name and its child; O(1) value metadata.
            long units = Math.addExact(7, input.storageUnits());
            reserve.accept(Math.addExact(8, units));
            var envelope = new DataValue.RecordValue(Map.of("value", input));
            var limits = new SerialRecordCodec.Limits(capacity, capacity,
                new ArchiveLimits((int)Math.min(Integer.MAX_VALUE, units), units, units));
            var encoder = SerialEncoding.begin(envelope, limits, candidate -> reserve.accept(Math.addExact(9, candidate)));
            return new Engine(encoder, DataValue.Construction.list(), 0, null);
        }
        long units() { return Math.addExact(8, proof == null ? Math.addExact(encoder.storageUnits(), bytes.storageUnits()) : proof.storageUnits()); }
        boolean done() { return proof != null && proof.done(); }
        Engine advance(LongConsumer reserve) {
            if (proof != null) {
                reserve.accept(units()); return new Engine(null, null, copied, proof.advance());
            }
            if (!encoder.done()) {
                var next = encoder.advance(candidate -> reserve.accept(Math.addExact(Math.addExact(8, bytes.storageUnits()), candidate)));
                return new Engine(next, bytes, copied, null);
            }
            var encoded = encoder.encoded();
            if (encoded.error() != null) throw RegisterNbt.fault(ComputationException.Code.INVALID_ARGUMENT, "VALUE.ENCODE: " + encoded.error());
            if (copied < encoded.size()) {
                reserve.accept(Math.addExact(units(), 1));
                return new Engine(encoder, bytes.append(new DataValue.IntegerValue((byte)encoded.byteAt(copied))), copied+1, null);
            }
            reserve.accept(Math.addExact(12, bytes.storageUnits()));
            return new Engine(null, null, copied, DataValue.NativeValidation.begin(7, bytes.finish()));
        }
    }
}
