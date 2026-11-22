package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Native uncompressed NBT with an unnamed root, without the computation value-table envelope.
 * Tags exist only at this conversion boundary. No retained mutable source/returned Tag aliases. */
public final class NativeNbtCodec {
    // Parser/stack safety, not capacity or throughput balance. Mirrors the peripheral snapshot ceiling.
    private static final int MAX_DEPTH = 128;
    private NativeNbtCodec() {}
    public enum Code { PAYLOAD_TOO_LARGE, VALUE_LIMIT, DECODE_LIMIT, INVALID_NBT }
    public static final class Failure extends IllegalArgumentException {
        private final Code code;
        private Failure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
    public record Limits(int maxEncodedBytes, long maxDecodeHeapBytes, ArchiveLimits values) {
        public Limits {
            Objects.requireNonNull(values);
            if (maxEncodedBytes < 1 || maxDecodeHeapBytes < 1) throw new IllegalArgumentException("Positive NBT limits required");
        }
    }
    public static DataValue.NbtValue snapshot(Tag tag, Limits limits) {
        return capture(Objects.requireNonNull(tag), new Budget(limits.values()), 0);
    }
    public static Tag toTag(DataValue.NbtValue value, Limits limits) {
        if (value.storageUnits() > limits.values().maxValueUnits()) throw new Failure(Code.VALUE_LIMIT);
        return materialize(value, new Budget(limits.values()), 0);
    }
    public static byte[] encode(DataValue.NbtValue value, Limits limits) {
        var bytes = new BoundedOutput(limits.maxEncodedBytes());
        try {
            // Unlike write(...), this does not silently replace an oversized UTF string by "".
            NbtIo.writeUnnamedTag(toTag(value, limits), new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new Failure(Code.INVALID_NBT); }
    }
    public static DataValue.NbtValue decode(byte[] bytes, Limits limits) {
        if (bytes.length > limits.maxEncodedBytes()) throw new Failure(Code.PAYLOAD_TOO_LARGE);
        try {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            var budget = NbtAccounter.create(limits.maxDecodeHeapBytes());
            int id = input.readUnsignedByte();
            if (id > Tag.TAG_LONG_ARRAY) throw new Failure(Code.INVALID_NBT);
            budget.accountBytes(1);
            Tag tag;
            if (id == Tag.TAG_END) tag = EndTag.INSTANCE;
            else {
                budget.readUTF(input.readUTF()); budget.accountBytes(4);
                tag = TagTypes.getType(id).load(input, budget);
            }
            if (input.available() != 0) throw new Failure(Code.INVALID_NBT);
            return snapshot(tag, limits);
        } catch (Failure known) { throw known; }
        catch (NbtAccounterException exhausted) { throw new Failure(Code.DECODE_LIMIT); }
        catch (IOException | NbtException | ReportedNbtException | IllegalArgumentException | NegativeArraySizeException invalid) {
            throw new Failure(Code.INVALID_NBT);
        }
    }
    private static DataValue.NbtValue capture(Tag tag, Budget budget, int depth) {
        budget.node(depth);
        DataValue body = switch (tag) {
            case EndTag ignored -> DataValue.NullValue.INSTANCE;
            case FloatTag n -> new DataValue.IntegerValue(Float.floatToRawIntBits(n.getAsFloat()));
            case DoubleTag n -> new DataValue.IntegerValue(Double.doubleToRawLongBits(n.getAsDouble()));
            case NumericTag n -> new DataValue.IntegerValue(n.getAsLong());
            case StringTag s -> { budget.text(s.getAsString()); yield new DataValue.TextValue(s.getAsString()); }
            case CompoundTag compound -> {
                budget.children(compound.size());
                var fields = new LinkedHashMap<String, DataValue>();
                for (String key : compound.getAllKeys()) {
                    budget.text(key); fields.put(key, capture(compound.get(key), budget, depth + 1));
                }
                yield new DataValue.RecordValue(fields);
            }
            case CollectionTag<?> collection -> {
                budget.children(collection.size());
                var items = new ArrayList<DataValue>(collection.size());
                for (var item : collection) {
                    if (tag instanceof ListTag) items.add(capture(item, budget, depth + 1));
                    else { budget.node(depth + 1); items.add(new DataValue.IntegerValue(((NumericTag)item).getAsLong())); }
                }
                yield new DataValue.ListValue(items);
            }
            default -> throw new Failure(Code.INVALID_NBT);
        };
        var result = new DataValue.NbtValue(tag.getId(), body);
        if (result.storageUnits() > budget.limits.maxValueUnits()) throw new Failure(Code.VALUE_LIMIT);
        return result;
    }
    private static Tag materialize(DataValue.NbtValue nbt, Budget budget, int depth) {
        budget.node(depth);
        var body = nbt.value();
        if (body instanceof DataValue.IntegerValue n) return switch (nbt.tagId()) {
            case 1 -> ByteTag.valueOf((byte)n.value()); case 2 -> ShortTag.valueOf((short)n.value());
            case 3 -> IntTag.valueOf((int)n.value()); case 4 -> LongTag.valueOf(n.value());
            case 5 -> FloatTag.valueOf(Float.intBitsToFloat((int)n.value()));
            case 6 -> DoubleTag.valueOf(Double.longBitsToDouble(n.value()));
            default -> throw new Failure(Code.INVALID_NBT);
        };
        if (body instanceof DataValue.TextValue text) { budget.text(text.value()); return StringTag.valueOf(text.value()); }
        if (body instanceof DataValue.RecordValue record) {
            budget.children(record.fields().size()); var tag = new CompoundTag();
            record.fields().forEach((key, value) -> { budget.text(key); tag.put(key, materialize((DataValue.NbtValue)value, budget, depth + 1)); });
            return tag;
        }
        if (body instanceof DataValue.ListValue list) {
            int size = list.values().size(); budget.children(size);
            if (nbt.tagId() == 9) {
                var tag = new ListTag();
                for (var child : list.values()) tag.add(materialize((DataValue.NbtValue)child, budget, depth + 1));
                return tag;
            }
            // Account primitive array elements before allocating, not after building an unbounded array.
            for (int i = 0; i < size; i++) budget.node(depth + 1);
            return switch (nbt.tagId()) {
                case 7 -> { byte[] data = new byte[size]; for (int i = 0; i < size; i++) data[i] = (byte)number(list, i); yield new ByteArrayTag(data); }
                case 11 -> { int[] data = new int[size]; for (int i = 0; i < size; i++) data[i] = (int)number(list, i); yield new IntArrayTag(data); }
                case 12 -> { long[] data = new long[size]; for (int i = 0; i < size; i++) data[i] = number(list, i); yield new LongArrayTag(data); }
                default -> throw new Failure(Code.INVALID_NBT);
            };
        }
        return EndTag.INSTANCE;
    }
    private static long number(DataValue.ListValue list, int i) { return ((DataValue.IntegerValue)list.values().get(i)).value(); }
    private static final class Budget {
        final ArchiveLimits limits;
        long nodes, chars;
        Budget(ArchiveLimits limits) { this.limits = limits; }
        void node(int depth) {
            if (depth >= MAX_DEPTH || ++nodes > limits.maxNodes()) throw new Failure(Code.VALUE_LIMIT);
        }
        void children(int count) { if (count > limits.maxNodes() - nodes) throw new Failure(Code.VALUE_LIMIT); }
        void text(String text) {
            if (text.length() > limits.maxStringChars() - chars) throw new Failure(Code.VALUE_LIMIT);
            chars += text.length();
        }
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int limit;
        BoundedOutput(int limit) { this.limit = limit; }
        private void reserve(int length) { if (length > limit - count) throw new Failure(Code.PAYLOAD_TOO_LARGE); }
        @Override public synchronized void write(int value) { reserve(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length); reserve(length); super.write(bytes, offset, length);
        }
    }
}
