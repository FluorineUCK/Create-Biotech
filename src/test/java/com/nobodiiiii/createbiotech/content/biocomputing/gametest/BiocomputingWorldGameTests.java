package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationEndpoint;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server/registry tests of the transitional physical host, not a new Cluster implementation. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingWorldGameTests {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final Map<String, DataType> FIELDS = Map.of("name", DataType.Scalar.TEXT, "value", DataType.Scalar.INTEGER);
    private static final DataValue.RecordValue SAMPLE = record("temperature", 21);

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void computerPhysicalRegistrationBelongsToBiocomputingAndKeepsDoubleBlockIdentity(GameTestHelper helper) {
        var block = CBBlocks.COMPUTER.get();
        helper.assertValueEqual(block.getClass().getName(),
            "com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock", "Actual new physical definition, not a forwarding old block");
        helper.assertValueEqual(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(),
            "create_biotech:computer", "Historical block identity retained");
        var lower = block.defaultBlockState();
        var upper = lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER);
        helper.assertValueEqual(lower.getRenderShape(), net.minecraft.world.level.block.RenderShape.MODEL, "Original model remains visible");
        helper.assertValueEqual(upper.getRenderShape(), net.minecraft.world.level.block.RenderShape.MODEL, "Upper model remains visible");
        helper.assertTrue(block.newBlockEntity(BlockPos.ZERO.above(), upper) == null, "Upper half never owns another BE");
        helper.assertTrue(block.newBlockEntity(BlockPos.ZERO, lower).getType() == CBBlockEntityTypes.COMPUTER.get(), "Lower uses real registered host");
        helper.assertValueEqual(block.newBlockEntity(BlockPos.ZERO, lower).getClass().getName(),
            "com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity",
            "The registered physical entity is owned by biocomputing, not a legacy production host");
        var physical = (ComputerBlockEntity) block.newBlockEntity(BlockPos.ZERO, lower);
        var physicalSave = physical.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("ComputerData");
        helper.assertValueEqual(physicalSave.getAllKeys(), java.util.Set.of("Version", "ComputerId"),
            "A newly placed Computer stores only its physical identity, not a production runtime");
        helper.assertTrue(physicalSave.hasUUID("ComputerId"), "Physical identity is present");
        helper.assertTrue(block.getTicker(helper.getLevel(), upper, CBBlockEntityTypes.COMPUTER.get()) == null, "Upper does not tick a duplicate host");
        helper.assertTrue(block.getTicker(helper.getLevel(), lower, net.minecraft.world.level.block.entity.BlockEntityType.CHEST) == null,
            "Unrelated BE type is never ticked");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void pausedEventSurvivesRealBlockEntityReloadAndRunsOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = computer(helper, pos);
        var host = host(block);
        helper.assertTrue(block.getType() == CBBlockEntityTypes.COMPUTER.get(), "Use the actually registered Computer");
        host.install(counter(), ComputerRuntime.MemoryPolicy.RESET);
        host.setPaused(true);
        host.begin(EMPTY);
        var id = host.snapshot().orElseThrow().id();
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(memory(host, "count"), new DataValue.IntegerValue(0), "Paused real ticks must not commit RAM");
            var restored = host(reload(level, block));
            restored.document(); // Normal authorized observation restores the durable host state.
            helper.assertValueEqual(restored.snapshot().orElseThrow().id(), id, "Reload must preserve the Computer identity");
            helper.assertTrue(restored.snapshot().orElseThrow().machine().paused(), "Reload must preserve pause");
            restored.setPaused(false);
            helper.runAfterDelay(20, () -> {
                helper.assertValueEqual(memory(restored, "count"), new DataValue.IntegerValue(1), "Server ticks must complete the installed graph");
                var completed = host(reload(level, (ComputerBlockEntity) level.getBlockEntity(pos)));
                completed.document();
                helper.assertValueEqual(memory(completed, "count"), new DataValue.IntegerValue(1), "Completed RAM must survive reload");
                helper.runAfterDelay(12, () -> {
                    helper.assertValueEqual(memory(completed, "count"), new DataValue.IntegerValue(1), "Reload must not replay the completed event");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void disconnectedNamedValuesSurviveReloadAndReachAnIndependentComputer(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = helper.absolutePos(new BlockPos(2, 2, 2));
        var b = a.offset(3, 0, 0);
        var sourceBlock = computer(helper, a);
        var destinationBlock = computer(helper, b, VillagerProfession.CLERIC, 5); // Receiver constructs and stores a typed record.
        var source = host(sourceBlock);
        var destination = host(destinationBlock);
        source.install(sender(), ComputerRuntime.MemoryPolicy.RESET);
        destination.install(receiver(), ComputerRuntime.MemoryPolicy.RESET);
        source.configureEndpoint(new EndpointConfiguration("source", true));
        destination.configureEndpoint(new EndpointConfiguration("receiver", true));
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, a, Direction.EAST) == source,
            "The source must expose its real registered communication capability");
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, b, Direction.WEST) == destination,
            "The destination must expose its real registered communication capability");
        level.setBlock(a.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        // The second cable is deliberately absent until after the pending send has been saved and reloaded.
        source.begin(EMPTY);
        helper.runAfterDelay(12, () -> {
            helper.assertValueEqual(memory(destination, "received"), record("", 0), "A disconnected target must not receive a value");
            var committed = source.snapshot().orElseThrow();
            helper.assertValueEqual(committed.machine().status(), ComputationMachine.Status.SUCCEEDED,
                "The graph must have committed before testing disconnected outbox persistence");
            helper.assertValueEqual(committed.delivery().orElseThrow().acknowledged(), 0, "The committed message must still be unacknowledged");
            helper.assertValueEqual(source.transportResult().orElseThrow().code(), "not_found",
                "The real transport must have attempted delivery and observed the disconnected target");
            var restored = host(reload(level, sourceBlock));
            restored.document();
            var restoredState = restored.snapshot().orElseThrow();
            helper.assertValueEqual(restoredState.machine().status(), ComputationMachine.Status.SUCCEEDED,
                "Reload must retain the committed event instead of starting a replacement computation");
            helper.assertValueEqual(restoredState.id(), committed.id(), "Reload must retain the sender identity");
            helper.assertValueEqual(restoredState.delivery(), committed.delivery(),
                "Reload must retain the same event identity, sequence base and unacknowledged cursor");
            level.setBlock(a.offset(2, 0, 0), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(30, () -> {
                helper.assertValueEqual(memory(destination, "received"), SAMPLE, "Real cable ticks must deliver both named values");
                helper.assertValueEqual(restored.snapshot().orElseThrow().delivery().orElseThrow().acknowledged(), 1,
                    "Accepted delivery must durably advance the existing completion cursor");
                helper.assertValueEqual(memory(restored, "sentinel"), new DataValue.IntegerValue(99), "The recipient must not share the sender's RAM");
                helper.assertValueEqual(memory(destination, "deliveries"), new DataValue.IntegerValue(1), "One send must trigger exactly one receiver event");
                helper.assertValueEqual(SAMPLE, record("temperature", 21), "Message processing must not mutate the source value");
                var savedSender = host(reload(level, (ComputerBlockEntity) level.getBlockEntity(a)));
                var savedReceiver = host(reload(level, destinationBlock));
                savedSender.document(); savedReceiver.document();
                helper.runAfterDelay(12, () -> {
                    helper.assertValueEqual(memory(savedReceiver, "deliveries"), new DataValue.IntegerValue(1),
                        "Reloading both endpoints after acknowledgement must not replay the receiver event");
                    helper.assertValueEqual(memory(savedReceiver, "received"), SAMPLE, "Recipient RAM must persist independently");
                    helper.succeed();
                });
            });
        });
    }

    static ComputerBlockEntity computer(GameTestHelper helper, BlockPos pos) {
        return computer(helper, pos, VillagerProfession.NONE, 1);
    }

    static ComputerBlockEntity computer(GameTestHelper helper, BlockPos pos, VillagerProfession profession, int residentLevel) {
        var level = helper.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, Block.UPDATE_ALL);
        level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var block = (ComputerBlockEntity) Objects.requireNonNull(level.getBlockEntity(pos));
        var villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
        villager.setBaby(false);
        villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, residentLevel));
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Capture an actual supported resident");
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "biocomputing-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(pos));
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        helper.assertValueEqual(block.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS,
            "Install resident through the existing checked player operation");
        return block;
    }

    private static ComputerBlockEntity reload(ServerLevel level, ComputerBlockEntity before) {
        var pos = before.getBlockPos();
        var state = before.getBlockState();
        var saved = before.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var after = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(after);
        return after;
    }

    static ComputerBehaviour host(ComputerBlockEntity block) {
        return Objects.requireNonNull(block.getBehaviour(ComputerBehaviour.TYPE));
    }

    static DataValue memory(ComputerBehaviour host, String cell) {
        return host.snapshot().orElseThrow().machine().memory().field(cell);
    }

    static DataValue.RecordValue record(String name, long value) {
        return new DataValue.RecordValue(Map.of("name", new DataValue.TextValue(name), "value", new DataValue.IntegerValue(value)));
    }

    static ProgramArchive counter() {
        var graph = new ComputationGraph(Map.of(), Map.of(
            "old", new ComputationGraph.ReadMemory("count"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")), Map.of("next", "next"));
        return new ProgramArchive(new ProgramDefinition(graph,
            Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(new ProgramDefinition.Write("count", "next"))), Map.of());
    }

    static ProgramArchive sender() {
        var graph = new ComputationGraph(Map.of(), Map.of(
            "to", new ComputationGraph.Constant(new DataValue.TextValue("receiver")),
            "payload", new ComputationGraph.Constant(SAMPLE)), Map.of("to", "to", "payload", "payload"));
        return new ProgramArchive(new ProgramDefinition(graph,
            Map.of("sentinel", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(99))),
            List.of(), List.of(new ProgramDefinition.Send("to", "payload"))), Map.of());
    }

    static ProgramArchive receiver() {
        var message = new ComputationGraph(FIELDS, Map.of(
            "name", new ComputationGraph.Input("name"), "value", new ComputationGraph.Input("value"),
            "received", new ComputationGraph.MakeRecord(Map.of("name", "name", "value", "value")),
            "old", new ComputationGraph.ReadMemory("deliveries"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
            Map.of("received", "received", "deliveries", "next"));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("received", new ProgramDefinition.Cell(new DataType.RecordType(FIELDS), record("", 0)),
                "deliveries", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(), List.of(),
            Map.of("message", new ProgramDefinition.Entry(message, List.of(new ProgramDefinition.Write("received", "received"),
                new ProgramDefinition.Write("deliveries", "deliveries")), List.of()))), Map.of());
    }
}
