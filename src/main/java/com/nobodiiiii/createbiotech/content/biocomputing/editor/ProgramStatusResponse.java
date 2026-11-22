package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public record ProgramStatusResponse(int containerId, UUID nonce, long intent, long revision,
    String code, String detail, byte[] status) implements ClientboundPacketPayload {
    public static final int MAX_CODE = 32, MAX_DETAIL = 512;
    private static final Set<String> CODES = Set.of("ok", "stale", "invalid", "unavailable", "too_large", "busy", "memory_limit", "type_mismatch", "no_listener", "already_running");
    /** Capture is independent of whether a program/runtime snapshot exists. Never send a partial value. */
    public static ProgramStatusResponse fromAnswer(int containerId, UUID nonce, long intent,
        ComputerControlSession.Answer answer, ArchiveLimits limits) {
        String code = answer.code(), detail = answer.detail(); byte[] bytes = new byte[0];
        try {
            var fields = new LinkedHashMap<String, DataValue>();
            // READ has its own complete envelope; unrelated RAM/output must not consume its wire budget.
            if (answer.capturedInputs().isEmpty() && answer.dedicatedStatus().isEmpty())
                answer.snapshot().ifPresent(snapshot -> fields.putAll(ComputerStatusView.of(snapshot).fields()));
            answer.capturedInputs().ifPresent(captured -> fields.put("captured_inputs", captured));
            answer.dedicatedStatus().ifPresent(dedicated -> fields.putAll(dedicated.fields()));
            if (!fields.isEmpty()) {
                var value = new DataValue.RecordValue(fields);
                bytes = answer.capturedInputs().isPresent() ? BoundedEditorEnvelope.CAPTURE.encode(value, limits)
                    : ProgramEditorWire.encodeValue(value, limits);
            }
        } catch (IllegalArgumentException tooLarge) {
            code = "too_large"; detail = String.valueOf(tooLarge.getMessage()); bytes = new byte[0];
        }
        if (detail.length() > MAX_DETAIL) detail = detail.substring(0, MAX_DETAIL);
        return new ProgramStatusResponse(containerId, nonce, intent, answer.revision(), code, detail, bytes);
    }
    public ProgramStatusResponse {
        nonce = Objects.requireNonNull(nonce); code = Objects.requireNonNull(code);
        detail = Objects.requireNonNull(detail); status = Objects.requireNonNull(status).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || !CODES.contains(code)
            || detail.length() > MAX_DETAIL || status.length > ProgramEditorWire.MAX_BYTES)
            throw new IllegalArgumentException("invalid status response");
    }
    public ProgramStatusResponse(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(),
            buffer.readUtf(MAX_CODE), buffer.readUtf(MAX_DETAIL), bytes(buffer));
    }
    private static byte[] bytes(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("status too large");
        byte[] value = new byte[length]; buffer.readBytes(value); return value;
    }
    @Override public byte[] status() { return status.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeUtf(code, MAX_CODE); buffer.writeUtf(detail, MAX_DETAIL);
        buffer.writeVarInt(status.length); buffer.writeBytes(status);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.programStatusResponseType(); }
    @Override @OnlyIn(Dist.CLIENT) public void handle(LocalPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.accept(this);
    }
}
