package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Actual catalog -> independent hardware -> internal XBus -> exposed serial -> native Display Link. No Java command orchestration. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class TextDriverGameTests {
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicDriversUpdateNativeBoardThroughExplicitClusterGateway(GameTestHelper h) {
        var level = h.getLevel(); var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(6, 3, 4);
        var controllerPos = min.offset(6, 1, 2); var outputPos = controllerPos.east(4);
        var computers = new ArrayList<ComputerBehaviour>(); var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        for (int n = 0; n < 3; n++) {
            var role = String.valueOf((char)('a' + n));
            var computer = computer(h, min.offset(1 + n * 2, 1, 1), VillagerProfession.CLERIC);
            var document = load(role);
            if (role.equals("a")) {
                var body = document.flow().orElseThrow();
                var workspace = new ProgramWorkspace(document);
                workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(),
                    record("target", record("x", outputPos.getX(), "y", outputPos.getY(), "z", outputPos.getZ()), "lines", new DataValue.ListValue(List.of(new DataValue.TextValue("READY")))),
                    body.canvas(), body.inline(), body.execution())));
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
        var controller = (ClusterControllerBlockEntity) level.getBlockEntity(controllerPos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "text-output-driver")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of(
            "ab", new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true),
            "bc", new XBusWire(new XBusWire.Pin("b", "b", "x1"), new XBusWire.Pin("c", "c", "x0"), true))), actor), "configured", "Explicit two internal wires");
        var b = computers.get(1); var exposed = SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, b.blockEntity), actor, "s0", "output");
        b.configureSerialGateway("s0", Optional.of(exposed)); b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        computers.get(2).configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true)); // Not exposed, therefore not an external authority.
        for (int x = 1; x < 4; x++) level.setBlock(controllerPos.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(outputPos, CBBlocks.TEXT_OUTPUT.get().defaultBlockState().setValue(TextOutputBlock.FACING, Direction.EAST), 3);
        var boardPos = outputPos.east(2);
        level.setBlock(boardPos, com.simibubi.create.AllBlocks.DISPLAY_BOARD.getDefaultState(), 3);
        var board = (com.simibubi.create.content.trains.display.FlapDisplayBlockEntity)level.getBlockEntity(boardPos);
        board.updateControllerStatus();
        level.setBlock(outputPos.above(), com.simibubi.create.AllBlocks.DISPLAY_LINK.getDefaultState()
            .setValue(com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock.FACING, Direction.UP), 3);
        var link = (com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity)level.getBlockEntity(outputPos.above());
        link.initialize(); link.target(boardPos); link.activeSource = CBDisplaySources.TEXT_OUTPUT.get();
        var output = (TextOutputBlockEntity) level.getBlockEntity(outputPos);
        var control = output.getBehaviour(TextOutputSerialBehaviour.TYPE);
        var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        var documents = computers.stream().map(c -> c.flowSnapshot().orElseThrow().program()).toList();
        h.runAfterDelay(4, () -> {
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(outputPos));
            var choices = control.discover(actor);
            h.assertValueEqual(choices.size(), 1, "Only the explicitly exposed member is discoverable");
            h.assertValueEqual(choices.getFirst().member(), b.blockEntity.getBlockPos(), "No automatic bridge to the other open member");
            h.assertTrue(choices.getFirst().gateway().isPresent(), "Real concentrated Cluster source proof");
            h.assertValueEqual(control.configure(actor, control.revision(), Optional.of(choices.getFirst())), "configured", "Player configuration authorizes the exposed B interface");
            h.runAfterDelay(3, () -> {
                for (int n : List.of(2, 1, 0)) computers.get(n).setPaused(false);
                await(h, computers, () -> {
                    var locals = computers.getFirst().flowSnapshot().orElseThrow().state().locals();
                    h.assertValueEqual(locals.field("ok"), new DataValue.BooleanValue(true), "Public program reports success");
                    h.assertValueEqual(((DataValue.RecordValue) locals.field("reply")).field("code"), new DataValue.TextValue("accepted"), "Matched business reply, not send completion");
                    h.assertValueEqual(output.state().lines(), List.of("READY"), "Public program serial command accepted");
                    h.assertValueEqual(output.state().revision(), 2L, "One grant and exactly one replacement");
                    TextOutputGameTests.powerBoard(level, board); link.tickSource();
                    h.assertValueEqual(board.getLines().getFirst().getSections().getFirst().getText().getString(), "READY", "Native display link transfers the program output");
                    h.assertTrue(controller.enclosure().enclosure().isPresent(), "Sealed cuboid with interior air remains formed");
                    for (int n = 0; n < 3; n++) {
                        h.assertValueEqual(computers.get(n).hardware(), hardware.get(n), "No hardware replacement or pooled resources");
                        h.assertValueEqual(computers.get(n).flowSnapshot().orElseThrow().program(), documents.get(n), "No hidden program replacement");
                        h.assertTrue(computers.get(n).flowSnapshot().orElseThrow().state().assembly().isPresent(), "ISA is the executing backend");
                        computers.get(n).setPaused(true);
                    }
                    h.runAfterDelay(6, () -> {
                        h.assertValueEqual(output.state().lines(), List.of("READY"), "Pausing all members retains text");
                        h.assertValueEqual(control.configure(actor, control.revision(), Optional.empty()), "configured", "Revoke remains explicit");
                        h.assertValueEqual(output.state().lines(), List.of("READY"), "Revocation retains accepted text");
                        h.succeed();
                    });
                });
            });
        });
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
    private static ComputerBehaviour computer(GameTestHelper h, BlockPos pos, VillagerProfession profession) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var state = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, 3); level.setBlock(pos.above(), state.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity) level.getBlockEntity(pos); var resident = EntityType.VILLAGER.create(level);
        resident.setBaby(false); resident.setVillagerData(resident.getVillagerData().setProfession(profession).setLevel(5));
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Captured supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "redstone-install")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked resident installation");
        return block.getBehaviour(ComputerBehaviour.TYPE);
    }
    private static ProgramDocument load(String role) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> resources = id ->
            TextDriverGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var example = ProgramExampleCatalog.load(resources).stream().filter(e -> e.id().equals("text_output_" + role)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(example, resources, new ArchiveLimits(10000, 100000, 1000000));
    }
    private static DataValue.RecordValue record(Object... pairs) {
        var values = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1] instanceof DataValue value ? value :
            new DataValue.IntegerValue(((Number) pairs[i + 1]).longValue()));
        return new DataValue.RecordValue(values);
    }
}
