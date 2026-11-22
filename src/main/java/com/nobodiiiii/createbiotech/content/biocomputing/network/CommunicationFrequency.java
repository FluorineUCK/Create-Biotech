package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * A literal, bounded communication frequency. Its value is intentionally never
 * trimmed, normalized, case-folded, or interpreted as a pattern.
 */
public record CommunicationFrequency(String value) {
	public static final int MAX_LENGTH = 25;
	public static final CommunicationFrequency UNCONFIGURED = new CommunicationFrequency("");
	private static final int PACKET_MAGIC = 0x4346_5131; // CFQ1
	private static final int PACKET_FORMAT_VERSION = 1;
	private static final int MAX_UTF8_BYTES = MAX_LENGTH * 3;

	public CommunicationFrequency {
		value = Objects.requireNonNull(value, "value");
		if (value.length() > MAX_LENGTH)
			throw new IllegalArgumentException("frequency exceeds 25 characters");
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index)))
					throw new IllegalArgumentException("frequency contains an unpaired UTF-16 surrogate");
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException("frequency contains an unpaired UTF-16 surrogate");
			}
		}
	}

	public static CommunicationFrequency of(String raw) {
		return new CommunicationFrequency(raw);
	}

	public boolean configured() {
		return !value.isBlank();
	}

	/** Writes a self-identifying current-schema packet value; legacy integer bytes fail closed. */
	public void write(RegistryFriendlyByteBuf buffer) {
		Objects.requireNonNull(buffer, "buffer");
		byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		if (utf8.length > MAX_UTF8_BYTES)
			throw new IllegalStateException("frequency UTF-8 representation exceeds packet bound");
		buffer.writeInt(PACKET_MAGIC);
		buffer.writeByte(PACKET_FORMAT_VERSION);
		buffer.writeVarInt(utf8.length);
		buffer.writeBytes(utf8);
	}

	public static CommunicationFrequency read(RegistryFriendlyByteBuf buffer) {
		Objects.requireNonNull(buffer, "buffer");
		if (buffer.readableBytes() < Integer.BYTES + Byte.BYTES + Byte.BYTES)
			throw new IllegalArgumentException("truncated communication frequency packet");
		if (buffer.readInt() != PACKET_MAGIC)
			throw new IllegalArgumentException("unsupported communication frequency packet format");
		if (buffer.readUnsignedByte() != PACKET_FORMAT_VERSION)
			throw new IllegalArgumentException("unsupported communication frequency packet version");
		int utf8Length;
		try {
			utf8Length = buffer.readVarInt();
		} catch (IndexOutOfBoundsException invalid) {
			throw new IllegalArgumentException("truncated communication frequency byte length", invalid);
		}
		if (utf8Length < 0 || utf8Length > MAX_UTF8_BYTES || buffer.readableBytes() < utf8Length)
			throw new IllegalArgumentException("invalid communication frequency byte length");
		byte[] utf8 = new byte[utf8Length];
		buffer.readBytes(utf8);
		try {
			return of(StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(utf8)).toString());
		} catch (CharacterCodingException invalid) {
			throw new IllegalArgumentException("malformed communication frequency UTF-8", invalid);
		}
	}

	public void write(CompoundTag tag, String key) {
		Objects.requireNonNull(tag, "tag");
		tag.putString(Objects.requireNonNull(key, "key"), value);
	}

	/** Only a current-schema NBT string is accepted; legacy integer frequencies are not migrated. */
	public static Optional<CommunicationFrequency> read(CompoundTag tag, String key) {
		Objects.requireNonNull(tag, "tag");
		key = Objects.requireNonNull(key, "key");
		if (!tag.contains(key, Tag.TAG_STRING))
			return Optional.empty();
		try {
			return Optional.of(of(tag.getString(key)));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}
}
