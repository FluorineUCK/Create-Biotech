package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Stable current-schema binary wire encoding for exact remote expansion values. */
final class RemoteExpansionDigest {
	private static final int WIRE_VERSION = 1;

	private RemoteExpansionDigest() {}

	static String request(RemoteExpansionRequest request) {
		return sha256(requestWire(Objects.requireNonNull(request, "request")));
	}

	static String expansion(List<? extends RemoteExpansionStage> stages,
		Map<StackKey, Long> manifest, RemoteExpansionStackSnapshot exactStacks) {
		return sha256(expansionWire(stages, manifest, exactStacks));
	}

	static void requireRequestFits(RemoteExpansionRequest request) {
		requestWire(Objects.requireNonNull(request, "request"));
	}

	static void requireRequestFits(java.util.UUID sourceGoalId, java.util.UUID parentProcessId,
		long parentProcessGeneration, long parentStateGeneration, java.util.UUID patternId,
		int patternVersion, java.util.UUID runId, long runFence,
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode inputMode,
		long runMultiplier, List<? extends RemoteExpansionStage> stages,
		Map<StackKey, Long> dependencyManifest, RemoteExpansionStackSnapshot exactStacks) {
		requestWire(sourceGoalId, parentProcessId, parentProcessGeneration, parentStateGeneration,
			patternId, patternVersion, runId, runFence, inputMode, runMultiplier, stages,
			dependencyManifest, exactStacks);
	}

	static void requireExpansionFits(List<? extends RemoteExpansionStage> stages,
		Map<StackKey, Long> manifest, RemoteExpansionStackSnapshot exactStacks) {
		expansionWire(stages, manifest, exactStacks);
	}

	static byte[] canonicalExactStack(Tag tag) {
		return encode(out -> {
			writeText(out, "exact-stack");
			out.writeInt(WIRE_VERSION);
			writeTag(out, Objects.requireNonNull(tag, "exact stack tag"));
		});
	}

