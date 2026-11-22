package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.level.NoteBlockEvent;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Transport/physical authorization integration; not a GUI or published driver acceptance. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class MusicOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void authorizedSerialPlaysSelectedInstrumentAndCannotReplayAfterReload(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos.east(), Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        var original = level.getBlockState(pos.east());
        var sound = new SoundWitness(h, pos.east());
        level.setBlock(pos, CBBlocks.MUSIC_OUTPUT.get().defaultBlockState().setValue(MusicOutputBlock.FACING, Direction.EAST), 3);
        var output = (MusicOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(MusicOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Closed until an explicit menu grant");
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), net.minecraft.world.InteractionResult.CONSUME, "Registered physical entry");
            var grant = actor.client.request(MusicOutputRequest.Action.GRANT, 0);
            new MusicOutputRequest(grant.containerId(), UUID.randomUUID(), grant.revision(), grant.action(), grant.choice()).handle(actor);
            h.assertTrue(service.binding().isEmpty(), "Wrong nonce rejected"); actor.send(grant);
            h.assertValueEqual(actor.client.code(), "configured", "Real packet grants discovered source");
            var address = service.address();
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), status -> {
                var args = new LinkedHashMap<>(((DataValue.RecordValue)status.field("state")).fields());
                args.keySet().retainAll(Set.of("device", "session", "revision"));
                args.put("instrument", text("flute")); args.put("note", integer(19));
                var play = command(address, "first", "play", args);
                exchange(h, computer, play, accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Accepted is not a sound-completion claim");
                    exchange(h, computer, command(address, "result", "status", Map.of()), result -> {
                        h.assertValueEqual(((DataValue.RecordValue)result.field("state")).field("last"), text("played"), "Physical note completed independently");
                        h.assertValueEqual(level.getBlockState(pos.east()), original, "Instrument and pitch override do not mutate the note block");
                        h.assertValueEqual(sound.count, 1, "Exactly one server sound emitted");
                        h.assertValueEqual(sound.last.getSound(), NoteBlockInstrument.FLUTE.getSoundEvent(), "Real flute sound, not the block's harp");
                        h.assertValueEqual(sound.last.getOriginalPitch(), NoteBlock.getPitchFromNote(19), "Real requested pitch");
                        exchange(h, computer, play, duplicate -> {
                            h.assertValueEqual(duplicate.field("code"), text("changed"), "Old revision cannot replay");
                            h.assertValueEqual(sound.count, 1, "Duplicate emitted nothing");
                            checkCancelledAndReplaced(h, computer, output, service, sound,
                                (DataValue.RecordValue)result.field("state"));
                        });
                    });
                });
            }));
        });
    }
    @GameTest(template = "empty", batch = "public_music_driver", timeoutTicks = 1200)
    public static void publishedMusicProgramPlaysThroughExplicitClusterAndPhysicalAdapter(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1, 1, 1));
        var max = min.offset(5, 4, 4);
        var gate = min.offset(5, 1, 2);
        var aPos = min.offset(1, 1, 1);
        var bPos = gate.west();
        var a = computer(h, aPos, true);
        var b = computer(h, bPos, true);
        for (var p : BlockPos.betweenClosed(min, max))
            if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY()
                || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(gate, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gate);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(gate.east()));
        var pos = gate.east(3);
        var target = pos.east();
        for (int i = 1; i < 3; i++) level.setBlock(gate.east(i), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(target, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.MUSIC_OUTPUT.get().defaultBlockState().setValue(MusicOutputBlock.FACING, Direction.EAST), 3);
        var output = (MusicOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(MusicOutputSerialBehaviour.TYPE);
        var sound = new SoundWitness(h, target);

        var draft = new ProgramWorkspace(example("music_output_a"));
        var flow = draft.flow().orElseThrow();
        var initial = new LinkedHashMap<>(flow.initial().fields());
        initial.put("target", service.address()); initial.put("instrument", text("chime")); initial.put("note", integer(8));
        draft.setFlow(Optional.of(new FlowDocumentBody(flow.inputs(), flow.steps(), new DataValue.RecordValue(initial),
            flow.canvas(), flow.inline(), flow.execution(), flow.compilation())));
        a.installDocument(draft.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        b.installDocument(example("serial_exchange"), ComputerRuntime.MemoryPolicy.RESET);
        a.configureEndpoint(new EndpointConfiguration("a", true));
        b.configureEndpoint(new EndpointConfiguration("b", true));
        var members = Map.of("a", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), aPos),
            "b", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), bPos));
        var link = new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true);
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("ab", link)), actor),
            "configured", "Explicit XBus wire in closed Cluster");
        var block = (ComputerBlockEntity)level.getBlockEntity(bPos);
        b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, block), actor, "s0", "music")));
        b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));

        h.runAfterDelay(8, () -> {
            actor.moveTo(Vec3.atCenterOf(pos));
            h.assertTrue(MusicOutputMenu.open(actor, output, "ok"), "Player can bind the exposed gateway");
            actor.send(actor.client.request(MusicOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Explicit player grant");
            b.setPaused(false);
            h.runAfterDelay(4, () -> {
                a.setPaused(false);
                h.startSequence().thenWaitUntil(() -> {
                    var state = a.flowSnapshot().orElseThrow().state();
                    h.assertValueEqual(state.status(), FlowRuntime.Status.COMPLETE, "Installed public driver returns: " + state.diagnostic());
                }).thenExecute(() -> {
                    var reply = (DataValue.RecordValue)a.flowSnapshot().orElseThrow().state().locals().field("reply");
                    h.assertValueEqual(reply.field("code"), text("accepted"), "Visible admission result");
                    h.assertValueEqual(output.state().last(), "played", "Physical adapter emitted the note");
                    h.assertValueEqual(sound.count, 1, "Exactly one note from the public program");
                    h.assertValueEqual(sound.last.getSound(), NoteBlockInstrument.CHIME.getSoundEvent(), "Selected chime");
                    h.assertValueEqual(sound.last.getOriginalPitch(), NoteBlock.getPitchFromNote(8), "Selected pitch");
                }).thenSucceed();
            });
        });
    }

    @GameTest(template = "empty", batch = "scratch_music_driver", timeoutTicks = 1200)
    public static void scratchNoteExtensionUsesPhysicalSerialAndEmitsOneSelectedNote(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin, true);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos.east(), Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        var original = level.getBlockState(pos.east());
        var sound = new SoundWitness(h, pos.east());
        level.setBlock(pos, CBBlocks.MUSIC_OUTPUT.get().defaultBlockState().setValue(MusicOutputBlock.FACING, Direction.EAST), 3);
        var output = (MusicOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(MusicOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        computer.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        h.runAfterDelay(8, () -> {
            h.assertTrue(MusicOutputMenu.open(actor, output, "ok"), "Actual adapter entry is available");
            actor.send(actor.client.request(MusicOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Explicit grant of discovered Computer");
            computer.installDocument(scratchNote("status", service.address().fields()), ComputerRuntime.MemoryPolicy.RESET);
            h.assertValueEqual(sound.count, 0, "Installing a public block does not play");
            computer.setPaused(false);
            h.startSequence().thenWaitUntil(() -> scratchReplyReady(h, computer)).thenExecute(() -> {
                var status = scratchReply(h, computer);
                h.assertValueEqual(status.field("code"), text("ok"), "Real serial query returns current token");
                var inputs = new LinkedHashMap<>(service.address().fields());
                var token = (DataValue.RecordValue)status.field("state");
                for (var name : List.of("device", "session", "revision")) inputs.put(name, token.field(name));
                inputs.put("instrument", text("flute")); inputs.put("note", integer(19));
                computer.installDocument(scratchNote("play", inputs), ComputerRuntime.MemoryPolicy.RESET);
                h.assertValueEqual(sound.count, 0, "Replacement installation is also silent");
                computer.setPaused(false);
            }).thenWaitUntil(() -> scratchReplyReady(h, computer)).thenExecute(() -> {
                h.assertValueEqual(scratchReply(h, computer).field("code"), text("accepted"), "Public ASM request reached physical service");
                h.assertValueEqual(output.state().last(), "played", "Physical slot completed separately");
                h.assertValueEqual(sound.count, 1, "Exactly one sound");
                h.assertValueEqual(sound.last.getSound(), NoteBlockInstrument.FLUTE.getSoundEvent(), "Requested instrument");
                h.assertValueEqual(sound.last.getOriginalPitch(), NoteBlock.getPitchFromNote(19), "Requested pitch");
                h.assertValueEqual(level.getBlockState(pos.east()), original, "No note block state rewrite");
            }).thenIdle(20).thenExecute(() -> h.assertValueEqual(sound.count, 1, "Completed public block does not retry")).thenSucceed();
        });
    }

    private static ProgramDocument scratchNote(String operation, Map<String, DataValue> inputs) {
        var empty = FlowDocumentBody.emptySoftwareAssembly();
        var draft = new FlowDraft(new FlowDocumentBody(empty.inputs(), empty.steps(), empty.initial(),
            new FlowBlockLayout(empty.canvas().main(), empty.canvas().detached(), empty.canvas().values(),
                Map.of("create_biotech:note_output", 1L)), empty.inline(), empty.execution(), empty.compilation()));
        var key = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key(
            "cb_extension:create_biotech:note_output:" + operation, 1);
        var binding = com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(key).orElseThrow();
        var source = draft.placeTemplate(binding.template("note_driver"), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(16, 16));
        var graph = draft.snapshot().inline().get(source);
        var nodes = new LinkedHashMap<>(graph.nodes());
        inputs.forEach((name, value) -> nodes.put(Objects.requireNonNull(graph.outputs().get(name)),
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Constant(value)));
        draft.editInline(source, new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph(
            graph.inputs(), nodes, graph.outputs()), new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(Map.of(), Map.of()));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        return workspace.snapshot();
    }

    private static void scratchReplyReady(GameTestHelper h, ComputerBehaviour computer) {
        var state = computer.flowSnapshot().orElseThrow().state();
        h.assertValueEqual(state.status(), FlowRuntime.Status.COMPLETE, "Scratch ISA completed: " + state.diagnostic());
        h.assertValueEqual(computer.serialDevice().status("s0").receive().frames(), 1, "Exactly one physical reply");
    }

    private static DataValue.RecordValue scratchReply(GameTestHelper h, ComputerBehaviour computer) {
        var receive = computer.serialDevice().prepareReceive("s0");
        var reply = (DataValue.RecordValue)receive.result().field("value");
        h.assertTrue(receive.publish(), "Consume one observed reply");
        h.assertValueEqual(reply.field("kind"), text("music_output_reply"), "Existing protocol");
        return reply;
    }

    private static ProgramDocument example(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open =
            location -> MusicOutputSerialGameTests.class.getResourceAsStream("/assets/" + location.getNamespace() + "/" + location.getPath());
        var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, open, CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
    }

    private static void checkCancelledAndReplaced(GameTestHelper h, ComputerBehaviour computer, MusicOutputBlockEntity output,
            MusicOutputSerialBehaviour service, SoundWitness sound, DataValue.RecordValue status) {
        var level = h.getLevel();
        var address = service.address();
        var arguments = new LinkedHashMap<>(status.fields());
        arguments.keySet().retainAll(Set.of("device", "session", "revision"));
        arguments.put("instrument", text("bass")); arguments.put("note", integer(5));
        sound.cancelNote = true;
        exchange(h, computer, command(address, "cancel", "play", arguments), accepted -> {
            h.assertValueEqual(accepted.field("code"), text("accepted"), "World cancellation is separate from admission");
            h.assertValueEqual(output.state().last(), "cancelled", "NeoForge NoteBlockEvent can prevent sound");
            h.assertValueEqual(sound.count, 1, "Cancelled action emitted nothing");
            sound.cancelNote = false;
            arguments.put("revision", integer(output.state().revision()));
            var target = output.target();
            level.setBlock(target.above(), Blocks.STONE.defaultBlockState(), 3);
            exchange(h, computer, command(address, "blocked", "play", arguments), blocked -> {
                h.assertValueEqual(blocked.field("code"), text("accepted"), "Obstruction checked at execution");
                h.assertValueEqual(output.state().last(), "obstructed", "Selected bass obeys the above-block restriction");
                h.assertValueEqual(sound.count, 1, "Obstructed action emitted nothing");
                arguments.put("revision", integer(output.state().revision()));
                level.removeBlock(target.above(), false);
                level.removeBlock(target, false);
                level.setBlock(target, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
                exchange(h, computer, command(address, "replaced", "play", arguments), stale -> {
                    h.assertValueEqual(stale.field("code"), text("stale_session"), "Same-state target replacement fences old commands");
                    h.assertValueEqual(sound.count, 1, "Replacement did not inherit queued effects");
                    var pos = output.getBlockPos();
                    var saved = output.saveWithFullMetadata(level.registryAccess());
                    var oldSession = output.state().session();
                    level.removeBlockEntity(pos);
                    var restored = new MusicOutputBlockEntity(output.getType(), pos, output.getBlockState());
                    restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored); restored.initialize();
                    h.assertTrue(restored.state().pending().isEmpty(), "No replay on load");
                    h.assertValueEqual(restored.state().last(), "obstructed", "Last result retained");
                    h.assertTrue(!restored.state().session().equals(oldSession), "Old session fenced");
                    h.runAfterDelay(3, () -> {
                        h.assertValueEqual(sound.count, 1, "Reload did not emit another sound");
                        h.succeed();
                    });
                });
            });
        });
    }

    private static final class SoundWitness implements GameTestListener {
        final Consumer<NoteBlockEvent.Play> note;
        final Consumer<PlayLevelSoundEvent.AtPosition> sound;
        int count;
        boolean cancelNote;
        PlayLevelSoundEvent.AtPosition last;
        SoundWitness(GameTestHelper h, BlockPos target) {
            note = event -> {
                if (cancelNote && event.getLevel() == h.getLevel() && event.getPos().equals(target)) event.setCanceled(true);
            };
            sound = event -> {
                if (event.getLevel() == h.getLevel() && event.getPosition().equals(Vec3.atCenterOf(target))) {
                    count++; last = event;
                }
            };
            NeoForge.EVENT_BUS.addListener(note);
            NeoForge.EVENT_BUS.addListener(sound);
            h.testInfo.addListener(this);
        }
        private void remove() { NeoForge.EVENT_BUS.unregister(note); NeoForge.EVENT_BUS.unregister(sound); }
        @Override public void testStructureLoaded(GameTestInfo info) {}
        @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { remove(); }
        @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { remove(); }
        @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) { remove(); }
    }

    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) { return computer(h, pos, false); }
    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos, boolean masterCleric) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3); level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        if (masterCleric) villager.setVillagerData(new net.minecraft.world.entity.npc.VillagerData(
            net.minecraft.world.entity.npc.VillagerType.PLAINS, net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5));
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "redstone-computer")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Normal checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); return computer;
    }
    static DataValue.RecordValue command(DataValue.RecordValue address, String request, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("music_output_command"), "version", integer(1), "target", address,
            "request", text(request), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }
    static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue request, Consumer<DataValue.RecordValue> finish) {
        h.assertValueEqual(computer.serialDevice().send("s0", request), "queued", "No instantaneous device shortcut");
        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "Reply consumes wire time");
        awaitReply(h, computer, request.field("request"), finish);
    }
    private static void awaitReply(GameTestHelper h, ComputerBehaviour computer, DataValue correlation, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { awaitReply(h, computer, correlation, finish); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), correlation, "Explicit matching reply"); finish.accept(reply.orElseThrow());
        });
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }

    private static final class MenuPlayer extends FakePlayer {
        MusicOutputMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "redstone-control")); }
        @Override public OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.MUSIC_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(MusicOutputRequest packet) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new MusicOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
