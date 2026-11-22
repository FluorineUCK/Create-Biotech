package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class NarratorOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void realCableAdmitsOneUtteranceAndFencesOldSessionOnReload(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.NARRATOR_OUTPUT.get().defaultBlockState().setValue(LocalTextOutputBlock.FACING, Direction.EAST), 3);
        var output = (LocalTextOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(LocalTextOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);

        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Cabling does not grant authority");
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Registered manager opens");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "A real cabled pin is offered");
            actor.send(actor.client.request(LocalTextOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Menu grants the captured source");
            var address = service.address();
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), reply -> {
                var args = speechArguments(reply);
                exchange(h, computer, command(address, "say", "speak", args), accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Physical request admitted");
                    h.assertValueEqual(output.state().last(), "no_listeners", "Admission never implies a listener");
                    h.assertTrue(!output.state().pending(), "Utterance is settled, not retained");
                    var sync = output.getUpdateTag(level.registryAccess());
                    h.assertTrue(!sync.contains("BiocomputingNarratorOutput") && !sync.contains("BiocomputingNarratorSerial"),
                        "Private archives are not sent to clients");

                    var archive = output.saveWithFullMetadata(level.registryAccess());
                    h.assertTrue(archive.contains("BiocomputingNarratorSerial") && !archive.toString().contains("Ready at station"),
                        "The persisted result contains no replayable speech");
                    var oldSession = output.state().session();
                    var blockState = output.getBlockState();
                    level.removeBlockEntity(pos);
                    var restored = new LocalTextOutputBlockEntity(output.getType(), pos, blockState);
                    restored.loadWithComponents(archive, level.registryAccess());
                    level.setBlockEntity(restored);
                    restored.initialize();
                    h.assertTrue(!restored.state().session().equals(oldSession), "Recovery renews the session");
                    h.assertValueEqual(restored.state().last(), "no_listeners", "Recovery retains only the outcome");
                    h.runAfterDelay(3, () -> exchange(h, computer, command(address, "old", "speak", args), stale -> {
                        h.assertValueEqual(stale.field("code"), text("stale_session"), "Old frame cannot speak after restore");
                        h.assertTrue(!restored.state().pending(), "Nothing was re-enqueued");
                        h.succeed();
                    }));
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void chatUsesVanillaHistoryOnlyForNearbyPlayersAndNeverReplays(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.CHAT_OUTPUT.get().defaultBlockState().setValue(LocalTextOutputBlock.FACING, Direction.EAST), 3);
        var output = (LocalTextOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(LocalTextOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Cabling alone never authorizes chat");
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Player can open the chat adapter");
            h.assertValueEqual(actor.client.view().field("outputKind"), text("chat"), "Chat menu is not the narration mode");
            actor.send(actor.client.request(LocalTextOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Menu grants the actual cabled source");
            var address = service.address();
            var near = new ChatListener(level, pos.east(3));
            var far = new ChatListener(level, pos.east(32));
            h.runAfterDelay(3, () -> exchange(h, computer, chat(address, "query", "status", Map.of()), reply -> {
                var args = speechArguments(reply);
                exchange(h, computer, chat(address, "send", "send", args), accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Finite serial exchange accepted");
                    h.assertValueEqual(output.state().last(), "dispatched", "At least one nearby player dispatched");
                    var messages = near.take();
                    h.assertValueEqual(messages.size(), 1, "Nearby player receives exactly one message without narration opt-in");
                    h.assertTrue(!messages.getFirst().overlay(), "Native system chat belongs to chat history");
                    var content = (TranslatableContents)messages.getFirst().content().getContents();
                    h.assertValueEqual(((Component)content.getArgs()[3]).getString(), "Ready at station", "Literal body");
                    h.assertValueEqual(far.take().size(), 0, "Outside configured distance receives no chat");
                    var archive = output.saveWithFullMetadata(level.registryAccess());
                    h.assertTrue(archive.contains("BiocomputingChatSerial") && !archive.toString().contains("Ready at station"),
                        "Chat result has its own archive and does not retain the body for replay");
                    var state = output.getBlockState();
                    level.removeBlockEntity(pos);
                    var restored = new LocalTextOutputBlockEntity(output.getType(), pos, state);
                    restored.loadWithComponents(archive, level.registryAccess());
                    level.setBlockEntity(restored);
                    restored.initialize();
                    h.runAfterDelay(3, () -> exchange(h, computer, chat(address, "old", "send", args), stale -> {
                        try {
                            h.assertValueEqual(stale.field("code"), text("stale_session"), "Reload fences the old request");
                            h.assertTrue(near.take().isEmpty() && far.take().isEmpty(), "Neither reload nor stale request replays chat");
                        } finally {
                            near.close();
                            far.close();
                        }
                        h.succeed();
                    }));
                });
            }));
        });
    }

    private static DataValue.RecordValue chat(DataValue.RecordValue address, String id, String operation, Map<String, DataValue> arguments) {
        var frame = new LinkedHashMap<String, DataValue>(command(address, id, operation, arguments).fields());
        frame.put("kind", text("chat_output_command"));
        return new DataValue.RecordValue(frame);
    }

    private static Map<String, DataValue> speechArguments(DataValue.RecordValue reply) {
        var token = (DataValue.RecordValue)reply.field("state");
        return Map.of("device", token.field("device"), "session", token.field("session"),
            "revision", token.field("revision"), "text", text("Ready at station"));
    }

    private static DataValue.RecordValue command(DataValue.RecordValue address, String id, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("narrator_output_command"), "version", new DataValue.IntegerValue(1),
            "target", address, "request", text(id), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }

    private static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue frame,
            Consumer<DataValue.RecordValue> finish) {
        h.assertValueEqual(computer.serialDevice().send("s0", frame), "queued", "Transfer consumes physical wire time");
        await(h, computer, frame.field("request"), finish);
    }

    private static void await(GameTestHelper h, ComputerBehaviour computer, DataValue request, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { await(h, computer, request, finish); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), request, "Replies match correlation");
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
        var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "speech-computer"));
        actor.moveTo(Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        return computer;
    }

    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }

    private static final class ChatListener implements AutoCloseable {
        final ServerPlayer player;
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        ChatListener(ServerLevel level, BlockPos pos) {
            var profile = new GameProfile(UUID.randomUUID(), "chat-" + pos.getX());
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
            // A level player with a packet sink, not a pretend modded login in the server-wide player list.
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, PacketSendListener callback) { capture(packet); }
            };
            player.moveTo(Vec3.atCenterOf(pos));
            level.addNewPlayer(player);
        }
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("create_biotech.chat_output.message")) messages.add(chat);
        }
        List<ClientboundSystemChatPacket> take() {
            var result = List.copyOf(messages);
            messages.clear();
            return result;
        }
        @Override public void close() {
            player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }

    private static final class MenuPlayer extends FakePlayer {
        LocalTextOutputMenu client;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "speech-control")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.NARRATOR_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(LocalTextOutputRequest packet) {
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new LocalTextOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
