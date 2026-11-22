package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

/** A member-owned route declaration, not a grant to another Computer's state. */
public record SerialGatewayBinding(UUID controller, BlockPos position, Direction side, String alias) {
    public SerialGatewayBinding {
        Objects.requireNonNull(controller); position = position.immutable(); Objects.requireNonNull(side);
        new HardwarePins(List.of(alias), List.of());
    }
    public CompoundTag write() {
        var tag = new CompoundTag(); tag.putUUID("Controller", controller); tag.putLong("Position", position.asLong());
        tag.putString("Side", side.getName()); tag.putString("Alias", alias); return tag;
    }
    public static SerialGatewayBinding read(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Controller", "Position", "Side", "Alias"))
            || !tag.hasUUID("Controller") || !tag.contains("Position", Tag.TAG_LONG)
            || !tag.contains("Side", Tag.TAG_STRING) || !tag.contains("Alias", Tag.TAG_STRING)) throw new IllegalArgumentException("invalid_gateway");
        var side = Direction.byName(tag.getString("Side"));
        if (side == null) throw new IllegalArgumentException("invalid_gateway");
        return new SerialGatewayBinding(tag.getUUID("Controller"), BlockPos.of(tag.getLong("Position")), side, tag.getString("Alias"));
    }
}
