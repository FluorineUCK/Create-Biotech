package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ProgramArchiveCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded, uncompressed editor archive envelope. */
public final class ProgramEditorWire {
	public static final int MAX_BYTES = 24 * 1024;
	private static final long MAX_NBT_ALLOCATION = 2L * 1024 * 1024;
	private ProgramEditorWire() {}

	public static byte[] encode(ProgramArchive archive, ArchiveLimits limits) {
		return encodeValue(ProgramArchiveCodec.encode(archive), limits);
	}

	public static byte[] encodeValue(DataValue value, ArchiveLimits limits) {
		return encodeValue(value, limits, MAX_BYTES);
	}
	static byte[] encodeValue(DataValue value, ArchiveLimits limits, int byteLimit) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, byteLimit));
		try {
			buffer.writeNbt(EditorValueTableCompaction.compact(ValueNbtCodec.encode(value, limits)));
			if (buffer.readableBytes() > byteLimit) throw new IllegalArgumentException("program archive is too large");
			byte[] result = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), result); return result;
		} catch (RuntimeException error) {
			if (error instanceof IllegalArgumentException invalid) throw invalid;
			throw new IllegalArgumentException("could not encode program archive", error);
		} finally { buffer.release(); }
	}

	public static ProgramArchive decode(byte[] bytes, ArchiveLimits limits) {
		return ProgramArchiveCodec.decode(decodeValue(bytes, limits));
	}

	public static DataValue decodeValue(byte[] bytes, ArchiveLimits limits) {
		return decodeValue(bytes, limits, MAX_BYTES);
	}
	static DataValue decodeValue(byte[] bytes, ArchiveLimits limits, int byteLimit) {
		if (bytes == null || bytes.length == 0 || bytes.length > byteLimit)
			throw new IllegalArgumentException("invalid program archive length");
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try {
			var root = buffer.readNbt(NbtAccounter.create(MAX_NBT_ALLOCATION));
			if (!(root instanceof CompoundTag tag) || buffer.readableBytes() != 0)
				throw new IllegalArgumentException("malformed or trailing program archive data");
			return ValueNbtCodec.decode(EditorValueTableCompaction.expand(tag), limits);
		} catch (RuntimeException error) {
			if (error instanceof IllegalArgumentException invalid) throw invalid;
			throw new IllegalArgumentException("could not decode program archive", error);
		} finally { buffer.release(); }
	}
}
