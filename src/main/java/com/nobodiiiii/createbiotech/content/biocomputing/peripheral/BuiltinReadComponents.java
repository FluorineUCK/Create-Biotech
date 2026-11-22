package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Standard components. No Create machine, logistics or recipe dependencies. */
public final class BuiltinReadComponents {
    public static final String BLOCK = "create_biotech:block", PUBLIC_DATA = "create_biotech:public_data",
        SERIALIZED_DATA = "create_biotech:serialized_data", INVENTORY = "create_biotech:inventory",
        INVENTORY_ITEM_TAGS = "create_biotech:inventory_item_tags", FLUID = "create_biotech:fluid", ENERGY = "create_biotech:energy";
    private BuiltinReadComponents() {}

    public static void register(ReadComponentRegistry.Builder<BlockReadContext> builder) {
        var position = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
        builder.register(provider(BLOCK, new DataType.RecordType(Map.of("id", TEXT, "position", position,
            "state", ANY)), ReadComponentDescriptor.UpdateHint.BLOCK_UPDATE, c ->
                new DataValue.RecordValue(Map.of("id", new DataValue.TextValue(BuiltInRegistries.BLOCK.getKey(c.state().getBlock()).toString()),
                    "position", BlockSnapshotReader.position(c.target()), "state", state(c.state())))));
        builder.register(provider(PUBLIC_DATA, ANY, ReadComponentDescriptor.UpdateHint.TICK, BlockReadContext::publicData));
        builder.register(provider(SERIALIZED_DATA, ANY, ReadComponentDescriptor.UpdateHint.TICK, BlockReadContext::serializedData));
        builder.register(provider(INVENTORY, new DataType.ListType(new DataType.RecordType(Map.of("slot", INTEGER,
            "item", TEXT, "count", INTEGER, "components", ANY))), ReadComponentDescriptor.UpdateHint.TICK,
            BuiltinReadComponents::inventory));
        builder.register(provider(INVENTORY_ITEM_TAGS, new DataType.ListType(new DataType.RecordType(Map.of(
            "slot", INTEGER, "item", TEXT, "tags", new DataType.ListType(TEXT)))),
            ReadComponentDescriptor.UpdateHint.TICK, true, BuiltinReadComponents::inventoryItemTags));
        builder.register(provider(FLUID, new DataType.ListType(new DataType.RecordType(Map.of("tank", INTEGER,
            "fluid", TEXT, "amount", INTEGER, "capacity", INTEGER, "components", ANY))), ReadComponentDescriptor.UpdateHint.TICK,
            c -> CapabilitySnapshotValues.fluid(c.level().getCapability(Capabilities.FluidHandler.BLOCK, c.target(), c.face()),
                c.level().registryAccess(), c.limits())));
        builder.register(provider(ENERGY, new DataType.RecordType(Map.of("stored", INTEGER, "capacity", INTEGER)),
            ReadComponentDescriptor.UpdateHint.TICK,
            c -> CapabilitySnapshotValues.energy(c.level().getCapability(Capabilities.EnergyStorage.BLOCK, c.target(), c.face()), c.limits())));
        WorldReadComponents.register(builder);
        EntityReadComponents.register(builder);
        RedstoneReadComponent.register(builder);
    }
    private static DataValue inventory(BlockReadContext context) {
        var denied = VanillaContainerReadGuard.inventory(context);
        if (denied != null) return denied;
        return CapabilitySnapshotValues.inventory(
            context.level().getCapability(Capabilities.ItemHandler.BLOCK, context.target(), context.face()),
            context.level().registryAccess(), context.limits());
    }

    private static DataValue inventoryItemTags(BlockReadContext context) {
        var denied = VanillaContainerReadGuard.inventory(context);
        if (denied != null) return denied;
        return CapabilitySnapshotValues.inventoryItemTags(
            context.level().getCapability(Capabilities.ItemHandler.BLOCK, context.target(), context.face()), context.limits());
    }

    static ReadComponentProvider<BlockReadContext> provider(String id, DataType type,
            ReadComponentDescriptor.UpdateHint hint, Function<BlockReadContext, DataValue> read) {
        return provider(id, type, hint, false, read);
    }
    private static ReadComponentProvider<BlockReadContext> provider(String id, DataType type,
            ReadComponentDescriptor.UpdateHint hint, boolean additional, Function<BlockReadContext, DataValue> read) {
        var descriptor = new ReadComponentDescriptor(id, "create_biotech.biocomputing.component." + id.substring(id.indexOf(':') + 1),
            Set.of("block"), type, hint, additional);
        return new ReadComponentProvider<>() {
            @Override public ReadComponentDescriptor descriptor() { return descriptor; }
            @Override public boolean supports(BlockReadContext context) { return true; }
            @Override public DataValue read(BlockReadContext context) { return read.apply(context); }
            @Override public DataValue readRange(BlockReadContext context, ReadRange range) {
                if (!id.equals(INVENTORY_ITEM_TAGS)) return ReadComponentProvider.super.readRange(context, range);
                var denied = VanillaContainerReadGuard.inventory(context);
                if (denied != null) return denied;
                return CapabilitySnapshotValues.inventoryItemTags(
                    context.level().getCapability(Capabilities.ItemHandler.BLOCK, context.target(), context.face()), context.limits(), range);
            }
        };
    }
    private static DataValue.RecordValue state(BlockState state) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (Property<?> property : state.getProperties()) fields.put(property.getName(), new DataValue.TextValue(propertyValue(state, property)));
        return new DataValue.RecordValue(fields);
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
