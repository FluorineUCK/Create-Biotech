package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Canonical, bounded fingerprint encoder for the sole current recovery schema. */
final class CurrentSchemaEvidenceDigest {
	private static final int MAX_DEPTH = 64;
	private static final int MAX_UTF8_BYTES = 1 << 20;

	private CurrentSchemaEvidenceDigest() {}

	static String sha256(RemoteAgentRecord record) {
		try {
			Encoder encoder = new Encoder();
			encoder.value(Objects.requireNonNull(record, "record"), 0);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoder.bytes());
			return java.util.HexFormat.of().formatHex(digest);
		} catch (IOException | ReflectiveOperationException | NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Could not encode current recovery evidence", failure);
		}
	}

	private static final class Encoder {
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final DataOutputStream out = new DataOutputStream(buffer);

		byte[] bytes() { return buffer.toByteArray(); }

		void value(Object value, int depth) throws IOException, ReflectiveOperationException {
			if (depth > MAX_DEPTH) throw new IllegalArgumentException("Evidence nesting exceeds bound");
			if (value == null) { out.writeByte(0); return; }
			if (value instanceof Boolean item) { out.writeByte(1); out.writeBoolean(item); return; }
			if (value instanceof Byte item) { out.writeByte(2); out.writeByte(item); return; }
			if (value instanceof Short item) { out.writeByte(3); out.writeShort(item); return; }
			if (value instanceof Integer item) { out.writeByte(4); out.writeInt(item); return; }
			if (value instanceof Long item) { out.writeByte(5); out.writeLong(item); return; }
			if (value instanceof Float item) { out.writeByte(6); out.writeInt(Float.floatToIntBits(item)); return; }
			if (value instanceof Double item) { out.writeByte(7); out.writeLong(Double.doubleToLongBits(item)); return; }
			if (value instanceof Character item) { out.writeByte(8); out.writeChar(item); return; }
			if (value instanceof String item) { out.writeByte(9); text(item); return; }
			if (value instanceof UUID item) {
				out.writeByte(10); out.writeLong(item.getMostSignificantBits());
				out.writeLong(item.getLeastSignificantBits()); return;
			}
			if (value instanceof Enum<?> item) {
				out.writeByte(11); text(item.getDeclaringClass().getName()); text(item.name()); return;
			}
			if (value instanceof Optional<?> item) {
				out.writeByte(12); out.writeBoolean(item.isPresent());
				if (item.isPresent()) value(item.orElseThrow(), depth + 1); return;
			}
			if (value instanceof OptionalLong item) {
				out.writeByte(20); out.writeBoolean(item.isPresent());
				if (item.isPresent()) out.writeLong(item.orElseThrow());
				return;
			}
			if (value instanceof StackKey item) { stack(item.stack()); return; }
			if (value instanceof ItemStack item) { stack(item); return; }
			if (value instanceof NativePackageObservation item) {
				out.writeByte(15); text(NativePackageObservation.class.getName());
				value(item.route(), depth + 1); value(item.hasOrderData(), depth + 1);
				value(item.orderId(), depth + 1); value(item.linkIndex(), depth + 1);
				value(item.fragmentIndex(), depth + 1); value(item.isFinalLink(), depth + 1);
				value(item.isFinal(), depth + 1); value(item.orderContext(), depth + 1);
				value(item.acceptedStacks(), depth + 1); return;
			}
			if (value instanceof Map<?, ?> item) { map(item, depth); return; }
			if (value instanceof Set<?> item) { unordered(item, depth, (byte) 17); return; }
			if (value instanceof List<?> item) { ordered(item, depth); return; }
			if (value.getClass().isArray()) {
				if (value instanceof byte[] bytes) {
					out.writeByte(19); boundedSize(bytes.length); out.write(bytes); return;
				}
				ordered(Arrays.asList((Object[]) value), depth); return;
			}
			if (value.getClass().isRecord()) { record(value, depth); return; }
			throw new IllegalArgumentException("Unsupported current-schema evidence type: "
				+ value.getClass().getName());
		}

		private void stack(ItemStack stack) throws IOException {
			out.writeByte(13);
			Tag exact = stack.copyWithCount(1).saveOptional(RegistryAccess.EMPTY);
			text(canonicalNbt(exact));
		}

		private void record(Object record, int depth)
			throws IOException, ReflectiveOperationException {
			out.writeByte(14); text(record.getClass().getName());
			RecordComponent[] components = record.getClass().getRecordComponents();
			boundedSize(components.length);
			for (RecordComponent component : components) {
				text(component.getName());
				value(component.getAccessor().invoke(record), depth + 1);
			}
		}

		private void ordered(List<?> values, int depth)
			throws IOException, ReflectiveOperationException {
			out.writeByte(16); boundedSize(values.size());
			for (Object value : values) value(value, depth + 1);
		}

		private void unordered(Iterable<?> values, int depth, byte tag)
			throws IOException, ReflectiveOperationException {
			ArrayList<byte[]> encoded = new ArrayList<>();
			for (Object value : values) encoded.add(encoded(value, depth + 1));
			boundedSize(encoded.size()); encoded.sort(CurrentSchemaEvidenceDigest::compareUnsigned);
			out.writeByte(tag); out.writeInt(encoded.size());
			for (byte[] item : encoded) { out.writeInt(item.length); out.write(item); }
		}

		private void map(Map<?, ?> values, int depth)
			throws IOException, ReflectiveOperationException {
			ArrayList<MapEntry> encoded = new ArrayList<>();
			for (Map.Entry<?, ?> entry : values.entrySet())
				encoded.add(new MapEntry(encoded(entry.getKey(), depth + 1),
					encoded(entry.getValue(), depth + 1)));
			boundedSize(encoded.size()); encoded.sort(Comparator.comparing(MapEntry::key,
				CurrentSchemaEvidenceDigest::compareUnsigned));
			out.writeByte(18); out.writeInt(encoded.size());
			for (MapEntry entry : encoded) {
				out.writeInt(entry.key.length); out.write(entry.key);
				out.writeInt(entry.value.length); out.write(entry.value);
			}
		}

		private byte[] encoded(Object value, int depth)
			throws IOException, ReflectiveOperationException {
			Encoder nested = new Encoder(); nested.value(value, depth); return nested.bytes();
		}

		private void text(String value) throws IOException {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > MAX_UTF8_BYTES) throw new IllegalArgumentException("Evidence text exceeds bound");
			out.writeInt(bytes.length); out.write(bytes);
		}

		private static void boundedSize(int size) {
			if (size < 0 || size > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("Evidence collection exceeds bound");
		}
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		return Arrays.compareUnsigned(left, right);
	}

	private static String canonicalNbt(Tag tag) {
		StringBuilder result = new StringBuilder();
		canonicalNbt(tag, result);
		return result.toString();
	}

	private static void canonicalNbt(Tag tag, StringBuilder result) {
		result.append(tag.getId()).append(':');
		if (tag instanceof CompoundTag compound) {
			compound.getAllKeys().stream().sorted().forEach(key -> {
				result.append(key.length()).append(':').append(key).append('=');
				canonicalNbt(Objects.requireNonNull(compound.get(key)), result);
			});
			return;
		}
		if (tag instanceof ListTag list) {
			result.append(list.size()).append('[');
			for (Tag child : list) canonicalNbt(child, result);
			result.append(']');
			return;
		}
		String value = tag.getAsString();
		result.append(value.length()).append(':').append(value);
	}

	private record MapEntry(byte[] key, byte[] value) {}
}
