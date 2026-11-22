package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Objects;

/** One physical read mechanism, independent of Computer CPU, resident count and cable bitrate. */
public record LibraryDriveLimits(long seekBaseTicks, long seekTicksPerBlock, long selectTicks,
    long readBaseTicks, long readBytesPerTick, long bufferBytes, ArchiveLimits archive, long codecWorkPerTick) {
    public LibraryDriveLimits(long seekBaseTicks, long seekTicksPerBlock, long selectTicks,
        long readBaseTicks, long readBytesPerTick, long bufferBytes, ArchiveLimits archive) {
        this(seekBaseTicks,seekTicksPerBlock,selectTicks,readBaseTicks,readBytesPerTick,bufferBytes,archive,readBytesPerTick);
    }
    public LibraryDriveLimits {
        Objects.requireNonNull(archive);
        if (seekBaseTicks < 1 || seekTicksPerBlock < 0 || selectTicks < 1 || readBaseTicks < 1
            || readBytesPerTick < 1 || bufferBytes < 1 || codecWorkPerTick < 1) throw new IllegalArgumentException("Invalid Library mechanism limits");
    }
    public long seekTicks(LibraryTrackAddress from, LibraryTrackAddress to) {
        long distance = Math.abs((long)from.x() - to.x()) + Math.abs((long)from.y() - to.y()) + Math.abs((long)from.z() - to.z());
        try { return Math.addExact(seekBaseTicks, Math.multiplyExact(seekTicksPerBlock, distance)); }
        catch (ArithmeticException overflow) { throw new LibraryBookAccess.Failure("timing_overflow"); }
    }
    public long readTicks(long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("A value archive is nonempty");
        try { return Math.addExact(readBaseTicks, 1 + (bytes - 1) / readBytesPerTick); }
        catch (ArithmeticException overflow) { throw new LibraryBookAccess.Failure("timing_overflow"); }
    }
}
