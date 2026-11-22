package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DataPortTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        // This fixture must also work as the first selected plain-JVM test; do not depend on another suite's bootstrap.
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", java.util.List.class, java.util.List.class, java.util.List.class, java.util.List.class, java.util.Map.class)
                .invoke(null, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true); bootstrapped.setBoolean(null, true);
    }
    @Test void readerHasExactlyOneBackConnectorAndCableNeverAttachesToOrdinaryBlocks() {
        var reader = new BlockReaderBlock(Block.Properties.of());
        for (Direction facing : Direction.values()) for (Direction side : Direction.values()) {
            var state = reader.defaultBlockState().setValue(BlockReaderBlock.FACING, facing);
            assertEquals(side == facing.getOpposite(), reader.acceptsCable(state, side));
            assertEquals(side.getOpposite() == facing.getOpposite(), DataCableBlock.connects(state, side));
        }
        assertFalse(DataCableBlock.connects(Blocks.CHEST.defaultBlockState(), Direction.UP));
    }
    @Test void computerOnlyOwnsPortsOnLowerHalfAndCableVisualsHaveSixIndependentArms() {
        var computer = new ComputerBlock(Block.Properties.of());
        for (Direction side : Direction.values()) {
            assertTrue(DataCableBlock.connects(computer.defaultBlockState(), side));
            assertFalse(DataCableBlock.connects(computer.defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), side));
        }
        var cable = new DataCableBlock(Block.Properties.of());
        var state = cable.defaultBlockState();
        for (Direction side : Direction.values()) {
            assertFalse(state.getValue(DataCableBlock.connection(side)));
            assertFalse(DataCableBlock.connects(state, side));
            var connected = state.setValue(DataCableBlock.connection(side), true);
            for (Direction other : Direction.values()) assertEquals(side == other, connected.getValue(DataCableBlock.connection(other)));
        }
    }
    @Test void redstoneCanAttachButSavedPowerAloneCannotBecomeAWorldSource() {
        var cable = new DataCableBlock(Block.Properties.of());
        assertTrue(DataCableBlock.connects(Blocks.REDSTONE_WIRE.defaultBlockState(), Direction.EAST));
        var saved = cable.defaultBlockState().setValue(DataCableBlock.POWER, 15);
        assertTrue(cable.isSignalSource(saved));
        assertFalse(cable.canConnectRedstone(saved, null, net.minecraft.core.BlockPos.ZERO, Direction.EAST));
        assertTrue(cable.canConnectRedstone(saved.setValue(DataCableBlock.connection(Direction.WEST), true),null,net.minecraft.core.BlockPos.ZERO,Direction.EAST));
        assertEquals(0, cable.getSignal(saved, null, net.minecraft.core.BlockPos.ZERO, Direction.EAST));
        assertEquals(0, cable.getDirectSignal(saved, null, net.minecraft.core.BlockPos.ZERO, Direction.EAST));
    }
}
