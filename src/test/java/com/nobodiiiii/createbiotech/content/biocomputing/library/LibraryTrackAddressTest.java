package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LibraryTrackAddressTest {
    @Test void onlyValidMemberShelvesResolveAndCoordinatesNeverWrap() {
        var origin = new BlockPos(Integer.MAX_VALUE, 0, 0);
        var shelf = origin.west();
        var snapshot = new LibraryStructureScanner.Snapshot(List.of(origin, shelf), List.of(), List.of(shelf), shelf, origin);
        var valid = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID, snapshot);
        assertEquals("missing_track", assertThrows(LibraryBookAccess.Failure.class,
            () -> new LibraryTrackAddress(0, 0, 0).resolve(origin, Direction.NORTH, valid)).code());
        assertEquals("invalid_address", assertThrows(LibraryBookAccess.Failure.class,
            () -> new LibraryTrackAddress(1, 0, 0).resolve(origin, Direction.NORTH, valid)).code());
        assertEquals("unloaded", assertThrows(LibraryBookAccess.Failure.class,
            () -> new LibraryTrackAddress(-1, 0, 0).resolve(origin, Direction.NORTH,
                new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.PARTIAL, snapshot))).code());
        var normalShelf = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
            new LibraryStructureScanner.Snapshot(List.of(origin, shelf), List.of(shelf), List.of(), shelf, origin));
        assertEquals("missing_track", assertThrows(LibraryBookAccess.Failure.class,
            () -> new LibraryTrackAddress(-1, 0, 0).resolve(origin, Direction.NORTH, normalShelf)).code());
    }

    @Test void localAddressFollowsCoreOrientationNotDirectoryOrder() {
        var origin = new BlockPos(30, 64, -40);
        var address = new LibraryTrackAddress(2, -1, 3);
        for (var facing : Direction.Plane.HORIZONTAL) {
            var shelf = origin.relative(facing.getClockWise(), 2).below().relative(facing.getOpposite(), 3);
            var geometry = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
                new LibraryStructureScanner.Snapshot(List.of(origin, shelf), List.of(), List.of(shelf), origin, shelf));
            assertEquals(address, LibraryTrackAddress.from(origin, facing, shelf));
            assertEquals(shelf, address.resolve(origin, facing, geometry));
            assertEquals(address, LibraryTrackAddress.decode(address.value()));
        }
    }
}