	static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(Objects.requireNonNull(value, "value")));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required", impossible);
		}
	}

	private static byte[] requestWire(RemoteExpansionRequest request) {
		return requestWire(request.sourceGoalId(), request.parentProcessId(),
			request.parentProcessGeneration(), request.parentStateGeneration(), request.patternId(),
			request.patternVersion(), request.runId(), request.runFence(), request.inputMode(),
			request.runMultiplier(), request.stages(), request.dependencyManifest(),
			request.exactStackSnapshot());
	}

	private static byte[] requestWire(java.util.UUID sourceGoalId, java.util.UUID parentProcessId,
		long parentProcessGeneration, long parentStateGeneration, java.util.UUID patternId,
		int patternVersion, java.util.UUID runId, long runFence,
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode inputMode,
		long runMultiplier, List<? extends RemoteExpansionStage> stages,
		Map<StackKey, Long> dependencyManifest, RemoteExpansionStackSnapshot exactStacks) {
		return encode(out -> {
			writeText(out, "remote-expansion-request");
			out.writeInt(WIRE_VERSION);
			writeUuid(out, sourceGoalId);
			writeUuid(out, parentProcessId);
			out.writeLong(parentProcessGeneration);
			out.writeLong(parentStateGeneration);
			writeUuid(out, patternId);
			out.writeInt(patternVersion);
			writeUuid(out, runId);
			out.writeLong(runFence);
			writeText(out, inputMode.name());
			out.writeLong(runMultiplier);
			writeStages(out, stages, exactStacks);
			writeManifest(out, dependencyManifest, exactStacks);
		});
	}

	private static byte[] expansionWire(List<? extends RemoteExpansionStage> stages,
		Map<StackKey, Long> manifest, RemoteExpansionStackSnapshot exactStacks) {
		Objects.requireNonNull(stages, "stages");
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(exactStacks, "exactStacks");
		return encode(out -> {
			writeText(out, "remote-expansion-result");
			out.writeInt(WIRE_VERSION);
			writeStages(out, stages, exactStacks);
			writeManifest(out, manifest, exactStacks);
		});
	}

	private static void writeStages(DataOutputStream out,
		List<? extends RemoteExpansionStage> stages, RemoteExpansionStackSnapshot exactStacks)
		throws IOException {
		out.writeInt(stages.size());
		for (RemoteExpansionStage stage : stages) {
			out.writeInt(stage.index());
			writeUuid(out, stage.route().logisticsNetworkId());
			writeText(out, stage.route().address());
			writeManifest(out, stage.manifest(), exactStacks);
		}
	}

	private static void writeManifest(DataOutputStream out, Map<StackKey, Long> manifest,
		RemoteExpansionStackSnapshot exactStacks) throws IOException {
		ArrayList<WireEntry> entries = new ArrayList<>(manifest.size());
		manifest.forEach((stack, count) -> entries.add(new WireEntry(
			exactStacks.exact(stack).canonicalBytes(), count)));
		entries.sort((left, right) -> {
			int stacks = Arrays.compareUnsigned(left.stack, right.stack);
			return stacks != 0 ? stacks : Long.compare(left.count, right.count);
		});
		out.writeInt(entries.size());
		for (WireEntry entry : entries) {
			out.writeInt(entry.stack.length);
			out.write(entry.stack);
			out.writeLong(entry.count);
		}
	}

	private static void writeUuid(DataOutputStream out, java.util.UUID value) throws IOException {
		out.writeLong(value.getMostSignificantBits());
		out.writeLong(value.getLeastSignificantBits());
	}

	private static void writeText(DataOutputStream out, String value) throws IOException {
		byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static void writeTag(DataOutputStream out, Tag tag) throws IOException {
		out.writeByte(tag.getId());
		switch (tag.getId()) {
			case Tag.TAG_END -> {}
			case Tag.TAG_BYTE -> out.writeByte(((NumericTag) tag).getAsByte());
			case Tag.TAG_SHORT -> out.writeShort(((NumericTag) tag).getAsShort());
			case Tag.TAG_INT -> out.writeInt(((NumericTag) tag).getAsInt());
			case Tag.TAG_LONG -> out.writeLong(((NumericTag) tag).getAsLong());
			case Tag.TAG_FLOAT -> out.writeFloat(((NumericTag) tag).getAsFloat());
			case Tag.TAG_DOUBLE -> out.writeDouble(((NumericTag) tag).getAsDouble());
			case Tag.TAG_BYTE_ARRAY -> {
				byte[] values = ((ByteArrayTag) tag).getAsByteArray();
				out.writeInt(values.length);
				out.write(values);
			}
			case Tag.TAG_STRING -> writeText(out, tag.getAsString());
			case Tag.TAG_LIST -> {
				ListTag values = (ListTag) tag;
				out.writeInt(values.size());
				for (Tag value : values) writeTag(out, value);
			}
			case Tag.TAG_COMPOUND -> {
				CompoundTag values = (CompoundTag) tag;
				List<String> keys = values.getAllKeys().stream().sorted().toList();
				out.writeInt(keys.size());
				for (String key : keys) {
					writeText(out, key);
					writeTag(out, Objects.requireNonNull(values.get(key), "compound value"));
				}
			}
			case Tag.TAG_INT_ARRAY -> {
				int[] values = ((IntArrayTag) tag).getAsIntArray();
				out.writeInt(values.length);
				for (int value : values) out.writeInt(value);
			}
			case Tag.TAG_LONG_ARRAY -> {
				long[] values = ((LongArrayTag) tag).getAsLongArray();
				out.writeInt(values.length);
				for (long value : values) out.writeLong(value);
			}
			default -> throw new IllegalArgumentException("unsupported exact-stack NBT type");
		}
	}

	private static byte[] encode(WireWriter writer) {
		BoundedBytes bytes = new BoundedBytes();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
			out.flush();
			return bytes.toByteArray();
		} catch (IOException impossible) {
			throw new IllegalStateException("in-memory remote expansion encoding failed", impossible);
		}
	}

	private record WireEntry(byte[] stack, long count) {}

	@FunctionalInterface
	private interface WireWriter {
		void write(DataOutputStream out) throws IOException;
	}

	private static final class BoundedBytes extends OutputStream {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);

		@Override
		public void write(int value) {
			requireCapacity(1);
			bytes.write(value);
		}

		@Override
		public void write(byte[] values, int offset, int length) {
			Objects.checkFromIndexSize(offset, length, values.length);
			requireCapacity(length);
			bytes.write(values, offset, length);
		}

		private void requireCapacity(int added) {
			if (added < 0 || bytes.size() > RemoteExpansionRequest.MAX_SERIALIZED_BYTES - added)
				throw new IllegalArgumentException(
					"remote expansion full wire payload exceeds its protocol bound");
		}

		private byte[] toByteArray() {
			return bytes.toByteArray();
		}
	}

	interface RemoteExpansionStage {
		int index();
		com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey route();
		Map<StackKey, Long> manifest();
	}
}
