package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage.Mode;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage.Setting;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import java.util.Optional;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WitchCarrierStageTest {
    private static final CarrierHardwareConfig.Limits LIMITS = new CarrierHardwareConfig.Limits(16, 16, 3, 2, 3);
    private static final CarrierClock.Output INPUT = CarrierClock.Output.valid(new CarrierFrequency(100, 3));
    private static Setting stage(Mode mode, Direction output, int factor) {
        return new Setting(mode, Optional.of(Direction.NORTH), Optional.of(output), factor);
    }

    @Test void offByDefaultAndOnlyDrivesSelectedRatio() {
        var device = new WitchCarrierStage();
        assertNull(device.write());
        assertEquals(Setting.OFF, device.setting());
        assertTrue(device.output().frequency().isEmpty());
        var divide = stage(Mode.DIVIDE, Direction.WEST, 3);
        assertTrue(device.configure(divide, LIMITS));
        device.advance(0, INPUT, LIMITS);
        assertEquals(Optional.of(new CarrierFrequency(100, 9)), device.output().frequency());
        assertFalse(device.configure(divide, LIMITS));
        assertTrue(device.configure(Setting.OFF, LIMITS));
        assertNull(device.write());
        assertTrue(device.output().frequency().isEmpty());
    }

    @Test void savedPllResumesOnlyOnActiveTicksAndChangingEitherFaceOrModeDropsLock() {
        var device = new WitchCarrierStage();
        var west = stage(Mode.MULTIPLY, Direction.WEST, 2);
        assertEquals(west, Setting.decode(west.encode()));
        device.configure(west, LIMITS);
        device.advance(2, INPUT, LIMITS);
        assertFalse(device.advance(2, INPUT, LIMITS));
        var restored = new WitchCarrierStage();
        restored.read(device.write());
        assertEquals(west, restored.setting());
        restored.advance(100_000, INPUT, LIMITS);
        assertEquals(CarrierClock.State.ACQUIRING, restored.output().state());
        restored.advance(100_001, INPUT, LIMITS);
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), restored.output().frequency());
        restored.configure(stage(Mode.MULTIPLY, Direction.UP, 2), LIMITS);
        restored.advance(100_002, INPUT, LIMITS);
        assertEquals(CarrierClock.State.ACQUIRING, restored.output().state());
        restored.configure(new Setting(Mode.MULTIPLY, Optional.of(Direction.DOWN), Optional.of(Direction.UP), 2), LIMITS);
        restored.advance(100_003, INPUT, LIMITS);
        restored.advance(100_004, INPUT, LIMITS);
        assertEquals(CarrierClock.State.ACQUIRING, restored.output().state());
        restored.configure(stage(Mode.DIVIDE, Direction.EAST, 2), LIMITS);
        restored.advance(100_005, INPUT, LIMITS);
        assertEquals(Optional.of(new CarrierFrequency(50, 3)), restored.output().frequency());
        restored.advance(100_006, CarrierClock.Output.unavailable(CarrierClock.State.WRONG_FACE), LIMITS);
        assertEquals(CarrierClock.State.WRONG_FACE, restored.output().state());
    }

    @Test void invalidRoutesAndLimitsCannotReplaceAGoodSettingAndMalformedSaveStaysOpaqueUntilReset() {
        var device = new WitchCarrierStage();
        var good = stage(Mode.DIVIDE, Direction.WEST, 2);
        device.configure(good, LIMITS);
        assertThrows(IllegalArgumentException.class, () -> stage(Mode.DIVIDE, Direction.NORTH, 2));
        assertThrows(IllegalArgumentException.class, () -> stage(Mode.OFF, Direction.EAST, 1));
        assertThrows(IllegalArgumentException.class, () -> device.configure(stage(Mode.DIVIDE, Direction.EAST, 17), LIMITS));
        assertEquals(good, device.setting());
        var bad = (net.minecraft.nbt.CompoundTag)device.write();
        bad.getCompound("Converter").putString("Factor", "2");
        device.read(bad);
        assertFalse(device.valid());
        assertEquals(Setting.OFF, device.setting());
        assertFalse(device.advance(10, INPUT, LIMITS));
        assertTrue(device.output().frequency().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> device.configure(good, LIMITS));
        assertEquals(bad, device.write());
        assertTrue(device.configure(Setting.OFF, LIMITS));
        assertNull(device.write());
    }
}
