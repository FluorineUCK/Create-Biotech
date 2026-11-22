package com.nobodiiiii.createbiotech.content.biocomputing.library;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.*;
class LibraryServicePhysicalPortTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    @Test void lecternConnectsOnlyAtItsLowerRearFace() {
        var block = new LibraryCoreBlock(BlockBehaviour.Properties.of());
        assertInstanceOf(DataPort.class, block);
        var port = (DataPort)(Object)block;
        for (Direction facing : Direction.Plane.HORIZONTAL) for (Direction side : Direction.values())
            for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                var state = block.defaultBlockState().setValue(LibraryCoreBlock.FACING, facing).setValue(LibraryCoreBlock.HALF, half);
                assertEquals(half == DoubleBlockHalf.LOWER && side == facing.getOpposite(), port.acceptsCable(state, side));
            }
    }
}

