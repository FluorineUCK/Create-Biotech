package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** Create-specific data is registered like any other additional component. */
public final class CreateReadComponents {
    public static final String KINETICS = "create_biotech:create_kinetics", LOGISTICS = "create_biotech:create_logistics";
    public static final String STOCK_APPLICATION = "create_biotech:stock";
    private CreateReadComponents() {}

    public static void register(ReadComponentRegistry.Builder<BlockReadContext> builder) {
        builder.register(new CreateFluidReadComponent());
        builder.register(provider(KINETICS, new DataType.RecordType(Map.of("speed", DECIMAL, "theoretical_speed", DECIMAL,
            "overstressed", BOOLEAN, "network_capacity", ANY, "network_stress", ANY)),
            c -> c.entity() instanceof KineticBlockEntity, CreateReadComponents::kinetics));
        // Link and packager records have different shapes; their actual fields remain browsable.
        builder.register(provider(LOGISTICS, ANY, c -> c.entity() instanceof PackagerBlockEntity
            || c.entity() instanceof SmartBlockEntity smart && smart.getBehaviour(LogisticallyLinkedBehaviour.TYPE) != null,
            CreateReadComponents::logistics));
        builder.register(provider(STOCK_APPLICATION, ANY, c -> c.entity() instanceof PackagerLinkBlockEntity,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationBehaviour::readComponent));
    }

    /** Target-wide policy also protects generic serialized-data reads. */
    public static Optional<DataValue.ErrorValue> deny(BlockReadContext context) {
        if (context.entity() instanceof SmartBlockEntity smart) {
            var link = smart.getBehaviour(LogisticallyLinkedBehaviour.TYPE);
            if (link != null && !link.mayInteract(context.actor()))
                return Optional.of(CapabilitySnapshotValues.error("access_denied"));
        }
        return context.entity() instanceof PackagerBlockEntity
            ? Optional.ofNullable(attachedLinks(context).error()) : Optional.empty();
    }

    private static ReadComponentProvider<BlockReadContext> provider(String id, DataType type,
            Predicate<BlockReadContext> supports, Function<BlockReadContext, DataValue> read) {
        var descriptor = new ReadComponentDescriptor(id, "create_biotech.biocomputing.component." + id.substring(id.indexOf(':') + 1),
            Set.of("block"), type, ReadComponentDescriptor.UpdateHint.TICK, true);
        return new ReadComponentProvider<>() {
            @Override public ReadComponentDescriptor descriptor() { return descriptor; }
            @Override public boolean supports(BlockReadContext context) { return supports.test(context); }
            @Override public DataValue read(BlockReadContext context) { return read.apply(context); }
        };
    }

    private static DataValue kinetics(BlockReadContext context) {
        var kinetic = (KineticBlockEntity) context.entity();
        DataValue publicData = context.publicData();
        return new DataValue.RecordValue(Map.of("speed", new DataValue.DecimalValue(kinetic.getSpeed()),
            "theoretical_speed", new DataValue.DecimalValue(kinetic.getTheoreticalSpeed()),
            "overstressed", new DataValue.BooleanValue(kinetic.isOverStressed()),
            // Last synchronized network aggregates, never recalculated per-block contributions.
            "network_capacity", networkNumber(publicData, "Capacity"), "network_stress", networkNumber(publicData, "Stress")));
    }

    private static DataValue networkNumber(DataValue publicData, String key) {
        if (publicData instanceof DataValue.ErrorValue) return publicData;
        if (publicData instanceof DataValue.RecordValue record
            && record.fields().get("Network") instanceof DataValue.RecordValue network) {
            DataValue value = network.fields().get(key);
            if (value instanceof DataValue.IntegerValue || value instanceof DataValue.DecimalValue) return value;
        }
        return CapabilitySnapshotValues.unsupported();
    }

    private static DataValue logistics(BlockReadContext context) {
        var entity = context.entity();
        var actor = context.actor();
        if (entity instanceof SmartBlockEntity smart) {
            var linked = smart.getBehaviour(LogisticallyLinkedBehaviour.TYPE);
            if (linked != null) return linkDescription(linked, actor);
        }
        if (!(entity instanceof PackagerBlockEntity)) return CapabilitySnapshotValues.unsupported();
        var attached = attachedLinks(context);
        if (attached.error() != null) return attached.error();
        if (attached.links().isEmpty()) return CapabilitySnapshotValues.unsupported();
        var links = new ArrayList<DataValue>();
        for (var link : attached.links()) links.add(linkDescription(link, actor));
        return new DataValue.RecordValue(Map.of("attached_links", new DataValue.ListValue(links),
            "inventory_summary", CapabilitySnapshotValues.unsupported()));
    }

    /** Shared authorization scope: selecting a different provider cannot bypass attached ownership. */
    private static AttachedLinks attachedLinks(BlockReadContext context) {
        var level = context.level();
        var actor = context.actor();
        var target = context.target();
        var links = new ArrayList<LogisticallyLinkedBehaviour>();
        // Only six physically adjacent, loaded, correctly attached stock links are relevant.
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = target.relative(direction);
            if (!level.isLoaded(adjacent)) return AttachedLinks.failure("target_unloaded");
            if (!level.getWorldBorder().isWithinBounds(adjacent) || !level.mayInteract(actor, adjacent))
                return AttachedLinks.failure("access_denied");
            if (!(level.getBlockEntity(adjacent) instanceof PackagerLinkBlockEntity link)) continue;
            Direction attachedDirection = PackagerLinkBlock.getConnectedDirection(link.getBlockState()).getOpposite();
            if (!adjacent.relative(attachedDirection).equals(target) || link.behaviour == null) continue;
            if (!link.behaviour.mayInteract(actor)) return AttachedLinks.failure("access_denied");
            links.add(link.behaviour);
        }
        return new AttachedLinks(List.copyOf(links), null);
    }

    private record AttachedLinks(List<LogisticallyLinkedBehaviour> links, DataValue.ErrorValue error) {
        static AttachedLinks failure(String code) { return new AttachedLinks(List.of(), CapabilitySnapshotValues.error(code)); }
    }

    private static DataValue linkDescription(LogisticallyLinkedBehaviour link, ServerPlayer actor) {
        if (!link.mayInteract(actor)) return CapabilitySnapshotValues.error("access_denied");
        if (link.freqId == null) return CapabilitySnapshotValues.unsupported();
        // getSummary/getAvailableItems refresh caches and submit arrivals/promises: not read-only.
        return new DataValue.RecordValue(Map.of("network", new DataValue.TextValue(link.freqId.toString()),
            "redstone_power", CapabilitySnapshotValues.integer(link.redstonePower),
            "inventory_summary", CapabilitySnapshotValues.unsupported()));
    }
}
