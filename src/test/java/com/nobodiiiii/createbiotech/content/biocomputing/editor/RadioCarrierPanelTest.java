package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioCarrierPanelTest {
    @Test void everyChoiceIsLocalAndAnExplicitDetachIsNotLossOfReference() {
        var start = new RadioControlData.CarrierInput(Optional.empty());
        var next = start;
        var seen = new java.util.HashSet<>(java.util.List.of(start));
        for (int i = 0; i < 6; i++) {
            next = next.next();
            assertTrue(next.face().isPresent());
            assertTrue(seen.add(next));
            assertEquals(next, RadioControlData.CarrierInput.decode(next.encode()));
        }
        assertEquals(start, next.next());
        assertEquals(start, RadioControlData.CarrierInput.decode(start.encode()));
        var invalid = new DataValue.RecordValue(Map.of("input", new DataValue.TextValue("sideways")));
        assertThrows(IllegalArgumentException.class, () -> RadioControlData.CarrierInput.decode(invalid));
        var forged = new DataValue.RecordValue(Map.of("input", new DataValue.TextValue("north"),
            "frequency", new DataValue.IntegerValue(200)));
        assertThrows(IllegalArgumentException.class, () -> RadioControlData.CarrierInput.decode(forged));
        assertThrows(IllegalArgumentException.class, () -> RadioControlData.Tune.decode(start.encode()));
    }

    @Test void observedFaceAndEffectiveFrequencyRoundTripIndependentlyOfTheOscillatorPreview() {
        var west = Optional.of(Direction.WEST);
        var ratio = new CarrierFrequency(200,3);
        var locked = new CarrierPortStatus(west, new CarrierClock.Output(CarrierClock.State.LOCKED, Optional.of(ratio)));
        assertEquals(locked, CarrierPortStatus.decode(locked.encode()));
        for (var state : CarrierClock.State.values()) {
            if (state == CarrierClock.State.LOCKED) continue;
            var unavailable = new CarrierPortStatus(west, new CarrierClock.Output(state, Optional.empty()));
            var raw = unavailable.encode();
            assertFalse(raw.fields().containsKey("numerator"));
            assertEquals(unavailable, CarrierPortStatus.decode(raw));
            var bad = new java.util.HashMap<>(raw.fields());
            bad.put("numerator",new DataValue.IntegerValue(0));
            bad.put("denominator",new DataValue.IntegerValue(1));
            assertThrows(IllegalArgumentException.class, () -> CarrierPortStatus.decode(new DataValue.RecordValue(bad)));
        }
        var bad = new java.util.HashMap<>(locked.encode().fields());
        bad.put("numerator",new DataValue.IntegerValue(1L << 32));
        assertThrows(IllegalArgumentException.class, () -> CarrierPortStatus.decode(new DataValue.RecordValue(bad)));
    }

    @Test void missingObservationDoesNotAuthorizeChangingTheFaceOrInventAnInternalSignal() {
        var fields = new java.util.HashMap<String,DataValue>();
        for (var key : java.util.List.of("revision","preset","wanted","interference","frames"))
            fields.put(key,new DataValue.IntegerValue(0));
        for (var key : java.util.List.of("preset_count","center_spacing","transmit_width","receive_width"))
            fields.put(key,new DataValue.IntegerValue(1));
        fields.put("first_center",new DataValue.IntegerValue(100));
        for (var key : java.util.List.of("reception","diagnostic","transmit"))
            fields.put(key,new DataValue.TextValue(""));
        fields.put("equipped",new DataValue.BooleanValue(true));
        fields.put("armed",new DataValue.BooleanValue(false));
        assertTrue(RadioControlData.View.decode(new DataValue.RecordValue(fields)).carrier().isEmpty());
        var status = new CarrierPortStatus(Optional.of(Direction.NORTH),
            new CarrierClock.Output(CarrierClock.State.LOST_LOCK, Optional.empty()));
        fields.put("carrier",status.encode());
        var view=RadioControlData.View.decode(new DataValue.RecordValue(fields));
        assertEquals(status, view.carrier().orElseThrow());
        assertEquals(100, view.bands().orElseThrow().first());
        assertFalse(view.armed());
        fields.put("equipped",new DataValue.BooleanValue(false));
        assertThrows(IllegalArgumentException.class, () -> RadioControlData.View.decode(new DataValue.RecordValue(fields)));
    }

    @Test void witchMenuEditsAreBoundedAndCannotNameATargetOrCreateAReceiver() {
        var setting = new WitchReceiverFilter.Setting(
            Optional.of(Direction.NORTH),WitchReceiverFilter.Width.WIDE);
        assertEquals(setting, WitchReceiverFilter.Setting.decode(setting.encode()));
        var forged = new java.util.HashMap<>(setting.encode().fields());
        forged.put("target",new DataValue.TextValue("some remote trader"));
        assertThrows(IllegalArgumentException.class,() ->
            WitchReceiverFilter.Setting.decode(new DataValue.RecordValue(forged)));
        var action = ComputerControlSession.Action.CONFIGURE_WITCH_FILTER;
        assertTrue(action.radio() && action.carriesInputs());
        assertThrows(IllegalArgumentException.class,() -> new ProgramControlRequest(1,java.util.UUID.randomUUID(),1,1,action,new byte[513]));
    }

    @Test void witchCarrierSelectionCannotAddressOtherBlocksOrApplyAnInvalidFactor() {
        var action = ComputerControlSession.Action.CONFIGURE_WITCH_CARRIER;
        assertTrue(action.radio() && action.carriesInputs());
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1,java.util.UUID.randomUUID(),1,1,action,new byte[513]));
        var mode = WitchCarrierStage.Mode.MULTIPLY;
        for (String invalid : java.util.List.of("", "-2", "0", "9", "1.5", "4294967297"))
            assertTrue(RadioControlData.stageSelection(mode, Direction.UP, Direction.DOWN, invalid, 8).isEmpty());
        assertTrue(RadioControlData.stageSelection(mode, Direction.UP, Direction.UP, "2", 8).isEmpty());
        var active = RadioControlData.stageSelection(mode, Direction.UP, Direction.DOWN, "8", 8).orElseThrow();
        assertEquals(8, active.factor());
        assertEquals(java.util.Set.of("input","output","factor","mode"), active.encode().fields().keySet());
        var off = RadioControlData.stageSelection(WitchCarrierStage.Mode.OFF, Direction.UP, Direction.UP, "", 8).orElseThrow();
        assertTrue(off.input().isEmpty() && off.output().isEmpty());
        assertEquals(1, off.factor());
    }

    @Test void witchRadioSnapshotSeparatesTheWiredStageFromTheReceiverFilter() {
        var fields = new java.util.HashMap<String, DataValue>();
        for (var key : java.util.List.of("revision","preset","wanted","interference"))
            fields.put(key,new DataValue.IntegerValue(0));
        for (var key : java.util.List.of("reception","diagnostic"))
            fields.put(key,new DataValue.TextValue(""));
        fields.put("equipped",new DataValue.BooleanValue(false));
        fields.put("armed",new DataValue.BooleanValue(false));
        fields.put("witch_filter",WitchReceiverFilter.Setting.OFF.encode());
        assertTrue(RadioControlData.View.decode(new DataValue.RecordValue(fields)).stage().isEmpty());
        var setting = new WitchCarrierStage.Setting(WitchCarrierStage.Mode.DIVIDE,
            Optional.of(Direction.NORTH),Optional.of(Direction.UP),3);
        fields.put("witch_carrier", setting.encode());
        fields.put("witch_carrier_state",new DataValue.TextValue("locked"));
        fields.put("witch_carrier_frequency",new DataValue.TextValue("200/3"));
        fields.put("witch_carrier_default_divisor",new DataValue.IntegerValue(5));
        fields.put("witch_carrier_default_multiplier",new DataValue.IntegerValue(4));
        fields.put("witch_carrier_max_factor",new DataValue.IntegerValue(12));
        var view=RadioControlData.View.decode(new DataValue.RecordValue(fields));
        assertEquals(WitchReceiverFilter.Setting.OFF,view.witch().orElseThrow());
        assertEquals(new RadioControlData.Stage(setting,12,5,4,"locked","200/3"),view.stage().orElseThrow());
        assertEquals(3, view.stage().orElseThrow().suggestedFactor(WitchCarrierStage.Mode.DIVIDE)); // Already applied.
        assertEquals(4, view.stage().orElseThrow().suggestedFactor(WitchCarrierStage.Mode.MULTIPLY)); // Server default.
        fields.remove("witch_carrier_max_factor");
        assertThrows(IllegalArgumentException.class, () -> RadioControlData.View.decode(new DataValue.RecordValue(fields)));
    }

    @Test void faceEditUsesItsOwnBoundedRadioAction() {
        var action = ComputerControlSession.Action.CONFIGURE_CARRIER_INPUT;
        assertTrue(action.radio());
        assertTrue(action.carriesInputs());
        var wire = ProgramEditorWire.encodeValue(new RadioControlData.CarrierInput(Optional.of(Direction.UP)).encode(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(1000,10000,100000));
        var nonce = java.util.UUID.randomUUID();
        assertDoesNotThrow(() -> new ProgramControlRequest(1,nonce,3,2,action,wire));
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1,nonce,3,2,action,new byte[513]));
    }
}
