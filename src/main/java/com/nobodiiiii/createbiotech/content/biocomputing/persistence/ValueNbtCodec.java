package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.nbt.*;

/** Versioned, flat immutable value table. References only point backwards; NBT depth is constant. */
public final class ValueNbtCodec {
	private ValueNbtCodec() {}

	public static CompoundTag encode(DataValue value, ArchiveLimits limits) {com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(value);return encode(value,limits,false);}
    static CompoundTag encodeCheckpoint(DataValue value,ArchiveLimits limits){return encode(value,limits,true);}
    private static CompoundTag encode(DataValue value, ArchiveLimits limits,boolean checkpoint) {
		var budget = new Budget(limits);
		budget.value(value);
		var ids = new IdentityHashMap<DataValue, Integer>();
		var rows = new ListTag();
		boolean nativeNbt = false, semantic = false;
		record Visit(DataValue value, boolean expanded) {}
		var pending = new ArrayDeque<Visit>();
		pending.push(new Visit(value, false));
		while (!pending.isEmpty()) {
			var visit = pending.pop();
			if (ids.containsKey(visit.value())) continue;
			if (!visit.expanded()) {
				pending.push(new Visit(visit.value(), true));
				List<DataValue> children = switch (visit.value()) {
					case DataValue.RecipeValue recipe -> List.of(recipe.projection());
					case DataValue.NbtValue nbt -> List.of(nbt.value());
					case DataValue.ListValue list -> list.values();
					case DataValue.RecordValue record -> List.copyOf(record.fields().values());
					case DataValue.MapValue map -> List.copyOf(map.entries().values());
					default -> List.of();
				};
				for (int i = children.size() - 1; i >= 0; i--)
					if (!ids.containsKey(children.get(i))) pending.push(new Visit(children.get(i), false));
				continue;
			}
			if (rows.size() == limits.maxNodes()) throw ArchiveException.limit();
			var row = new CompoundTag();
			switch (visit.value()) {
                case DataValue.LocalReference ref -> {if(!checkpoint)throw ArchiveException.invalid("Local reference is not portable");row.putString("Kind","local_ref");row.put("Descriptor",encode(ref.descriptor(),limits));}
				case DataValue.RecipeValue recipe -> {
                    semantic = true; row.putString("Kind", "recipe"); row.putString("Fmt", recipe.fmt()); row.putInt("SemanticVersion", recipe.version()); row.putInt("Value", ids.get(recipe.projection()));
                }
				case DataValue.NbtValue nbt -> {
					nativeNbt = true; row.putString("Kind", "nbt");
					row.putInt("Tag", nbt.tagId()); row.putInt("Value", ids.get(nbt.value()));
				}
				case DataValue.NullValue ignored -> row.putString("Kind", "null");
				case DataValue.BooleanValue bool -> { row.putString("Kind", "boolean"); row.putBoolean("Value", bool.value()); }
				case DataValue.IntegerValue integer -> { row.putString("Kind", "integer"); row.putLong("Value", integer.value()); }
				case DataValue.DecimalValue decimal -> {
					row.putString("Kind", "decimal");
					// DoubleTag canonicalizes -0.0 to +0.0, including when loading NBT.
					row.putLong("Value", Double.doubleToRawLongBits(decimal.value()));
				}
				case DataValue.TextValue text -> { row.putString("Kind", "text"); row.putByteArray("Value", utf16(text.value(), budget)); }
				case DataValue.ErrorValue error -> {
					row.putString("Kind", "error"); row.putByteArray("Code", utf16(error.code(), budget));
					row.putByteArray("Message", utf16(error.message(), budget));
				}
				case DataValue.ListValue list -> {
					row.putString("Kind", "list");
					row.putIntArray("Items", list.values().stream().mapToInt(ids::get).toArray());
				}
				case DataValue.RecordValue record -> { row.putString("Kind", "record"); row.put("Fields", fields(record.fields(), ids, budget)); }
				case DataValue.MapValue map -> { row.putString("Kind", "map"); row.put("Fields", fields(map.entries(), ids, budget)); }
			}
			ids.put(visit.value(), rows.size());
			rows.add(row);
		}
		var result = new CompoundTag();
		result.putInt("Version", value.localReferences()!=0?5:semantic ? 4 : nativeNbt ? 3 : 2); result.putInt("Root", ids.get(value)); result.put("Values", rows);
		return result;
	}

