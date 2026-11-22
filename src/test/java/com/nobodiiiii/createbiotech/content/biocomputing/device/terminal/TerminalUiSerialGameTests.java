package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest.Action;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class TerminalUiSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 4500)
    public static void wiredComputerPublishesLayoutsAndReceivesPlayerActions(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var source = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.TERMINAL_UI.get().defaultBlockState().setValue(TerminalUiBlock.FACING, Direction.EAST), 3);
        var terminal = (TerminalUiBlockEntity)level.getBlockEntity(pos);
        var service = terminal.getBehaviour(TerminalUiSerialBehaviour.TYPE);
        var actor = new Viewer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        var target = service.address();
        h.runAfterDelay(5, () -> {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.EAST, pos, false);
            h.assertValueEqual(terminal.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Right click opens the device");
            h.assertTrue(service.binding().isEmpty(), "Wiring does not grant publication");
            actor.send(Action.GRANT, 0, "", "");
            h.assertValueEqual(actor.client.code(), "access_denied", "Viewer cannot grant a source");
            actor.send(Action.MANAGE, -1, "", "");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1,
                "Actual cable source offered (" + actor.client.code() + ")");
            actor.send(Action.GRANT, 0, "", "");
            h.assertValueEqual(actor.client.code(), "configured", "Explicitly authorize the captured source");
            actor.send(Action.VIEW, -1, "", "");
            h.runAfterDelay(3, () -> exchange(h, source, target, "status", Map.of(), ready -> {
                var button = new TerminalDefinition("open", Map.of("open", new TerminalDefinition.Button(
                    literal(text("Open form")), literal(new DataValue.BooleanValue(true)), "open", literal(record(Map.of())))));
                exchange(h, source, target, "publish", publication(ready, button), first -> {
                    h.assertValueEqual(first.field("code"), text("published"), "Physical publish succeeds");
                    actor.send(Action.REFRESH, -1, "", "");
                    var old = actor.client.request(Action.CLICK, -1, projected(actor).instance(), "");
                    actor.send(old);
                    h.assertValueEqual(actor.client.code(), "queued", "Visible button enters the queue");
                    exchange(h, source, target, "take_event", claim(first, 1), click -> {
                        h.assertValueEqual(event(click).field("name"), text("open"), "Click returned over the cable");
                        var input = new TerminalDefinition("query", Map.of("query", new TerminalDefinition.Input(
                            literal(text("")), literal(new DataValue.BooleanValue(true)), "submit", "answer", literal(record(Map.of())))));
                        exchange(h, source, target, "publish", publication(click, input), second -> {
                            h.assertValueEqual(second.field("code"), text("published"), "Replaces layout without installing a program");
                            actor.send(Action.CLICK, -1, "4:open", "");
                            h.assertValueEqual(actor.client.code(), "stale_view", "Stale menu cannot act on the changed layout");
                            actor.send(Action.SUBMIT, -1, projected(actor).instance(), "hello");
                            h.assertValueEqual(actor.client.code(), "queued", "Current input accepted");
                            exchange(h, source, target, "take_event", claim(second, 2), answer -> {
                                h.assertValueEqual(event(answer).field("values"), record(Map.of("answer", text("hello"))), "Player input returned");
                                exchange(h, source, target, "take_event", claim(second, 2), replay -> {
                                    h.assertValueEqual(replay.field("code"), text("no_events"), "Claim cannot be replayed");
                                    var archive = terminal.saveWithFullMetadata(level.registryAccess());
                                    var clientTag = terminal.getUpdateTag(level.registryAccess());
                                    h.assertTrue(!clientTag.contains("BiocomputingTerminalUi") && !clientTag.contains("BiocomputingTerminalUiSerial"),
                                        "No private state in block sync");
                                    var blockState = terminal.getBlockState();
                                    level.removeBlockEntity(pos);
                                    var restored = new TerminalUiBlockEntity(terminal.getType(), pos, blockState);
                                    restored.loadWithComponents(archive, level.registryAccess());
                                    level.setBlockEntity(restored);
                                    restored.initialize();
                                    h.assertValueEqual(restored.state().page(), terminal.state().page(), "Published layout survives loading");
                                    h.assertTrue(restored.state().events().isEmpty(), "Nothing is replayed after load");
                                    h.runAfterDelay(3, () -> exchange(h, source, target, "take_event", claim(second, 2), oldClaim -> {
                                        h.assertValueEqual(oldClaim.field("code"), text("stale_session"), "Reload fences old claims");
                                        h.succeed();
                                    }));
                                });
                            });
                        });
                    });
                });
            }));
        });
    }

    private static TerminalProjection.View projected(Viewer actor) {
        var page = (DataValue.RecordValue)actor.client.view().field("page");
        return TerminalProjection.project(TerminalCodec.decode(page.field("layout")), (DataValue.RecordValue)page.field("roots"), Map.of(), 4, 16);
    }
    private static TerminalBinding.Literal literal(DataValue value) { return new TerminalBinding.Literal(value); }
    private static DataValue.RecordValue event(DataValue.RecordValue reply) { return (DataValue.RecordValue)reply.field("event"); }
    private static LinkedHashMap<String, DataValue> token(DataValue.RecordValue reply) {
        return new LinkedHashMap<>(Map.of("state", reply.field("state")));
    }
    private static Map<String, DataValue> publication(DataValue.RecordValue reply, TerminalDefinition layout) {
        var args = token(reply);
        args.put("layout", TerminalCodec.encode(layout));
        args.put("roots", record(Map.of()));
        return args;
    }
    private static Map<String, DataValue> claim(DataValue.RecordValue reply, int sequence) {
        var args = token(reply);
        args.put("sequence", number(sequence));
        return args;
    }
    private static void exchange(GameTestHelper h, ComputerBehaviour source, DataValue.RecordValue target, String operation,
            Map<String, DataValue> args, Consumer<DataValue.RecordValue> finish) {
        var id = text(UUID.randomUUID().toString());
        var request = record(Map.of("kind", text("terminal_ui_command"), "version", number(1), "target", target,
            "request", id, "operation", text(operation), "arguments", record(args)));
        h.assertValueEqual(source.serialDevice().send("s0", request), "queued", "Physical frame enqueued");
        await(h, source, target, operation, id, 550, finish);
    }
    private static void await(GameTestHelper h, ComputerBehaviour source, DataValue.RecordValue target,
            String operation, DataValue id, int remaining, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = source.serialDevice().poll("s0");
            if (reply.isEmpty()) {
                var pos = new BlockPos((int)((DataValue.IntegerValue)target.field("x")).value(),
                    (int)((DataValue.IntegerValue)target.field("y")).value(), (int)((DataValue.IntegerValue)target.field("z")).value());
                var terminal = (TerminalUiBlockEntity)h.getLevel().getBlockEntity(pos);
                var service = terminal.getBehaviour(TerminalUiSerialBehaviour.TYPE);
                if (remaining == 0) h.fail("No " + operation + " reply; sender " + source.serialDevice().status("s0")
                    + ", terminal " + service.device().status("s0") + ", revision " + terminal.state().revision());
                await(h, source, target, operation, id, remaining - 1, finish);
                return;
            }
            h.assertValueEqual(reply.orElseThrow().field("request"), id, "Correlated physical reply");
            finish.accept(reply.orElseThrow());
        });
    }
    private static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3);
        level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos);
        var villager = EntityType.VILLAGER.create(level);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Resident captured");
        var installer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "terminal-maker"));
        installer.moveTo(Vec3.atCenterOf(pos));
        installer.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(installer, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Resident installed");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), record(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        computer.serialDevice().configure("s0", Direction.EAST, true);
        return computer;
    }
    private static final class Viewer extends FakePlayer {
        private TerminalUiMenu client;
        private int id;
        Viewer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "terminal-user")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            int next = ++id;
            containerMenu = provider.createMenu(next, getInventory(), this);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try { opening.accept(wire); client = CBMenuTypes.TERMINAL_UI.get().create(next, getInventory(), wire); }
            finally { wire.release(); }
            return OptionalInt.of(next);
        }
        void send(Action action, int choice, String instance, String value) { send(client.request(action, choice, instance, value)); }
        void send(TerminalUiRequest packet) {
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try { packet.write(wire); new TerminalUiRequest(wire).handle(this); }
            finally { wire.release(); }
        }
    }
}
