package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;

/** Physical slot access, not a whole-Library directory or RPC service. The trusted host supplies live loaded-only geometry
 * and the bound caller's grant; neither the local address nor the book's name grants authority. */
public final class LibraryPositionedReadSource implements LibraryReadHead.Source {
    private final LibraryBookAccess access;
    private final Function<LibraryTrackAddress, BlockPos> locate;
    private final Predicate<String> mayRead;
    @FunctionalInterface public interface RecipeReader {
        LibraryReadHead.Sample read(BlockPos shelf, int slot, int page, net.minecraft.world.item.ItemStack book);
    }
    private final RecipeReader recipes;
    private final LibraryPositionedWriteSource writer;
    public LibraryPositionedReadSource(LibraryBookAccess access, Function<LibraryTrackAddress, BlockPos> locate,
        Predicate<String> mayRead) {
        this(access, locate, mayRead, null);
    }
    public LibraryPositionedReadSource(LibraryBookAccess access, Function<LibraryTrackAddress, BlockPos> locate,
        Predicate<String> mayRead, LibraryPositionedWriteSource writer) {
        this(access, locate, mayRead, writer, null);
    }
    public LibraryPositionedReadSource(LibraryBookAccess access, Function<LibraryTrackAddress, BlockPos> locate,
        Predicate<String> mayRead, LibraryPositionedWriteSource writer, RecipeReader recipes) {
        this.recipes = recipes;
        this.access = Objects.requireNonNull(access); this.locate = Objects.requireNonNull(locate); this.mayRead = Objects.requireNonNull(mayRead);
        this.writer = writer;
    }
    @Override public LibraryBookAccess.PreparedWrite prepareWrite(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) {
        if (writer == null) throw new LibraryBookAccess.Failure("unsupported_operation");
        return writer.prepare(track, slot, operation);
    }
    @Override public void releaseWrite(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) {
        if (writer == null) throw new LibraryBookAccess.Failure("unsupported_operation");
        writer.release(track, slot, operation);
    }
    @Override public void verifyTransfer(LibraryTrackAddress track, int slot, LibraryWriteTransfer transfer) {
        if (writer == null) throw new LibraryBookAccess.Failure("unsupported_operation");
        writer.verifyTransfer(track, slot, transfer);
    }
    @Override public void validateTrack(LibraryTrackAddress track) { Objects.requireNonNull(locate.apply(track)); }
    @Override public LibraryReadHead.Sample read(LibraryTrackAddress track, int slot, String entry) {
        var handle = access.at(locate.apply(track), slot); var volume = authorizedVolume(handle);
        var snapshot = access.snapshot(handle, entry);
        return new LibraryReadHead.Sample(fence(volume, snapshot.revision()), snapshot.value());
    }
    @Override public void verify(LibraryTrackAddress track, int slot, String entry, DataValue.RecordValue expected) {
        var handle = access.at(locate.apply(track), slot); var volume = authorizedVolume(handle);
        if (!fence(volume, access.revision(handle)).equals(expected)) throw new LibraryBookAccess.Failure("changed");
    }
    @Override public LibraryReadHead.Sample recipePage(LibraryTrackAddress track, int slot, int page) {
        if (recipes == null) throw new LibraryBookAccess.Failure("unsupported_operation");
        var shelf = locate.apply(track); var handle = access.at(shelf, slot); var volume = authorizedVolume(handle);
        var snapshot = recipes.read(shelf, slot, page, access.bookSnapshot(handle));
        return new LibraryReadHead.Sample(new DataValue.RecordValue(Map.of("volume", new DataValue.TextValue(volume),
            "recipe", snapshot.fence())), snapshot.value());
    }
    @Override public void verifyRecipePage(LibraryTrackAddress track, int slot, int page, DataValue.RecordValue expected) {
        // Resamples only this physical page, using current grants, book, space and sole index. Never publishes this value.
        if (!recipePage(track, slot, page).fence().equals(expected)) throw new LibraryBookAccess.Failure("changed");
    }
    @Override public LibraryReadHead.Sample stat(LibraryTrackAddress track, int slot, String entry) {
        var handle = access.at(locate.apply(track), slot); var volume = authorizedVolume(handle);
        var snapshot = access.statSnapshot(handle, entry);
        return new LibraryReadHead.Sample(fence(volume, snapshot.revision()), snapshot.value());
    }
    @Override public LibraryReadHead.Sample list(LibraryTrackAddress track, int slot, LibraryDirectoryQuery query) {
        var handle = access.at(locate.apply(track), slot); var volume = authorizedVolume(handle);
        var snapshot = access.directorySnapshot(handle, query);
        var fields = new LinkedHashMap<>(snapshot.value().fields()); fields.put("volume", new DataValue.TextValue(volume));
        return new LibraryReadHead.Sample(fence(volume, snapshot.revision()), new DataValue.RecordValue(fields));
    }
    private String authorizedVolume(LibraryBookAccess.Handle handle) {
        var volume = access.volumeName(handle).orElseThrow(() -> new LibraryBookAccess.Failure("unnamed_volume"));
        if (!mayRead.test(volume)) throw new LibraryBookAccess.Failure("denied");
        return volume;
    }
    private static DataValue.RecordValue fence(String volume, DataValue.RecordValue revision) {
        return new DataValue.RecordValue(Map.of("volume", new DataValue.TextValue(volume), "revision", revision));
    }
}