	public static DataValue decode(CompoundTag tag, ArchiveLimits limits) {return decode(tag,limits,false);}
    static DataValue decodeCheckpoint(CompoundTag tag,ArchiveLimits limits){return decode(tag,limits,true);}
    private static DataValue decode(CompoundTag tag, ArchiveLimits limits,boolean checkpoint) {
		var budget = new Budget(limits);
		int version = integer(tag, "Version");
		if (version < 1 || version > (checkpoint?5:4))
			throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported value archive version");
		var rows = compounds(tag, "Values");
		if (rows.isEmpty() || rows.size() > limits.maxNodes()) throw ArchiveException.limit();
		int root = integer(tag, "Root");
		if (root != rows.size() - 1) throw ArchiveException.invalid("Root must be the last value");
		var values = new ArrayList<DataValue>(rows.size());
		try {
			for (Tag entry : rows) {
				var row = (CompoundTag) entry;
				DataValue value = switch (string(row, "Kind")) {
					case "local_ref" -> {
                        if(!checkpoint||version!=5||!row.getAllKeys().equals(Set.of("Kind","Descriptor")))throw ArchiveException.invalid("Invalid checkpoint reference");require(row,"Descriptor",Tag.TAG_COMPOUND);
                        var data=decode(row.getCompound("Descriptor"),limits);
                        if(!(data instanceof DataValue.RecordValue r)||!r.fields().keySet().equals(Set.of("computer","generation","image","context","cell","target","writable")))throw ArchiveException.invalid("Invalid reference descriptor");
                        yield new DataValue.LocalReference(UUID.fromString(ArchiveData.string(r.field("computer"))),ArchiveData.number(r.field("generation")),ArchiveData.string(r.field("image")),UUID.fromString(ArchiveData.string(r.field("context"))),ArchiveData.index(r.field("cell")),new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.RefType(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.valueOf(ArchiveData.string(r.field("target"))),ArchiveData.bool(r.field("writable"))));
                    }
                    case "recipe" -> {
                        if(version < 4) throw ArchiveException.invalid("Recipe requires value version 4");
                        var projection=reference(values, integer(row, "Value"));
                        if(!(projection instanceof DataValue.RecordValue record)) throw ArchiveException.invalid("Recipe projection must be a record");
                        yield new DataValue.RecipeValue(string(row,"Fmt"), integer(row,"SemanticVersion"),record);
                    }
					case "nbt" -> {
						if (version < 3) throw ArchiveException.invalid("Native NBT requires value version 3");
						yield new DataValue.NbtValue(integer(row, "Tag"), reference(values, integer(row, "Value")));
					}
					case "null" -> DataValue.NullValue.INSTANCE;
					case "boolean" -> {
						require(row, "Value", Tag.TAG_BYTE);
						byte b = row.getByte("Value");
						if (b != 0 && b != 1) throw ArchiveException.invalid("Invalid boolean");
						yield new DataValue.BooleanValue(b == 1);
					}
					case "integer" -> { require(row, "Value", Tag.TAG_LONG); yield new DataValue.IntegerValue(row.getLong("Value")); }
					case "decimal" -> {
						require(row, "Value", version == 1 ? Tag.TAG_DOUBLE : Tag.TAG_LONG);
						yield new DataValue.DecimalValue(version == 1 ? row.getDouble("Value")
							: Double.longBitsToDouble(row.getLong("Value")));
					}
					case "text" -> new DataValue.TextValue(readText(row, "Value", budget));
					case "error" -> new DataValue.ErrorValue(readText(row, "Code", budget), readText(row, "Message", budget));
					case "list" -> {
						require(row, "Items", Tag.TAG_INT_ARRAY);
						int[] refs = row.getIntArray("Items");
						if (refs.length >= limits.maxValueUnits()) throw ArchiveException.limit();
						var children = new ArrayList<DataValue>(refs.length);
						for (int ref : refs) children.add(reference(values, ref));
						yield new DataValue.ListValue(children);
					}
					case "record" -> new DataValue.RecordValue(readFields(row, values, budget));
					case "map" -> new DataValue.MapValue(readFields(row, values, budget));
					default -> throw ArchiveException.invalid("Unknown value kind");
				};
				budget.value(value);
				values.add(value);
			}
		} catch (ComputationException overflow) {
			throw ArchiveException.limit();
		} catch (IllegalArgumentException invalid) {
			if (invalid instanceof ArchiveException archive) throw archive;
			throw ArchiveException.invalid("Invalid value: " + invalid.getMessage());
		}
		return values.get(root);
	}

