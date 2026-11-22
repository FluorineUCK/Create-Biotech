package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** A private register file and PC, not graph-node values or a world-object address space. */
public record RegisterState(String image, int pc, Map<Integer, DataValue> registers, boolean structured, Optional<Scan> scan,
Optional<RegisterCalls> calls, Optional<Boolean> transaction, boolean constructionCapable, Optional<RegisterConstruction> build, boolean serialCapable, Optional<RegisterSerial.Progress> serial, boolean equalityCapable, Optional<RegisterComparison> comparison) {
    /** Progress of one TEXT.LENGTH instruction. The source remains in its immutable register. */
    public static final class Scan {
        private final int offset;
        private final long points;
        private String validatedSource;
        public Scan(int offset, long points) {
            if (offset < 1 || points < 1 || points > offset || offset > 2 * points) throw new IllegalArgumentException("Invalid text scan");
            this.offset = offset; this.points = points;
        }
        public int offset() { return offset; }
        public long points() { return points; }
        void validate(String source) {
            // A restored untrusted prefix is checked once. Live immutable continuations retain
            // an identity-bound witness; normal ticks do not rescan already consumed text.
            if (validatedSource == source) return;
            if (offset > source.length() || offset < source.length() && Character.isHighSurrogate(source.charAt(offset - 1)) && Character.isLowSurrogate(source.charAt(offset))
                    || source.codePointCount(0, offset) != points)
                throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Scan does not match its source prefix");
            validatedSource = source;
        }
        static Scan advanced(String source, int offset, long points) {
            var next = new Scan(offset + Character.charCount(source.codePointAt(offset)), points + 1); next.validatedSource = source; return next;
        }
        @Override public boolean equals(Object other) { return other instanceof Scan s && s.offset == offset && s.points == points; }
        @Override public int hashCode() { return Objects.hash(offset, points); }
        @Override public String toString() { return "Scan[offset=" + offset + ", points=" + points + "]"; }
        public DataValue.RecordValue value() {
            return new DataValue.RecordValue(Map.of("offset", new DataValue.IntegerValue(offset), "points", new DataValue.IntegerValue(points)));
        }
    }
    public RegisterState(String image, int pc, Map<Integer, DataValue> registers) { this(image, pc, registers, false, Optional.empty()); }
    public RegisterState(String image, int pc, Map<Integer, DataValue> registers, boolean structured, Optional<Scan> scan) {
        this(image, pc, registers, structured, scan, Optional.empty());
    }
    public RegisterState(String image, int pc, Map<Integer, DataValue> registers, boolean structured, Optional<Scan> scan, Optional<RegisterCalls> calls) {
        this(image, pc, registers, structured, scan, calls, Optional.empty());
    }
    public RegisterState(String image, int pc, Map<Integer, DataValue> registers, boolean structured, Optional<Scan> scan, Optional<RegisterCalls> calls, Optional<Boolean> transaction) {
        this(image, pc, registers, structured, scan, calls, transaction, false, Optional.empty());
    }
    public RegisterState(String image,int pc,Map<Integer,DataValue> registers,boolean structured,Optional<Scan> scan,Optional<RegisterCalls> calls,Optional<Boolean> transaction,boolean constructionCapable,Optional<RegisterConstruction> build) {
        this(image,pc,registers,structured,scan,calls,transaction,constructionCapable,build,false,Optional.empty());
    }
    public RegisterState(String image, int pc, Map<Integer,DataValue> registers, boolean structured, Optional<Scan> scan,
            Optional<RegisterCalls> calls, Optional<Boolean> transaction, boolean constructionCapable, Optional<RegisterConstruction> build,
            boolean serialCapable, Optional<RegisterSerial.Progress> serial) {
        this(image, pc, registers, structured, scan, calls, transaction, constructionCapable, build, serialCapable, serial, false, Optional.empty());
    }
    /** Only this private wrapper can carry a reusable defensive-copy proof. */
    private static final class FrozenRegisters extends AbstractMap<Integer, DataValue> {
        private final Map<Integer, DataValue> values;
        FrozenRegisters(Map<Integer, DataValue> source) {
            var copy = new TreeMap<Integer, DataValue>();
            source.forEach((key, value)-> {
                if (key<0) throw new IllegalArgumentException("Invalid register");
                copy.put(key, Objects.requireNonNull(value));
            });
            values = Collections.unmodifiableMap(copy);
        }
        public Set<Entry<Integer, DataValue>> entrySet() {
            return values.entrySet();
        }
        public DataValue get(Object key) {
            return values.get(key);
        }
        public int size() {
            return values.size();
        }
    }
    public long progressWork() {
        return scan.map(Scan::points).orElse(0L) + build.map(RegisterConstruction::paidWork).orElse(0L) + serial.map(RegisterSerial.Progress::paidWork).orElse(0L) + comparison.map(RegisterComparison::paidWork).orElse(0L);
    }
    public long constructionUnits() {
        return build.map(RegisterConstruction::ownedUnits).orElse(0L) + serial.map(RegisterSerial.Progress::ownedUnits).orElse(0L) + comparison.map(RegisterComparison::ownedUnits).orElse(0L);
    }
    /** Cold restore ledger: every existing buffer, including witnesses from a failed validation. */
    public long materializedConstructionUnits() {
        return Math.addExact(Math.addExact(build.map(p -> p.materializedUnits().orElse(0L)).orElse(0L),
            serial.map(p -> p.materializedUnits().orElse(0L)).orElse(0L)), comparison.map(RegisterComparison::ownedUnits).orElse(0L));
    }
    public RegisterState withTransaction(boolean active) {
        if (transaction.isEmpty()) throw new IllegalStateException("Not a memory-enabled continuation");
        return new RegisterState(image, pc, registers, structured, scan, calls, Optional.of(active), constructionCapable, build, serialCapable, serial, equalityCapable, comparison);
    }
    public RegisterState {
        Objects.requireNonNull(image);
        Objects.requireNonNull(scan);
        Objects.requireNonNull(calls);
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(build); Objects.requireNonNull(serial); Objects.requireNonNull(comparison);
        if (!(registers instanceof FrozenRegisters)) registers = new FrozenRegisters(registers);
        if (!structured && (scan.isPresent() || calls.isPresent() || transaction.isPresent() || constructionCapable || serialCapable || equalityCapable)) throw new IllegalArgumentException("Continuation requires data ISA format");
        if (!image.matches("[0-9a-f]{64}") || pc < 0)
            throw new IllegalArgumentException("Invalid register continuation");
        if (!equalityCapable && comparison.isPresent() || comparison.isPresent() && (scan.isPresent() || build.isPresent() || serial.isPresent())) throw new IllegalArgumentException("Invalid comparison format");
        if (!serialCapable && serial.isPresent() || serial.isPresent() && (scan.isPresent() || build.isPresent())) throw new IllegalArgumentException("Invalid serial format");
        if (!constructionCapable && build.isPresent() || scan.isPresent() && build.isPresent()) throw new IllegalArgumentException("Invalid construction format");
    }
    public DataValue.RecordValue value() {
        var values = new LinkedHashMap<String, DataValue>(); registers.forEach((r, v) -> values.put(Integer.toString(r), v));
        var fields = new LinkedHashMap<String, DataValue>(Map.of("image", new DataValue.TextValue(image), "pc", new DataValue.IntegerValue(pc),
            "registers", new DataValue.RecordValue(values)));
        if (structured) {
            fields.put("data_version", new DataValue.IntegerValue(equalityCapable ? 6 : serialCapable ? 5 : constructionCapable ? 4 : transaction.isPresent() ? 3 : calls.isPresent() ? 2 : 1));
            fields.put("scan", scan.<DataValue>map(Scan::value).orElse(DataValue.NullValue.INSTANCE));
        }
        if (constructionCapable) fields.put("build", build.<DataValue>map(RegisterConstruction::value).orElse(DataValue.NullValue.INSTANCE));
        if (serialCapable) fields.put("serial", serial.<DataValue>map(RegisterSerial.Progress::value).orElse(DataValue.NullValue.INSTANCE));
        if (equalityCapable) fields.put("comparison", comparison.<DataValue>map(RegisterComparison::value).orElse(DataValue.NullValue.INSTANCE));
        calls.ifPresent(value -> fields.put("calls", value.value()));
        transaction.ifPresent(active -> fields.put("transaction", new DataValue.BooleanValue(active)));
        return new DataValue.RecordValue(fields);
    }
}
