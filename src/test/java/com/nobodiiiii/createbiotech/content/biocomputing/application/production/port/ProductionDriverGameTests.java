package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

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
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Published, editable assembly constructs every command; no test-side request builder drives the device. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ProductionDriverGameTests {
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicAssemblyPackagesOnceAndQueriesRestoredOrder(GameTestHelper h) {
        var level = h.getLevel(); var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(6, 3, 4);
        var controllerPos = min.offset(6, 1, 2); var portPos = controllerPos.east(4);
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        var computers = new ArrayList<ComputerBehaviour>();
        var target = record("x", portPos.getX(), "y", portPos.getY(), "z", portPos.getZ());
        var items = new DataValue.ListValue(List.of(record("item", "minecraft:stone", "count", 4)));
        var initial = record("target", target, "job", "assembly-order", "items", items);
        for (int n = 0; n < 3; n++) {
            String role = String.valueOf((char)('a' + n));
            var computer = computer(h, min.offset(1 + n * 2, 1, 1));
            var document = load(n == 0 ? "production_submit_a" : "production_" + role);
            computer.installDocument(n == 0 ? inputs(document, initial) : document, ComputerRuntime.MemoryPolicy.RESET);
            computer.configureEndpoint(new EndpointConfiguration(role, true));
            computers.add(computer);
            members.put(role, new ClusterConfiguration.Member(computer.communicationIdentity().orElseThrow(), computer.blockEntity.getBlockPos()));
        }
        for (var p : BlockPos.betweenClosed(min, max))
            if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY() || p.getY() == max.getY()
                    || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "production-driver"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of(
            "ab", new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true),
            "bc", new XBusWire(new XBusWire.Pin("b", "b", "x1"), new XBusWire.Pin("c", "c", "x0"), true))), actor), "configured", "Explicit internal wiring");
        var b = computers.get(1);
        b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, b.blockEntity), actor, "s0", "output")));
        b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        for (int x = 1; x < 4; x++) level.setBlock(controllerPos.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var panel = CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        level.setBlock(portPos, panel, 3);
        var host = (SmartBlockEntity)level.getBlockEntity(portPos); var port = host.getBehaviour(ProductionPortBehaviour.TYPE);
        var packagerPos = portPos.south(3);
        level.setBlock(packagerPos.west(), Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST), 3);
        level.setBlock(packagerPos.north(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
            .setValue(PackagerLinkBlock.FACING, Direction.NORTH), 3);
        var link = (PackagerLinkBlockEntity)level.getBlockEntity(packagerPos.north()); var network = UUID.randomUUID();
        LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
        var chest = (Container)level.getBlockEntity(packagerPos.west()); chest.setItem(0, new ItemStack(Items.STONE, 64));
        var packager = (PackagerBlockEntity)level.getBlockEntity(packagerPos);
        var tuned = new ItemStack(AllBlocks.STOCK_LINK.asItem()); var frequency = new CompoundTag(); frequency.putUUID("Freq", network);
        tuned.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(frequency)); actor.setItemInHand(InteractionHand.MAIN_HAND, tuned);
        var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        h.runAfterDelay(30, () -> {
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(portPos));
            var choices = port.discover(actor);
            h.assertValueEqual(choices.size(), 1, "Only the published gateway can submit");
            h.assertTrue(choices.getFirst().gateway().isPresent(), "Concentrated I/O, not a direct member bridge");
            h.assertValueEqual(port.configure(actor, port.revision(), Optional.of(choices.getFirst()), "workshop"), "configured", "Explicit device grant");
            h.runAfterDelay(3, () -> {
                for (int n : List.of(2, 1, 0)) computers.get(n).setPaused(false);
                await(h, computers, () -> {
                    var a = computers.getFirst(); var reply = reply(a);
                    h.assertValueEqual(reply.field("code"), new DataValue.TextValue("accepted"), "Assembly-built submit is admitted");
                    h.assertValueEqual(chest.getItem(0).getCount(), 60, "Actual native inventory loses exactly four items");
                    h.assertValueEqual(PackageItem.getAddress(packager.heldBox), "workshop", "Frozen granted address");
                    var contents = PackageItem.getContents(packager.heldBox); int count = 0;
                    for (int slot = 0; slot < contents.getSlots(); slot++) if (contents.getStackInSlot(slot).is(Items.STONE)) count += contents.getStackInSlot(slot).getCount();
                    h.assertValueEqual(count, 4, "Actual package contains the requested material");
                    h.assertValueEqual(a.flowSnapshot().orElseThrow().initialInputs(), initial, "Source list remains immutable");
                    var originalCorrelation = reply.field("request");
                    a.reset(); a.setPaused(false);
                    await(h, computers, () -> {
                        h.assertValueEqual(reply(a).field("code"), new DataValue.TextValue("existing"), "Repeating the public block reuses the business order");
                        h.assertTrue(!reply(a).field("request").equals(originalCorrelation), "New wire correlation for the same stable job");
                        h.assertValueEqual(chest.getItem(0).getCount(), 60, "No second material extraction");
                        var saved = host.saveWithoutMetadata(level.registryAccess());
                        level.removeBlock(portPos, false); level.setBlock(portPos, panel, 3);
                        ((SmartBlockEntity)level.getBlockEntity(portPos)).loadWithComponents(saved, level.registryAccess());
                        a.reset();
                        a.installDocument(inputs(load("production_poll_a"), record("target", target, "job", "assembly-order")), ComputerRuntime.MemoryPolicy.RESET);
                        a.setPaused(false);
                        await(h, computers, () -> {
                            h.assertValueEqual(reply(a).field("phase"), new DataValue.TextValue("waiting"), "Persisted dispatch is not guessed to be production completion");
                            h.assertValueEqual(chest.getItem(0).getCount(), 60, "Restoration never redispatches");
                            h.assertTrue(controller.enclosure().enclosure().isPresent(), "Air-filled sealed cluster remains valid");
                            for (int n = 0; n < 3; n++) {
                                h.assertValueEqual(computers.get(n).hardware(), hardware.get(n), "No extra or pooled hardware");
                                h.assertTrue(computers.get(n).flowSnapshot().orElseThrow().state().assembly().isPresent(), "ISA alone executes");
                            }
                            // A real receiver accepts the original package while the service host is absent.
                            var currentHost = (SmartBlockEntity)level.getBlockEntity(portPos);
                            var pending = currentHost.saveWithoutMetadata(level.registryAccess());
                            level.removeBlock(portPos, false);
                            var destination = packagerPos.south(4);
                            level.setBlock(destination.west(), Blocks.CHEST.defaultBlockState(), 3);
                            level.setBlock(destination, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST), 3);
                            level.setBlock(destination.north(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
                                .setValue(PackagerLinkBlock.FACING, Direction.NORTH), 3);
                            var receiverLink = (PackagerLinkBlockEntity)level.getBlockEntity(destination.north());
                            LogisticallyLinkedBehaviour.remove(receiverLink.behaviour); receiverLink.behaviour.freqId = network;
                            LogisticallyLinkedBehaviour.keepAlive(receiverLink.behaviour);
                            var receiver = (PackagerBlockEntity)level.getBlockEntity(destination);
                            var originalPackage = packager.heldBox.copy();
                            h.assertTrue(receiver.unwrapBox(originalPackage, true), "Simulated native unpack is possible");
                            h.assertTrue(((Container)level.getBlockEntity(destination.west())).isEmpty(), "Simulation does not deliver");
                            h.assertTrue(new PackagerItemHandler(receiver).insertItem(0, originalPackage, false).isEmpty(), "Actual native package insertion succeeds");
                            packager.heldBox = ItemStack.EMPTY; packager.setChanged();
                            h.assertValueEqual(((Container)level.getBlockEntity(destination.west())).getItem(0).getCount(), 4, "Four actual items delivered");
                            level.setBlock(portPos, panel, 3);
                            ((SmartBlockEntity)level.getBlockEntity(portPos)).loadWithComponents(pending, level.registryAccess());
                            a.reset(); a.setPaused(false);
                            await(h, computers, () -> {
                                h.assertValueEqual(reply(a).field("phase"), new DataValue.TextValue("complete"), "Native delivery survives absent host and host reconstruction");
                                h.assertValueEqual(reply(a).field("milestone"), new DataValue.TextValue("materials_delivered"), "Delivery is not recipe production completion");
                                h.assertValueEqual(chest.getItem(0).getCount(), 60, "Polling delivered order does not redispatch");
                                computers.forEach(computer -> computer.setPaused(true));
                                h.succeed();
                            });
                        });
                    });
                });
            });
        });
    }
    private static DataValue.RecordValue reply(ComputerBehaviour computer) {
        return (DataValue.RecordValue)computer.flowSnapshot().orElseThrow().state().locals().field("reply");
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
    private static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var state = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, 3); level.setBlock(pos.above(), state.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var resident = EntityType.VILLAGER.create(level);
        resident.setBaby(false); resident.setVillagerData(resident.getVillagerData().setProfession(VillagerProfession.CLERIC).setLevel(5));
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Captured supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "production-install")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked resident installation");
        return block.getBehaviour(ComputerBehaviour.TYPE);
    }
    private static ProgramDocument load(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> resources = key ->
            ProductionDriverGameTests.class.getClassLoader().getResourceAsStream("assets/" + key.getNamespace() + "/" + key.getPath());
        var example = ProgramExampleCatalog.load(resources).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(example, resources, new ArchiveLimits(10000, 100000, 1000000));
    }
    private static ProgramDocument inputs(ProgramDocument document, DataValue.RecordValue initial) {
        var body = document.flow().orElseThrow(); var draft = new ProgramWorkspace(document);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), initial, body.canvas(), body.inline(), body.execution())));
        return draft.snapshot();
    }
    private static DataValue.RecordValue record(Object... pairs) {
        var values = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String)pairs[i], pairs[i + 1] instanceof DataValue value ? value :
            pairs[i + 1] instanceof String text ? new DataValue.TextValue(text) : new DataValue.IntegerValue(((Number)pairs[i + 1]).longValue()));
        return new DataValue.RecordValue(values);
    }
}
