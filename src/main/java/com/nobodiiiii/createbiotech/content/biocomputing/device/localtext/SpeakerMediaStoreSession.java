package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFileRam;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookVolumes;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Explicit manual driver using the real serial interfaces. No direct writes to book or drive. */
final class SpeakerMediaStoreSession {
    private static final int CHUNK = 64;
    private final SpeakerMediaStoreRequest address;
    private final ComputerFileRam.Lease encoded;
    private final LibraryBlockEntity library;
    private final SharedSerialChannel.Port peer;
    private final SpeakerMediaLibraryTransfer transfer;
    private final long timeoutTicks;
    private long lastProgress;
    private boolean awaiting;

    static SpeakerMediaStoreSession start(ServerPlayer player, ComputerBehaviour computer, SpeakerMediaStoreRequest request) {
        LibraryBookVolumes.requireName(request.volume());
        LibraryBookVolumes.requireName(request.entry());
        if (request.slot() < 0 || request.slot() >= 6 || request.port().isBlank()
            || Math.abs(request.track().getX()) > 1024 || Math.abs(request.track().getY()) > 1024
            || Math.abs(request.track().getZ()) > 1024) throw new IllegalArgumentException("invalid_address");
        var level = player.serverLevel();
        if (!level.getWorldBorder().isWithinBounds(request.library()) || !level.hasChunkAt(request.library()) || !level.mayInteract(player, request.library())
            || !(level.getBlockEntity(request.library()) instanceof LibraryBlockEntity library))
            throw new IllegalArgumentException("library_unavailable");
        var peer = peer(computer, library, request.port());
        var serial = computer.serialDevice();
        var status = serial.status(request.port());
        if (!status.connection().equals("ready") || serial.attachments().stream().noneMatch(a -> a.name().equals(request.port())))
            throw new IllegalArgumentException("disconnected");
        if (status.receive().frames() != 0 || Set.of("queued", "transmitting").contains(status.transmit()))
            throw new IllegalArgumentException("busy");
        if (computer.installedProgram().filter(p -> !p.paused()).isPresent())
            throw new IllegalArgumentException("pause_program");

        var encoded = computer.prepareStagedFileTransfer(player.getUUID(), request.file())
            .orElseThrow(() -> new IllegalArgumentException("media_ram_unavailable"));
        try { return new SpeakerMediaStoreSession(request, library, peer, encoded, level.getGameTime()); }
        catch (RuntimeException failed) { encoded.abort(); throw failed; }
    }

    private SpeakerMediaStoreSession(SpeakerMediaStoreRequest address, LibraryBlockEntity library,
            SharedSerialChannel.Port peer, ComputerFileRam.Lease encoded, long tick) {
        this.address = address; this.library = library; this.peer = peer; this.encoded = encoded;
        var p = address.library();
        var t = address.track();
        transfer = new SpeakerMediaLibraryTransfer(UUID.randomUUID(), new LibraryTrackAddress(p.getX(), p.getY(), p.getZ()),
            new LibraryTrackAddress(t.getX(), t.getY(), t.getZ()), address.slot(), address.volume(), address.entry(),
            CHUNK, encoded.readyBytes(), encoded::slice);
        var config = CBConfigs.SERVER.biocomputing;
        boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        long frame = config.serialCodec(loaded).maxEncodedBytes();
        long bits = 8 * frame + config.serialMedium(loaded).framingBits();
        // Allow a full round trip even when a slow endpoint is set to one bit per tick.
        timeoutTicks = Math.max(200, Math.min(Long.MAX_VALUE / 4, bits) * 4);
        lastProgress = tick;
    }

    private static SharedSerialChannel.Port peer(ComputerBehaviour computer, LibraryBlockEntity library, String port) {
        library.getBehaviour(LibraryControlBehaviour.TYPE).requireSerialPort(computer, port);
        var serial = library.getBehaviour(LibrarySerialBehaviour.TYPE).device();
        return serial.attachments().stream().filter(a -> a.name().equals("s0"))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("library_closed")).key();
    }

    String tick(ServerPlayer player, ComputerBehaviour computer) {
        var level = player.serverLevel();
        if (encoded.readyBytes() == 0 || computer.stagedFile(player.getUUID()).filter(f -> f.id().equals(address.file())).isEmpty())
            return "file_unavailable";
        if (!level.hasChunkAt(address.library()) || level.getBlockEntity(address.library()) != library) return "library_unavailable";
        if (!level.mayInteract(player, address.library())) return "access_denied";
        if (computer.installedProgram().filter(p -> !p.paused()).isPresent()) return "pause_program";
        if (!peer.equals(peer(computer, library, address.port()))) return "device_changed";
        SerialDevice serial = computer.serialDevice();
        var status = serial.status(address.port());
        if (!status.connection().equals("ready")) return status.connection();
        if (awaiting) {
            var receipt = serial.prepareReceive(address.port());
            if ("received".equals(statusCode(receipt))) {
                if (receipt.source().filter(peer::equals).isEmpty() || !(receipt.result().field("value") instanceof DataValue.RecordValue reply))
                    return "unrelated_frame";
                if (!transfer.matches(reply)) return "unrelated_frame";
                if (!receipt.publish()) return "changed";
                transfer.reply(reply);
                awaiting = false; lastProgress = level.getGameTime();
                if (transfer.finished()) return transfer.code().orElseThrow();
                return null;
            }
            if (!status.failure().isEmpty()) return status.failure();
            return level.getGameTime() - lastProgress > timeoutTicks ? transfer.replyTimeout() : null;
        }
        if (status.receive().frames() != 0) return "unrelated_frame";
        if (Set.of("queued", "transmitting").contains(status.transmit())) return "busy";
        var frame = transfer.next().orElseThrow();
        var send = serial.prepareSend(address.port(), frame);
        if (!"queued".equals(statusCode(send))) return statusCode(send);
        if (!send.publish()) return "changed";
        awaiting = true; lastProgress = level.getGameTime();
        return null;
    }

    void complete(ServerPlayer player, ComputerBehaviour computer) { computer.retireStagedFile(player.getUUID(), address.file()); }
    void close() { encoded.abort(); }
    private static String statusCode(SerialDevice.Prepared frame) { return ((DataValue.TextValue)frame.result().field("status")).value(); }
}
