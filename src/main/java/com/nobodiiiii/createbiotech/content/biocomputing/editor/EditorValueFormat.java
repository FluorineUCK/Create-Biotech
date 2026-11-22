package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Optional literal sugar, not a program language. Duplicate keys and lossy coercions are errors. */
public final class EditorValueFormat {
	public enum Kind { INTEGER, DECIMAL, TEXT, BOOLEAN, JSON, NBT }
	private static final int MAX_DEPTH = 64, MAX_CHARS = 24 * 1024;
	private static final NativeNbtCodec.Limits NBT_LIMITS = new NativeNbtCodec.Limits(MAX_CHARS * 4, MAX_CHARS * 64L,
		new ArchiveLimits(MAX_CHARS, MAX_CHARS, MAX_CHARS * 4L));
	private EditorValueFormat() {}
	public static DataValue parse(Kind kind, String text) {
		if (text.length() > MAX_CHARS) throw new IllegalArgumentException("Literal is too large");
		return switch (kind) {
			case NBT -> parseNbt(text);
			case INTEGER -> new DataValue.IntegerValue(Long.parseLong(text));
			case DECIMAL -> new DataValue.DecimalValue(Double.parseDouble(text));
			case TEXT -> new DataValue.TextValue(text);
			case BOOLEAN -> {
				if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("Expected true or false");
				yield new DataValue.BooleanValue(Boolean.parseBoolean(text));
			}
			case JSON -> {
				try (var reader = new JsonReader(new StringReader(text))) {
					reader.setLenient(false);
					DataValue value = read(reader, 0);
					if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing literal data");
					yield value;
				} catch (IOException | IllegalStateException error) { throw new IllegalArgumentException("Invalid JSON literal", error); }
			}
		};
	}
	private static DataValue read(JsonReader reader, int depth) throws IOException {
		if (depth >= MAX_DEPTH) throw new IllegalArgumentException("Literal is too deeply nested");
		return switch (reader.peek()) {
			case NULL -> { reader.nextNull(); yield DataValue.NullValue.INSTANCE; }
			case BOOLEAN -> new DataValue.BooleanValue(reader.nextBoolean());
			case STRING -> new DataValue.TextValue(reader.nextString());
			case NUMBER -> {
				String number = reader.nextString();
				yield number.matches("-?[0-9]+") ? new DataValue.IntegerValue(Long.parseLong(number))
					: new DataValue.DecimalValue(Double.parseDouble(number));
			}
			case BEGIN_ARRAY -> {
				var values = new ArrayList<DataValue>(); reader.beginArray();
				while (reader.hasNext()) values.add(read(reader, depth + 1)); reader.endArray();
				yield new DataValue.ListValue(values);
			}
			case BEGIN_OBJECT -> {
				var fields = new LinkedHashMap<String, DataValue>(); reader.beginObject();
				while (reader.hasNext()) {
					String key = reader.nextName();
					if (fields.containsKey(key)) throw new IllegalArgumentException("Duplicate field: " + key);
					fields.put(key, read(reader, depth + 1));
				}
				reader.endObject(); yield new DataValue.RecordValue(fields);
			}
			default -> throw new IllegalArgumentException("Expected a data literal");
		};
	}
	public static String format(DataValue value) {
		if (value instanceof DataValue.NbtValue nbt) {
			String literal = NativeNbtCodec.toTag(nbt, NBT_LIMITS).toString();
			// SNBT is editor sugar, not the wire format. Refuse a lossy edit (e.g. native non-finite floats).
			if (!parse(Kind.NBT, literal).equals(value)) throw new IllegalArgumentException("NBT has no lossless SNBT literal");
			return literal;
		}
		try {
			var output = new StringWriter();
			try (var writer = new JsonWriter(output)) { writer.setSerializeNulls(true); write(writer, value, 0); }
			return output.toString();
		} catch (IOException error) { throw new IllegalArgumentException("Cannot format value", error); }
	}
	private static DataValue.NbtValue parseNbt(String text) {
		// Bound nesting before calling Minecraft's recursive SNBT parser; brackets in quotes are data.
		int depth = 0; char quote = 0; boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quote != 0) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == quote) quote = 0;
			} else if (c == '\'' || c == '"') quote = c;
			else if (c == '{' || c == '[') { if (++depth >= MAX_DEPTH) throw new IllegalArgumentException("NBT literal is too deeply nested"); }
			else if (c == '}' || c == ']') depth--;
		}
		try {
			var reader = new com.mojang.brigadier.StringReader(text);
			var tag = new net.minecraft.nbt.TagParser(reader).readValue();
			reader.skipWhitespace(); if (reader.canRead()) throw new IllegalArgumentException("Trailing NBT literal data");
			return NativeNbtCodec.snapshot(tag, NBT_LIMITS);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { throw new IllegalArgumentException("Invalid SNBT literal"); }
	}
	private static void write(JsonWriter writer, DataValue value, int depth) throws IOException {
		if (depth >= MAX_DEPTH) throw new IllegalArgumentException("Value is too deeply nested for literal editing");
		switch (value) {
			case DataValue.NullValue ignored -> writer.nullValue();
			case DataValue.BooleanValue v -> writer.value(v.value());
			case DataValue.IntegerValue v -> writer.value(v.value());
			case DataValue.DecimalValue v -> writer.value(v.value());
			case DataValue.TextValue v -> writer.value(v.value());
			case DataValue.ListValue v -> { writer.beginArray(); for (var item : v.values()) write(writer,item,depth+1); writer.endArray(); }
			case DataValue.RecordValue v -> { writer.beginObject(); for (var entry : v.fields().entrySet()) { writer.name(entry.getKey()); write(writer,entry.getValue(),depth+1); } writer.endObject(); }
			default -> throw new IllegalArgumentException("This value has no lossless JSON literal representation");
		}
	}
}
