package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record ProgramEditorResponse(int containerId, UUID nonce, long intent, long revision, String code,
	String detail, ResourceLimits hardware, ArchiveLimits archiveLimits, byte[] archive, Boolean nativeIsa1b, HardwarePins pins) implements ClientboundPacketPayload {
	public static final int MAX_CODE = 32, MAX_DETAIL = 512;
	private static final Set<String> CODES = Set.of("ready", "applied", "queued", "stale", "invalid", "unavailable", "too_large");
	public ProgramEditorResponse {
		nonce = Objects.requireNonNull(nonce); code = Objects.requireNonNull(code); detail = Objects.requireNonNull(detail);
		hardware = Objects.requireNonNull(hardware); archiveLimits = Objects.requireNonNull(archiveLimits); pins = Objects.requireNonNull(pins);
		archive = Objects.requireNonNull(archive).clone();
		if (containerId < 0 || intent < 0 || revision < 0 || !CODES.contains(code) || code.length() > MAX_CODE
			|| detail.length() > MAX_DETAIL || archive.length > ProgramEditorWire.MAX_BYTES)
			throw new IllegalArgumentException("invalid program editor response");
	}
    /** Compatibility callers have no observed server capability. */
    public ProgramEditorResponse(int containerId, UUID nonce, long intent, long revision, String code, String detail,
        ResourceLimits hardware, ArchiveLimits archiveLimits, byte[] archive, HardwarePins pins) {
        this(containerId, nonce, intent, revision, code, detail, hardware, archiveLimits, archive, null, pins);
    }
    ProgramEditorResponse reissueSource(long newIntent, com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection selected) {
        return new ProgramEditorResponse(containerId, nonce, newIntent, revision, code, detail, hardware, archiveLimits, archive, capability(selected), pins);
    }

    static Boolean capability(com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection selected) {
        return selected.ready() ? selected.limits().isa1b() : null;
    }
	public ProgramEditorResponse(int containerId, UUID nonce, long intent, long revision, String code, String detail,
		ResourceLimits hardware, ArchiveLimits archiveLimits, byte[] archive) {
		this(containerId, nonce, intent, revision, code, detail, hardware, archiveLimits, archive, HardwarePins.DEFAULT);
	}
	public ProgramEditorResponse(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readUtf(MAX_CODE),
			buffer.readUtf(MAX_DETAIL), new ResourceLimits(buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarInt()),
			new ArchiveLimits(buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong()), readArchive(buffer), readCapability(buffer), readPins(buffer));
	}
    private static Boolean readCapability(RegistryFriendlyByteBuf buffer) {
        return switch (buffer.readByte()) { case 0 -> null; case 1 -> false; case 2 -> true;
            default -> throw new IllegalArgumentException("Invalid native ISA-1b capability"); };
    }
	private static HardwarePins readPins(RegistryFriendlyByteBuf buffer) {
		return new HardwarePins(readPinList(buffer), readPinList(buffer));
	}
	private static java.util.List<String> readPinList(RegistryFriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		if (size < 0 || size > HardwarePins.MAX_PINS) throw new IllegalArgumentException("Invalid hardware pin count");
		var names = new java.util.ArrayList<String>(size);
		for (int i = 0; i < size; i++) names.add(buffer.readUtf(HardwarePins.MAX_NAME));
		return names;
	}
	private static byte[] readArchive(RegistryFriendlyByteBuf buffer) {
		int length = buffer.readVarInt();
		if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("program archive is too large");
		byte[] result = new byte[length]; buffer.readBytes(result); return result;
	}
	@Override public byte[] archive() { return archive.clone(); }
	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
		buffer.writeUtf(code, MAX_CODE); buffer.writeUtf(detail, MAX_DETAIL);
		buffer.writeVarLong(hardware.programUnits()); buffer.writeVarLong(hardware.memoryUnits()); buffer.writeVarLong(hardware.workPerSlice()); buffer.writeVarInt(hardware.registerSlots());
		buffer.writeVarInt(archiveLimits.maxNodes()); buffer.writeVarLong(archiveLimits.maxStringChars()); buffer.writeVarLong(archiveLimits.maxValueUnits());
		buffer.writeVarInt(archive.length); buffer.writeBytes(archive);
		buffer.writeByte(nativeIsa1b == null ? 0 : nativeIsa1b ? 2 : 1);
		buffer.writeVarInt(pins.xbus().size()); for (String pin : pins.xbus()) buffer.writeUtf(pin, HardwarePins.MAX_NAME);
		buffer.writeVarInt(pins.simpleIo().size()); for (String pin : pins.simpleIo()) buffer.writeUtf(pin, HardwarePins.MAX_NAME);
	}
	@Override public PacketTypeProvider getTypeProvider() { return CBPackets.programEditorResponseType(); }
	@Override @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
	public void handle(LocalPlayer player) {
		if (player.containerMenu instanceof ComputerProgramMenu menu) menu.accept(this);
	}
}
