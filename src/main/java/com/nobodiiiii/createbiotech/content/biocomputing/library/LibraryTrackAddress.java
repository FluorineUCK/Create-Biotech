package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Local right/up/back coordinates. Values are addresses, never authority or world references. */
public record LibraryTrackAddress(int x, int y, int z) {
    public static LibraryTrackAddress from(BlockPos core, Direction facing, BlockPos shelf) {
        var right = right(facing); var back = facing.getOpposite();
        long dx = (long)shelf.getX() - core.getX(), dz = (long)shelf.getZ() - core.getZ();
        return new LibraryTrackAddress(integer(dx * right.getStepX() + dz * right.getStepZ()),
            integer((long)shelf.getY() - core.getY()), integer(dx * back.getStepX() + dz * back.getStepZ()));
    }

    /** Resolves only inside a fully validated structure, before any shelf/content lookup. */
    public BlockPos resolve(BlockPos core, Direction facing, LibraryStructureScanner.ScanResult structure) {
        var right = right(facing); var back = facing.getOpposite();
        if (structure.state() != LibraryStructureScanner.State.VALID)
            throw new LibraryBookAccess.Failure(structure.state() == LibraryStructureScanner.State.PARTIAL
                ? "unloaded" : structure.state().name().toLowerCase(java.util.Locale.ROOT));
        if (structure.snapshot() == null) throw new LibraryBookAccess.Failure("invalid_structure");
        var position = new BlockPos(integer((long)core.getX() + (long)x * right.getStepX() + (long)z * back.getStepX()),
            integer((long)core.getY() + y), integer((long)core.getZ() + (long)x * right.getStepZ() + (long)z * back.getStepZ()));
        if (!structure.snapshot().mediaShelves().contains(position)) throw new LibraryBookAccess.Failure("missing_track");
        return position;
    }

    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(x),
            "y", new DataValue.IntegerValue(y), "z", new DataValue.IntegerValue(z)));
    }

    public static LibraryTrackAddress decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of("x", "y", "z")))
            throw new LibraryBookAccess.Failure("invalid_address");
        return new LibraryTrackAddress(coordinate(record.field("x")), coordinate(record.field("y")), coordinate(record.field("z")));
    }

    private static Direction right(Direction facing) {
        if (facing == null || !facing.getAxis().isHorizontal()) throw new LibraryBookAccess.Failure("invalid_address");
        return facing.getClockWise();
    }
    private static int coordinate(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue integer)) throw new LibraryBookAccess.Failure("invalid_address");
        return integer(integer.value());
    }
    private static int integer(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new LibraryBookAccess.Failure("invalid_address");
        return (int)value;
    }
}
