package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingEntityReaderGameTests {
    /** Removing the loaded-target guard must fail without converting unloaded space into an empty list. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unloadedEntityTargetIsAnErrorAndNeverLoadsAChunk(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(2, 2, 2));
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "unloaded-reader"));
        actor.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        // A fresh, distant region inside the normal world border. Do not request/generate a chunk to prepare it.
        var target = origin.offset(1_000_000, 0, 1_000_000);
        helper.assertTrue(!level.hasChunkAt(target), "Fixture target starts unloaded");
        var read = BlockSnapshotReader.readComponents(level, target, Direction.WEST, actor,
            new ReadLimits(10000, 100000, 1000000), Set.of(EntityReadComponents.ENTITIES));
        assertTargetFailure(helper, read, "target_unloaded");
        helper.assertTrue(!level.hasChunkAt(target), "Rejected capture must not synchronously load the target");
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(!level.hasChunkAt(target), "Rejected capture must not schedule a later chunk load");
            helper.succeed();
        });
    }

    /** Removing actor-world authorization must not expose a real indexed entity to a foreign-world actor. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void entityTargetRejectsActorFromAnotherActualDimension(GameTestHelper helper) {
        var level = helper.getLevel();
        var target = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var pig = Objects.requireNonNull(EntityType.PIG.create(level));
        pig.setNoAi(true); pig.setNoGravity(true);
        pig.setPos(target.getX() + .5, target.getY(), target.getZ() + .5);
        helper.assertTrue(level.addFreshEntity(pig), "Fixture must be in the real entity index");
        try {
            var limits = new ReadLimits(10000, 100000, 1000000);
            var ids = Set.of(EntityReadComponents.ENTITIES);
            var local = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "local-reader"));
            var allowed = BlockSnapshotReader.readComponents(level, target, Direction.WEST, local, limits, ids);
            var rows = (DataValue.ListValue) allowed.field(EntityReadComponents.ENTITIES);
            helper.assertValueEqual(rows.values().size(), 1, "Authorized positive control reads the actual pig");
            var foreignLevel = Objects.requireNonNull(level.getServer().getLevel(
                level.dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER));
            helper.assertTrue(foreignLevel != level, "Use a distinct actual server level, not a mocked dimension string");
            var foreign = new FakePlayer(foreignLevel, new GameProfile(UUID.randomUUID(), "foreign-reader"));
            var denied = BlockSnapshotReader.readComponents(level, target, Direction.WEST, foreign, limits, ids);
            assertTargetFailure(helper, denied, "access_denied");
        } finally {
            pig.discard();
        }
        helper.succeed();
    }

    /** Choosing only entities must not bypass the private-Computer target policy. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void entitiesOnlyCannotReadAnotherComputerTarget(GameTestHelper helper) {
        var level = helper.getLevel();
        var target = helper.absolutePos(new BlockPos(2, 2, 2));
        BiocomputingWorldGameTests.computer(helper, target);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "private-reader"));
        var denied = BlockSnapshotReader.readComponents(level, target, Direction.WEST, actor,
            new ReadLimits(10000, 100000, 1000000), Set.of(EntityReadComponents.ENTITIES));
        assertTargetFailure(helper, denied, "access_denied");
        helper.succeed();
    }

    private static void assertTargetFailure(GameTestHelper helper, DataValue.RecordValue result, String expected) {
        helper.assertValueEqual(result.fields().keySet(), Set.of("error"), "Failure must not include successful components or entity data");
        helper.assertTrue(result.field("error") instanceof DataValue.ErrorValue, "Failure retains its typed error value");
        helper.assertValueEqual(((DataValue.ErrorValue) result.field("error")).code(), expected, "Target failure category");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wiredReaderSnapshotsRealEntityIndexWithoutWorldReferences(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = BiocomputingWorldGameTests.computer(helper, origin);
        var reader = origin.east(2); var target = origin.east(3);
        level.setBlock(origin.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(reader, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "entity-read-test"));
        actor.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + 2.5);
        var pig = Objects.requireNonNull(EntityType.PIG.create(level));
        pig.setNoAi(true); pig.setNoGravity(true);
        pig.setPos(target.getX() + .5, target.getY(), target.getZ() + .5);
        pig.setCustomName(Component.literal("before")); pig.setHealth(7.5F);
        helper.assertTrue(level.addFreshEntity(pig), "The fixture must enter the real spatial entity index");
        var snapshot = PeripheralCaptureService.capture(computer, actor);
        var entities = entities(snapshot);
        helper.assertValueEqual(entities.values().size(), 1, "Actual wired target sees exactly one pig, not the nearby Computer resident");
        var row = (DataValue.RecordValue) entities.values().getFirst();
        helper.assertValueEqual(row.field("id"), new DataValue.TextValue(pig.getUUID().toString()), "UUID is ordinary text");
        helper.assertValueEqual(row.field("health"), new DataValue.DecimalValue(7.5), "Real living attribute projection");
        helper.assertValueEqual(row.field("type"), new DataValue.TextValue("minecraft:pig"), "Real entity registry type");
        var archiveLimits = new ArchiveLimits(10000, 100000, 1000000);
        var frozen = ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(entities, archiveLimits), archiveLimits);

        var graph = new ComputationGraph(Map.of("entities", DataType.Scalar.ANY), Map.of(
            "in", new ComputationGraph.Input("entities"), "first", new ComputationGraph.Index("in", 0),
            "id", new ComputationGraph.Field("first", "id"), "name", new ComputationGraph.Field("first", "name"),
            "new_health", new ComputationGraph.Constant(new DataValue.DecimalValue(99)),
            "copy", new ComputationGraph.MakeRecord(Map.of("id", "id", "name", "name", "health", "new_health"))), Map.of("result", "copy"));
        var program = new ProgramDefinition(graph, Map.of("result", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("result", "result")));
        var host = BiocomputingWorldGameTests.host(computer);
        host.install(new ProgramArchive(program, Map.of()), ComputerRuntime.MemoryPolicy.RESET);
        host.begin(new DataValue.RecordValue(Map.of("entities", frozen)));
        pig.setHealth(4); pig.setCustomName(Component.literal("after"));
        helper.runAfterDelay(12, () -> {
            var result = (DataValue.RecordValue) BiocomputingWorldGameTests.memory(host, "result");
            helper.assertValueEqual(result.field("health"), new DataValue.DecimalValue(99), "Public graph changes only the RAM record");
            helper.assertValueEqual(result.field("name"), new DataValue.TextValue("before"), "Computation consumes the frozen read, not a reacquired UUID");
            helper.assertValueEqual(pig.getHealth(), 4.0F, "No world entity write through the computed description");
            helper.assertValueEqual(pig.getName().getString(), "after", "Live world name remains independent");
            var refreshed = (DataValue.RecordValue) entities(PeripheralCaptureService.capture(computer, actor)).values().getFirst();
            helper.assertValueEqual(refreshed.field("health"), new DataValue.DecimalValue(4), "Explicit new capture sees current health");
            pig.setPos(target.getX() + 2.5, target.getY(), target.getZ() + .5);
            helper.assertTrue(entities(PeripheralCaptureService.capture(computer, actor)).values().isEmpty(), "Moving out of cell removes the entity from new captures");
            pig.discard();
            helper.assertValueEqual(((DataValue.RecordValue) ((DataValue.ListValue) frozen).values().getFirst()).field("health"),
                new DataValue.DecimalValue(7.5), "Removal cannot alter serialized snapshots");
            helper.succeed();
        });
    }

    private static DataValue.ListValue entities(DataValue.RecordValue capture) {
        var readers = (DataValue.RecordValue) capture.field("readers");
        if (readers.fields().size() != 1) throw new IllegalStateException("Expected one physically connected reader");
        var snapshot = (DataValue.RecordValue) readers.fields().values().iterator().next();
        return (DataValue.ListValue) ((DataValue.RecordValue) snapshot.field("components")).field(EntityReadComponents.ENTITIES);
    }
}