	private static ListTag fields(Map<String, DataValue> fields, IdentityHashMap<DataValue, Integer> ids, Budget budget) {
		var result = new ListTag();
		fields.forEach((name, value) -> {
			var entry = new CompoundTag();
			entry.putByteArray("Name", utf16(name, budget)); entry.putInt("Value", ids.get(value)); result.add(entry);
		});
		return result;
	}
	private static Map<String, DataValue> readFields(CompoundTag row, List<DataValue> values, Budget budget) {
		var result = new LinkedHashMap<String, DataValue>();
		var entries = compounds(row, "Fields");
		if (entries.size() >= budget.limits.maxValueUnits()) throw ArchiveException.limit();
		for (Tag entry : entries) {
			var field = (CompoundTag) entry;
			String name = readText(field, "Name", budget);
			if (result.putIfAbsent(name, reference(values, integer(field, "Value"))) != null)
				throw ArchiveException.invalid("Duplicate field: " + name);
		}
		return result;
	}
	private static DataValue reference(List<DataValue> values, int ref) {
		if (ref < 0 || ref >= values.size()) throw ArchiveException.invalid("Invalid or forward value reference");
		return values.get(ref);
	}
	/** Raw UTF-16 code units avoid NBT's 65,535-byte modified-UTF limit and preserve lone surrogates. */
	private static byte[] utf16(String text, Budget budget) {
		budget.characters(text.length());
		if (text.length() > Integer.MAX_VALUE / 2) throw ArchiveException.limit();
		byte[] bytes = new byte[text.length() * 2];
		for (int i = 0; i < text.length(); i++) {
			bytes[2 * i] = (byte) (text.charAt(i) >>> 8); bytes[2 * i + 1] = (byte) text.charAt(i);
		}
		return bytes;
	}
	private static String readText(CompoundTag tag, String key, Budget budget) {
		require(tag, key, Tag.TAG_BYTE_ARRAY);
		byte[] bytes = tag.getByteArray(key);
		if (bytes.length % 2 != 0) throw ArchiveException.invalid("Invalid UTF-16 payload");
		budget.characters(bytes.length / 2);
		char[] chars = new char[bytes.length / 2];
		for (int i = 0; i < chars.length; i++) chars[i] = (char) ((bytes[2 * i] & 255) << 8 | bytes[2 * i + 1] & 255);
		return new String(chars);
	}
	private static void require(CompoundTag tag, String key, int type) {
		if (!tag.contains(key, type)) throw ArchiveException.invalid("Missing or wrongly typed NBT field: " + key);
	}
	private static int integer(CompoundTag tag, String key) { require(tag, key, Tag.TAG_INT); return tag.getInt(key); }
	private static String string(CompoundTag tag, String key) { require(tag, key, Tag.TAG_STRING); return tag.getString(key); }
	private static ListTag compounds(CompoundTag tag, String key) {
		require(tag, key, Tag.TAG_LIST);
		var list = (ListTag) tag.get(key);
		if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
			throw ArchiveException.invalid("Expected compound list: " + key);
		return list;
	}
	private static final class Budget {
		private final ArchiveLimits limits;
		private long chars;
		Budget(ArchiveLimits limits) { this.limits = Objects.requireNonNull(limits); }
		void characters(int count) {
			if (count > limits.maxStringChars() - chars) throw ArchiveException.limit();
			chars += count;
		}
		void value(DataValue value) { if (value.storageUnits() > limits.maxValueUnits()) throw ArchiveException.limit(); }
	}
}
