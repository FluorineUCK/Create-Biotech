package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** The media handoff borrows a finished mechanical read. It never searches books or runs Library commands. */
final class SpeakerMediaPlaybackSource {
    private SpeakerMediaPlaybackSource() {}

    record Reference(BlockPos library, UUID device, long sequence, LibraryMutation.Version version) {
        Reference {
            library = library.immutable();
            if (sequence < 1 || !BlockPos.of(library.asLong()).equals(library))
                throw new IllegalArgumentException("invalid_media_reference");
        }

        static Reference decode(DataValue value) {
            if (!(value instanceof DataValue.RecordValue record)
                || !record.fields().keySet().equals(Set.of("library", "device", "sequence", "version"))
                || !(record.field("device") instanceof DataValue.TextValue id) || id.value().length() != 36
                || !(record.field("sequence") instanceof DataValue.IntegerValue sequence))
                throw new IllegalArgumentException("invalid_media_reference");
            try {
                var pos = LibraryTrackAddress.decode(record.field("library"));
                UUID device = UUID.fromString(id.value());
                if (!device.toString().equals(id.value())) throw new IllegalArgumentException();
                return new Reference(new BlockPos(pos.x(), pos.y(), pos.z()), device, sequence.value(),
                    LibraryMutation.Version.decode(record.field("version")));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid_media_reference");
            }
        }
    }

    /** Contains value data only, so decoding can happen off the main thread. It confers no ongoing authority. */
    record Prepared(DataValue archivedValue, int maximumBytes) {
        Decoded decode() {
            try {
                byte[] bytes = SpeakerMediaLibraryFile.read(archivedValue, maximumBytes);
                var record = (DataValue.RecordValue) archivedValue;
                var name = ((DataValue.TextValue) record.field("name")).value();
                return new Decoded(SpeakerMediaFormat.fromName(name), bytes);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("media_invalid");
            }
        }
    }

    record Decoded(SpeakerMediaFormat format, byte[] bytes) {}

    /** Server-thread access; re-run before publication if work crossed ticks. No chunks are loaded here. */
    static Prepared prepare(ServerLevel level, BlockPos speaker, SerialControlAccess.Binding grant,
            SharedSerialChannel.Port sender, Reference reference) {
        if (!grant.source().equals(sender)) throw new LibraryBookAccess.Failure("denied");
        var caller = new SerialControlAccess(level, speaker, null).verify(grant);
        var pos = reference.library();
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !SubLevelCompat.sameSpace(level, speaker, pos)) throw new LibraryBookAccess.Failure("denied");
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !level.shouldTickBlocksAt(pos)
            || !(chunk.getBlockEntity(pos) instanceof LibraryBlockEntity library) || library.isRemoved())
            throw new LibraryBookAccess.Failure("library_unavailable");
        if (!library.libraryDeviceId().equals(reference.device())) throw new LibraryBookAccess.Failure("library_changed");
        var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE);
        if (drive == null) throw new LibraryBookAccess.Failure("library_unavailable");
        var value = drive.readyDataForOutput(caller, grant.pin(), reference.sequence(), reference.version());
        return new Prepared(value, CBConfigs.SERVER.biocomputing.speakerMediaLimits(CBConfigs.SERVER_SPEC.isLoaded()).track());
    }
}
