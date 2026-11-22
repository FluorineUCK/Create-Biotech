package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;

/** A fixed physical controller identity and public label, not a transport or member-memory capability. */
public record ClusterPortBinding(UUID controller, BlockPos position, Direction side, String alias) {
    public ClusterPortBinding {
        Objects.requireNonNull(controller); position = position.immutable(); Objects.requireNonNull(side);
        new HardwarePins(List.of(alias), List.of());
    }
    public CompoundTag write() {
        var tag = new CompoundTag(); tag.putUUID("Controller", controller); tag.putLong("Position", position.asLong());
        tag.putString("Side", side.getName()); tag.putString("Alias", alias); return tag;
    }
    public static ClusterPortBinding read(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Controller", "Position", "Side", "Alias"))
            || !tag.hasUUID("Controller") || !tag.contains("Position", Tag.TAG_LONG)
            || !tag.contains("Side", Tag.TAG_STRING) || !tag.contains("Alias", Tag.TAG_STRING)) throw invalid();
        var side = Direction.byName(tag.getString("Side"));
        if (side == null || !side.getName().equals(tag.getString("Side"))) throw invalid();
        return new ClusterPortBinding(tag.getUUID("Controller"), BlockPos.of(tag.getLong("Position")), side, tag.getString("Alias"));
    }
    /** Fixed menu tuple; the enclosing pin already publishes its physical face. */
    public DataValue.ListValue value() {
        return new DataValue.ListValue(List.of(new DataValue.TextValue(controller.toString()),
            new DataValue.IntegerValue(position.asLong()), new DataValue.TextValue(alias)));
    }
    public static ClusterPortBinding read(DataValue raw, Direction side) {
        if (!(raw instanceof DataValue.ListValue list) || list.values().size() != 3
            || !(list.values().get(0) instanceof DataValue.TextValue id)
            || !(list.values().get(1) instanceof DataValue.IntegerValue pos)
            || !(list.values().get(2) instanceof DataValue.TextValue alias)) throw invalid();
        return new ClusterPortBinding(UUID.fromString(id.value()), BlockPos.of(pos.value()), side, alias.value());
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_gateway"); }
}
