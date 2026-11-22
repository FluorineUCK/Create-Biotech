package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Explicit local dial and enable state. Physical emissions and runtime observations are never saved. */
public final class ResidentRadioTuning {
    private Tag saved;
    private boolean valid = true, armed;
    private int preset, wanted;
    private Optional<Direction> carrierInput = Optional.empty();
    private Optional<Direction> receiveSend = Optional.empty();
    public Optional<Direction> receiveSend() { return receiveSend; }

    public void selectReceiveSend(Optional<Direction> face) {
        if (!valid) throw new IllegalArgumentException("invalid_radio_save");
        receiveSend = Objects.requireNonNull(face);
        refresh(preset, armed, carrierInput);
    }

    private long interference;
    private String reception = "idle", domainFault = "";

    public boolean owned() { return saved != null; }
    public boolean valid() { return valid; }
    public boolean armed() { return valid && armed; }
    public int preset() { return preset; }
    public Optional<Direction> carrierInput() { return carrierInput; }

    public void selectCarrierInput(Optional<Direction> input) {
        if (!valid) throw new IllegalArgumentException("invalid_radio_save");
        refresh(preset, armed, Objects.requireNonNull(input));
    }

    public void tune(int preset, boolean armed) {
        if (!valid || preset < 0) throw new IllegalArgumentException("invalid_radio_tune");
        refresh(preset, armed, carrierInput);
    }
    private void refresh(int preset, boolean armed, Optional<Direction> input) {
        var state = new CompoundTag();
        state.putInt("Version", receiveSend.isPresent() ? 3 : input.isPresent() ? 2 : 1);
        state.putInt("Preset", preset);
        state.putBoolean("Armed", armed);
        input.ifPresent(face -> state.putString("CarrierFace", face.getSerializedName()));
        receiveSend.ifPresent(face -> state.putString("ReceiveSend", face.getSerializedName()));
        read(state);
    }
    public void disarm() {
        if (owned() && valid) tune(preset, false);
    }
    public void read(Tag raw) {
        saved = raw == null ? null : raw.copy();
        valid = raw == null;
        carrierInput = Optional.empty();
        receiveSend = Optional.empty();
        if (raw instanceof CompoundTag tag && tag.contains("Version", Tag.TAG_INT)
            && tag.contains("Preset", Tag.TAG_INT) && tag.getInt("Preset") >= 0
            && tag.contains("Armed", Tag.TAG_BYTE) && (tag.getByte("Armed") == 0 || tag.getByte("Armed") == 1)) {
            int version = tag.getInt("Version");
            if (version == 3 && tag.contains("ReceiveSend", Tag.TAG_STRING)) {
                var keys = new java.util.HashSet<>(Set.of("Version", "Preset", "Armed", "ReceiveSend"));
                if (tag.contains("CarrierFace", Tag.TAG_STRING)) keys.add("CarrierFace");
                if (tag.getAllKeys().equals(keys)) try {
                    receiveSend = CarrierPortStatus.parseInput(tag.getString("ReceiveSend"));
                    carrierInput = tag.contains("CarrierFace") ? CarrierPortStatus.parseInput(tag.getString("CarrierFace")) : Optional.empty();
                    valid = receiveSend.isPresent() && (!tag.contains("CarrierFace") || carrierInput.isPresent())
                        && carrierInput.stream().noneMatch(f -> f.getAxis() == receiveSend.orElseThrow().getAxis());
                } catch (IllegalArgumentException invalidFace) { valid = false; }
            }
            if (version == 1) valid = tag.getAllKeys().equals(Set.of("Version", "Preset", "Armed"));
            if (version == 2 && tag.getAllKeys().equals(Set.of("Version", "Preset", "Armed", "CarrierFace"))
                && tag.contains("CarrierFace", Tag.TAG_STRING)) {
                try {
                    carrierInput = CarrierPortStatus.parseInput(tag.getString("CarrierFace"));
                    valid = carrierInput.isPresent();
                } catch (IllegalArgumentException invalidFace) { /* Preserve the unrecognized value, but do not use it. */ }
            }
        }
        armed = valid && raw instanceof CompoundTag state && state.getBoolean("Armed");
        preset = valid && raw instanceof CompoundTag state ? state.getInt("Preset") : 0;
        wanted = 0; interference = 0; reception = "idle"; domainFault = "";
    }
    public Tag write() { return saved == null ? null : saved.copy(); }
    public void observe(RadioSerialNetwork.Observation status) {
        if (!armed()) return;
        reception = status.outcome().name().toLowerCase(Locale.ROOT);
        wanted = status.wanted();
        interference = status.interference();
    }
    public void domainFault(String code) { domainFault = code; }
    public DataValue.RecordValue status(boolean equipped) {
        return new DataValue.RecordValue(Map.of(
            "equipped", new DataValue.BooleanValue(equipped),
            "armed", new DataValue.BooleanValue(equipped && armed()),
            "preset", new DataValue.IntegerValue(preset),
            "reception", new DataValue.TextValue(reception),
            "wanted", new DataValue.IntegerValue(wanted),
            "interference", new DataValue.IntegerValue(interference),
            "diagnostic", new DataValue.TextValue(valid ? domainFault : "invalid_radio_save")));
    }
}
