package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import java.util.Optional;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.ReceiveLoop;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WitchReceiverFilterTest {
    @Test void oldActiveAdjacencyNeedsExplicitRewiring() {
        var filter = new WitchReceiverFilter();
        var setting = new WitchReceiverFilter.Setting(Optional.of(Direction.NORTH), WitchReceiverFilter.Width.NARROW);
        filter.configure(setting);
        var old = (net.minecraft.nbt.CompoundTag) filter.write();
        old.putInt("Version", 1); filter.read(old);
        assertTrue(filter.needsRewire());
        assertEquals(old, filter.write());
        filter.configure(setting);
        assertFalse(filter.needsRewire());
    }

    @Test void wrongDirectionMissingReturnAndUnavailableGuideHaveDistinctResults() {
        var trader = net.minecraft.core.BlockPos.ZERO;
        var witch = trader.east(2);
        var out = Direction.EAST;
        var cells = new java.util.HashMap<net.minecraft.core.BlockPos, ReceiveLoop.Cell>();
        ReceiveLoop.View view = (p,face) -> cells.getOrDefault(p, ReceiveLoop.Gap.OPEN);
        cells.put(trader.east(), new ReceiveLoop.Guide(Direction.WEST, Direction.EAST));
        cells.put(witch, new ReceiveLoop.Socket(witch, Direction.WEST, Optional.of(WitchReceiverFilter.Width.NARROW)));
        assertEquals("return_open",ReceiveLoop.trace(trader,out,4,view).state());
        cells.put(witch.east(), ReceiveLoop.Gap.UNAVAILABLE);
        assertEquals("unavailable",ReceiveLoop.trace(trader,out,4,view).state());
        cells.put(witch.east(), new ReceiveLoop.Guide(Direction.EAST,Direction.SOUTH));
        assertEquals("wrong_face",ReceiveLoop.trace(trader,out,4,view).state());
        cells.put(witch.east(), new ReceiveLoop.Guide(Direction.WEST,Direction.EAST));
        cells.put(witch.east(2), new ReceiveLoop.Socket(witch.east(2),Direction.WEST,Optional.empty()));
        assertEquals("wrong_return",ReceiveLoop.trace(trader,out,4,view).state());
    }

    @Test void receivePathSurvivesRetuningAndReloadWithoutBecomingACarrier() {
        var tuning = new ResidentRadioTuning();
        tuning.selectCarrierInput(Optional.of(Direction.DOWN));
        tuning.selectReceiveSend(Optional.of(Direction.WEST));
        tuning.tune(3, true);
        var saved = tuning.write();
        var restored = new ResidentRadioTuning(); restored.read(saved);
        assertTrue(restored.valid());
        assertEquals(Optional.of(Direction.DOWN), restored.carrierInput());
        assertEquals(Optional.of(Direction.WEST), restored.receiveSend());
        restored.selectReceiveSend(Optional.empty());
        assertEquals(Optional.of(Direction.DOWN), restored.carrierInput());
        assertTrue(restored.armed());
    }

    @Test void passbandScalesPowerBeforeDecodeWithoutDistinguishingPayloads() {
        var wanted = new RadioSerialNetwork.Band(100,6);
        var adjacent = new RadioSerialNetwork.Band(108,6);
        var stock = new RadioSerialNetwork.Band(100,12);
        assertEquals(10,stock.passedPower(adjacent,60)); // 1 of 6 game frequency units passes
        assertEquals(0,wanted.passedPower(adjacent,60));
        assertEquals(60,wanted.passedPower(wanted,60)); // co-channel noise passes just as well
        assertFalse(wanted.containsTunedSignal(new RadioSerialNetwork.Band(100,12)));
    }
    @Test void legacySavesDoNotCreateAConnectionAndInvalidDataIsPreservedButNeverDrivesAFace() {
        var filter = new WitchReceiverFilter();
        filter.read(null);
        assertTrue(filter.setting().output().isEmpty());
        assertNull(filter.write());
        var setting = new WitchReceiverFilter.Setting(Optional.of(Direction.WEST), WitchReceiverFilter.Width.NARROW);
        filter.configure(setting);
        var saved = (net.minecraft.nbt.CompoundTag)filter.write();
        var restored = new WitchReceiverFilter();
        restored.read(saved);
        assertEquals(setting,restored.setting());
        saved.putString("Width","unknown");
        restored.read(saved);
        assertFalse(restored.valid());
        assertTrue(restored.setting().output().isEmpty());
        assertEquals(saved,restored.write());
        assertThrows(IllegalArgumentException.class,()->restored.configure(setting));
    }
}
