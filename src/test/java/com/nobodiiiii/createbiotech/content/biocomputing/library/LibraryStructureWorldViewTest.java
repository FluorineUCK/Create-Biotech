package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LibraryStructureWorldViewTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryShelfReaderTest.bootstrap(); }

    @Test void anUnloadedPhysicalCoreIsPartialWithoutReadingAWorldBlock() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var world = (UnloadedWorld)((sun.misc.Unsafe)field.get(null)).allocateInstance(UnloadedWorld.class);
        var result = LibraryStructureScanner.scan(new LibraryStructureWorldView(world), BlockPos.ZERO, 32, 16);
        assertEquals(LibraryStructureScanner.State.PARTIAL, result.state());
        assertNull(result.snapshot());
        assertEquals(1, world.loadedChecks);
    }

    // Only the production world's loaded predicate may be used on this path.
    private static final class UnloadedWorld extends ServerLevel {
        int loadedChecks;
        private UnloadedWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public boolean isLoaded(BlockPos pos) { loadedChecks++; return false; }
        @Override public BlockState getBlockState(BlockPos pos) { throw new AssertionError("Must not read or load an unavailable block"); }
    }
}
