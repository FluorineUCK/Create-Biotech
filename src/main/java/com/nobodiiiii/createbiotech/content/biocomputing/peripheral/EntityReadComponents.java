package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/** Public entity facts at the reader's target cell, not handles that can reacquire world objects. */
public final class EntityReadComponents {
    public static final String ENTITIES = "create_biotech:entities";
    private EntityReadComponents() {}

    public static void register(ReadComponentRegistry.Builder<BlockReadContext> builder) {
        var position = new DataType.RecordType(Map.of("x", DECIMAL, "y", DECIMAL, "z", DECIMAL));
        var entity = new DataType.RecordType(Map.of("id", TEXT, "type", TEXT, "dimension", TEXT,
            "position", position, "name", TEXT, "is_player", BOOLEAN, "alive", BOOLEAN,
            "health", new DataType.OptionalType(DECIMAL)));
        builder.register(BuiltinReadComponents.provider(ENTITIES, new DataType.ListType(entity),
            ReadComponentDescriptor.UpdateHint.TICK, EntityReadComponents::read));
    }

    private static DataValue read(BlockReadContext context) {
        var bounds = new AABB(context.target());
        var sink = new ProjectionSink(context);
        try {
            // Only the spatial entity index is queried. The sink, not the query's result limit,
            // enforces the complete value budget, including NeoForge's trailing PartEntity scan.
            context.level().getEntities(EntityTypeTest.forClass(Entity.class), bounds,
                entity -> !entity.isRemoved() && !entity.isSpectator() && entity.getBoundingBox().intersects(bounds),
                sink, Integer.MAX_VALUE);
        } catch (ReadDenied denied) {
            return CapabilitySnapshotValues.error(denied.code);
        }
        sink.rows.sort(Comparator.comparing(row -> ((DataValue.TextValue) row.field("id")).value()));
        return new DataValue.ListValue(new ArrayList<DataValue>(sink.rows));
    }

    /** Capture-local output sink. It stores only projected values, never a second list of live entities. */
    private static final class ProjectionSink extends AbstractList<Entity> {
        private final BlockReadContext context;
        private final SnapshotLimits.Budget budget;
        private final ArrayList<DataValue.RecordValue> rows = new ArrayList<>();
        private long units = 1;

        private ProjectionSink(BlockReadContext context) {
            this.context = context;
            budget = new SnapshotLimits.Budget(context.limits());
            budget.node();
        }
        @Override public int size() { return rows.size(); }
        @Override public Entity get(int index) { throw new UnsupportedOperationException("Projection sink has no entity references"); }
        @Override public boolean add(Entity entity) {
            var level = context.level();
            var position = entity.blockPosition();
            if (entity.level() != level) throw new ReadDenied("access_denied");
            if (!level.isLoaded(position)) throw new ReadDenied("target_unloaded");
            if (!level.getWorldBorder().isWithinBounds(position) || !level.mayInteract(context.actor(), position))
                throw new ReadDenied("access_denied");
            // Retain one overflow character so budget validation rejects, rather than accepts,
            // a truncated name. Stop visiting a potentially large structured text early.
            long nameLimit = Math.min(context.limits().maxStringChars(), context.limits().maxValueUnits());
            int nameCharacters = (int) Math.min(Integer.MAX_VALUE - 1L, nameLimit) + 1;
            var row = new DataValue.RecordValue(Map.of(
                "id", new DataValue.TextValue(entity.getUUID().toString()),
                "type", new DataValue.TextValue(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()),
                "dimension", new DataValue.TextValue(level.dimension().location().toString()),
                "position", new DataValue.RecordValue(Map.of("x", new DataValue.DecimalValue(entity.getX()),
                    "y", new DataValue.DecimalValue(entity.getY()), "z", new DataValue.DecimalValue(entity.getZ()))),
                "name", new DataValue.TextValue(entity.getName().getString(nameCharacters)),
                "is_player", new DataValue.BooleanValue(entity instanceof Player),
                "alive", new DataValue.BooleanValue(entity.isAlive()),
                "health", entity instanceof LivingEntity living ? new DataValue.DecimalValue(living.getHealth()) : DataValue.NullValue.INSTANCE));
            if (row.storageUnits() > context.limits().maxValueUnits() - units) throw SnapshotLimits.exceeded();
            units += row.storageUnits();
            SnapshotLimits.countValue(row, budget);
            rows.add(row);
            return true;
        }
    }

    private static final class ReadDenied extends RuntimeException {
        private final String code;
        private ReadDenied(String code) { this.code = code; }
    }
}
