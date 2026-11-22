package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

/** Completion-time capability for a selected physical slot. Not an admission, timer or public command. */
public final class LibraryPositionedWriteSource {
    private final LibraryBookAccess access;
    private final Function<LibraryTrackAddress, BlockPos> locate;
    private final Function<String, Access> grant;
    public LibraryPositionedWriteSource(LibraryBookAccess access, Function<LibraryTrackAddress, BlockPos> locate,
        Function<String, Access> grant) {
        this.access = Objects.requireNonNull(access); this.locate = Objects.requireNonNull(locate); this.grant = Objects.requireNonNull(grant);
    }
    public LibraryBookAccess.PreparedWrite prepare(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) {
        var handle = access.at(locate.apply(track), slot);
        authorize(track, handle, operation);
        var prepared = access.prepareWrite(handle, operation);
        return new LibraryBookAccess.PreparedWrite(prepared.reply(), prepared.replay(), () -> {
            authorize(track, handle, operation);
            prepared.commit().run();
        });
    }
    public void verifyTransfer(LibraryTrackAddress track, int slot, LibraryWriteTransfer transfer) {
        var handle = access.at(locate.apply(track), slot);
        authorize(track, handle, transfer.volume());
        access.verifyWriteTarget(handle, transfer.volume(), transfer.expected());
    }
    /** Only after the owning host has durably acknowledged this exact operation. Never releases entry history. */
    public void release(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) {
        var handle = access.at(locate.apply(track), slot);
        authorize(track, handle, operation); access.releaseReceipt(handle, operation);
    }
    private void authorize(LibraryTrackAddress track, LibraryBookAccess.Handle handle, LibraryDeviceWrite operation) {
        authorize(track, handle, operation.mutation().volume());
    }
    private void authorize(LibraryTrackAddress track, LibraryBookAccess.Handle handle, String requestedVolume) {
        if (!handle.shelf().equals(locate.apply(track))) throw new LibraryBookAccess.Failure("changed");
        var volume = access.volumeName(handle).orElseThrow(() -> new LibraryBookAccess.Failure("unnamed_volume"));
        switch (Objects.requireNonNull(grant.apply(volume))) {
            case CLOSED -> throw new LibraryBookAccess.Failure("denied");
            case READ_ONLY -> throw new LibraryBookAccess.Failure("readonly");
            case READ_WRITE -> {}
        }
        if (!volume.equals(requestedVolume)) throw new LibraryBookAccess.Failure("changed");
    }
}
