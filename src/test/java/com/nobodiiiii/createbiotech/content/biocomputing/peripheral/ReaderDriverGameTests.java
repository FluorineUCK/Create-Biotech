package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real catalog, separate residents, sealed Cluster, finite wire, reader and terminal data; no Java sampling. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ReaderDriverGameTests {
    private static final String REDSTONE = "create_biotech:redstone";

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void publicTypedCustomBlockRestoresItsOwnCheckBeforeComputing(GameTestHelper h) {
        var level = h.getLevel();
        var computer = computer(h, h.absolutePos(new BlockPos(2, 2, 2)), VillagerProfession.CLERIC);
        var document = loadEntry("value_check_integer");
        var ordinary = computer(h, h.absolutePos(new BlockPos(4, 2, 2)), VillagerProfession.MASON);
        var ordinaryBefore = new net.minecraft.nbt.CompoundTag();
        ordinary.write(ordinaryBefore, level.registryAccess(), false);
        boolean refused = false;
        try { ordinary.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable expected) {
            refused = expected.getMessage().contains("ISA-1a");
        }
        h.assertTrue(refused, "A nearby Parser cannot grant an ordinary Computer ISA-1a");
        var ordinaryAfter = new net.minecraft.nbt.CompoundTag();
        ordinary.write(ordinaryAfter, level.registryAccess(), false);
        h.assertValueEqual(ordinaryAfter, ordinaryBefore, "Rejected install leaves real ordinary owner unchanged");
        h.assertTrue(computer.hardware().limits().isa1a(), "Actual cleric resident selects Parser");
        computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        var hardware = computer.hardware();
        for (int n = 0; n < 30 && computer.flowSnapshot().orElseThrow().state().assembly().orElseThrow().comparison().isEmpty(); n++) computer.step();
        var before = computer.flowSnapshot().orElseThrow().state();
        h.assertTrue(before.assembly().orElseThrow().comparison().isPresent(), "Real metered type-check continuation");
        h.assertTrue(before.paused(), "Single stepping remains paused");
        h.assertTrue(!before.locals().fields().containsKey("checked"), "No partially checked result");
        h.assertValueEqual(before.locals().field("next"), DataValue.NullValue.INSTANCE, "Declared null sentinel is not a computed value");
        var archive = computer.blockEntity.saveWithFullMetadata(level.registryAccess());
        computer.blockEntity.loadWithComponents(archive, level.registryAccess());
        computer.terminalStatus(); // Authorized observation restores the saved owner, not execution.
        h.assertValueEqual(computer.flowSnapshot().orElseThrow().state(), before, "Same-process BE restore preserves paid progress");
        for (int n = 0; n < 100 && computer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; n++) computer.step();
        var output = (DataValue.RecordValue)computer.terminalStatus().field("output");
        h.assertValueEqual(output.field("next"), new DataValue.IntegerValue(16), "Typed integer is usable by ordinary arithmetic");
        h.assertValueEqual(((DataValue.RecordValue)output.field("checked")).field("ok"), new DataValue.BooleanValue(true), "Explicit match result");
        var draft = new FlowDraft(document.flow().orElseThrow()); draft.replaceInitial("reading", new DataValue.TextValue("15"));
        var workspace = new ProgramWorkspace(document); workspace.setFlow(Optional.of(draft.snapshot()));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        for (int n = 0; n < 100 && computer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; n++) computer.step();
        var rejected = (DataValue.RecordValue)computer.terminalStatus().field("output");
        h.assertValueEqual(((DataValue.RecordValue)rejected.field("checked")).field("ok"), new DataValue.BooleanValue(false), "Text is a handled mismatch, not a machine fault");
        h.assertValueEqual(rejected.field("next"), DataValue.NullValue.INSTANCE, "Wrong type does not run arithmetic with a fabricated zero");
        h.assertValueEqual(computer.hardware(), hardware, "No hardware upgrade");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 8000)
    public static void editableDriverSamplesAgainOnlyAfterExplicitRestart(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(6, 3, 4);
        var controllerPos = min.offset(6, 1, 2); var readerPos = controllerPos.east(4);
        var computers = new ArrayList<ComputerBehaviour>();
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        for (int n = 0; n < 3; n++) {
            String role = String.valueOf((char)('a' + n));
            var computer = computer(h, min.offset(1 + 2 * n, 1, 1), VillagerProfession.CLERIC);
            var document = load(role);
            if (role.equals("a")) {
                var draft = new FlowDraft(document.flow().orElseThrow());
                draft.replaceInitial("target", record("x", readerPos.getX(), "y", readerPos.getY(), "z", readerPos.getZ()));
                var workspace = new ProgramWorkspace(document); workspace.setFlow(Optional.of(draft.snapshot()));
                document = workspace.snapshot();
            }
            computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
            computer.configureEndpoint(new EndpointConfiguration(role, true));
            members.put(role, new ClusterConfiguration.Member(computer.communicationIdentity().orElseThrow(), computer.blockEntity.getBlockPos()));
            computers.add(computer);
        }
        for (var p : BlockPos.betweenClosed(min, max))
            if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY() || p.getY() == max.getY()
                    || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reader-driver"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of(
            "ab", new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true),
            "bc", new XBusWire(new XBusWire.Pin("b", "b", "x1"), new XBusWire.Pin("c", "c", "x0"), true))), actor), "configured", "Explicit internal XBus wiring");
        var b = computers.get(1);
        var exposed = SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, b.blockEntity), actor, "s0", "sampling");
        b.configureSerialGateway("s0", Optional.of(exposed));
        b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        computers.get(2).configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true)); // Not exposed.
        for (int x = 1; x < 4; x++) level.setBlock(controllerPos.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(readerPos, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, Direction.EAST), 3);
        level.setBlock(readerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        var reader = (BlockReaderBlockEntity)level.getBlockEntity(readerPos);
        var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
        h.assertTrue(service.binding().isEmpty(), "Reader is closed by default");
        var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        var documents = computers.stream().map(c -> c.document().orElseThrow()).toList();
        h.runAfterDelay(4, () -> {
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(readerPos));
            var sources = service.discover(actor);
            h.assertValueEqual(sources.size(), 1, "Only the exposed member can be selected");
            h.assertValueEqual(sources.getFirst().member(), b.blockEntity.getBlockPos(), "No implicit bridge to C");
            h.assertTrue(sources.getFirst().gateway().isPresent(), "Actual concentrated I/O proof");
            h.assertValueEqual(service.configure(actor, 0, Optional.of(sources.getFirst()), Set.of(REDSTONE)), "configured", "Explicit source and component grant");
            h.assertTrue(level.getServer().getPlayerList().getPlayer(actor.getUUID()) == null, "Granting player is offline");
            h.runAfterDelay(4, () -> {
                for (int n : List.of(2, 1, 0)) computers.get(n).setPaused(false);
                await(h, computers, () -> {
                    var a = computers.getFirst(); var first = result(h, a);
                    h.assertValueEqual(signal(first), new DataValue.IntegerValue(15), "Program obtains a fresh world signal without any open menu");
                    level.setBlock(readerPos.east(), Blocks.STONE.defaultBlockState(), 3);
                    h.runAfterDelay(80, () -> {
                        h.assertValueEqual(result(h, a), first, "No implicit world resampling after completion");
                        a.reset(); a.setPaused(false);
                        await(h, computers, () -> {
                            var second = result(h, a);
                            h.assertValueEqual(signal(second), new DataValue.IntegerValue(0), "Explicit restart requests a new snapshot");
                            h.assertValueEqual(signal(first), new DataValue.IntegerValue(15), "Old received value is immutable");
                            h.assertTrue(!first.field("request").equals(second.field("request")), "New request has its own correlation");
                            h.assertTrue(level.getBlockState(readerPos.east()).is(Blocks.STONE), "Sampling never modifies the source");
                            h.assertTrue(controller.enclosure().enclosure().isPresent(), "Outer shell and interior air remain a valid Cluster");
                            for (int n = 0; n < 3; n++) {
                                h.assertValueEqual(computers.get(n).hardware(), hardware.get(n), "No raised or pooled hardware limits");
                                h.assertValueEqual(computers.get(n).document().orElseThrow(), documents.get(n), "No hidden program replacement");
                                h.assertTrue(computers.get(n).flowSnapshot().orElseThrow().state().assembly().isPresent(), "Existing ISA backend");
                            }
                            h.assertValueEqual(service.configure(actor, service.revision(), service.binding(), Set.of(BuiltinReadComponents.BLOCK)),
                                "configured", "Player explicitly narrows the allowed component set");
                            h.runAfterDelay(4, () -> {
                                a.reset(); a.setPaused(false);
                                await(h, computers, () -> {
                                    var failed = (DataValue.RecordValue)a.terminalStatus().field("output");
                                    h.assertValueEqual(failed.field("ok"), new DataValue.BooleanValue(false), "Business rejection is visible to the program");
                                    var reply = (DataValue.RecordValue)failed.field("reply");
                                    h.assertValueEqual(reply.field("code"), new DataValue.TextValue("component_denied"), "Fresh request cannot bypass the new grant");
                                    h.assertTrue(!reply.fields().containsKey("value"), "Rejected sample does not leak a snapshot");
                                    h.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    private static DataValue.RecordValue result(GameTestHelper h, ComputerBehaviour a) {
        var output = (DataValue.RecordValue)a.terminalStatus().field("output");
        h.assertValueEqual(output.field("ok"), new DataValue.BooleanValue(true), "Public terminal result indicates a matched sampling success");
        var reply = (DataValue.RecordValue)output.field("reply");
        h.assertValueEqual(reply.field("code"), new DataValue.TextValue("ok"), "Business reply, not send completion");
        return reply;
    }
    private static DataValue signal(DataValue.RecordValue reply) {
        return ((DataValue.RecordValue)((DataValue.RecordValue)((DataValue.RecordValue)reply.field("value")).field("components")).field(REDSTONE)).field("signal");
    }
    private static void await(GameTestHelper h, List<ComputerBehaviour> computers, Runnable finish) {
        h.runAfterDelay(5, () -> {
            for (var computer : computers) {
                h.assertTrue(computer.diagnostic().isEmpty(), computer.diagnostic().toString());
                h.assertTrue(computer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.FAILED,
                    computer.flowSnapshot().orElseThrow().state().diagnostic().toString());
            }
            if (computers.getFirst().flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) finish.run();
            else await(h, computers, finish);
        });
    }
    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos, VillagerProfession profession) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var state = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, 3); level.setBlock(pos.above(), state.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var resident = EntityType.VILLAGER.create(level);
        resident.setBaby(false); resident.setVillagerData(resident.getVillagerData().setProfession(profession).setLevel(5));
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Captured resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reader-install"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)); actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked hardware installation");
        return block.getBehaviour(ComputerBehaviour.TYPE);
    }
    private static ProgramDocument load(String role) {
        return loadEntry("reader_sample_" + role);
    }
    static ProgramDocument loadEntry(String entryId) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> resources = id ->
            ReaderDriverGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var entry = ProgramExampleCatalog.load(resources).stream().filter(e -> e.id().equals(entryId)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, resources, new ArchiveLimits(10000, 100000, 1000000));
    }
    private static DataValue.RecordValue record(Object... pairs) {
        var values = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String)pairs[i], new DataValue.IntegerValue(((Number)pairs[i + 1]).longValue()));
        return new DataValue.RecordValue(values);
    }
}
