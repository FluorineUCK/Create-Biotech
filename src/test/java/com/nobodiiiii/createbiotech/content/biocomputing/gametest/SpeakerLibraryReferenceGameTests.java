package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.registry.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class SpeakerLibraryReferenceGameTests {
    // At the default 128 bit/tick, refusal followed by a successful upload of the
    // fixture MIDI takes over 5,400 ticks. These are test deadlines, not device
    // timeouts. Late faults that repeat a full upload need a second budget.
    private static final int MEDIA_MENU_PARTIAL_RETRY_TICKS = 7_200;
    private static final int MEDIA_MENU_FULL_RETRY_TICKS = 12_000;

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void physicalLibraryIssuesReferenceOverSerialOnlyWhileBookIsAuthorized(GameTestHelper h) {
        run(h, "MIDI", false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void midiSpeakerUsesActualSerialAndAuthorizedBookToDeliverAndStop(GameTestHelper h) {
        run(h, "MIDI", true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void sameSpeakerCanDeliverMp3FromThePhysicalLibrary(GameTestHelper h) {
        run(h, "MP3", true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void corruptMp3NeverStartsClientTransfer(GameTestHelper h) {
        run(h, "MP3", true, true);
    }

    @GameTest(template = "empty", timeoutTicks = MEDIA_MENU_PARTIAL_RETRY_TICKS)
    public static void mediaMenuRetainsFilesOnReadOnlyAndCommitsThroughCluster(GameTestHelper h) {
        withMediaMenu(h, LibraryServiceConfiguration.Access.READ_ONLY, fixture -> {
            fixture.submit(2);
            after(h, () -> fixture.editor().replied(2), () -> {
                h.assertValueEqual(fixture.editor().replies.getLast().code(), "readonly", "A healthy Cluster reaches the real read-only check");
                fixture.assertLocalOnly(h);
                expect(h, "configured", fixture.library().getBehaviour(LibraryServiceBehaviour.TYPE).configure(1,
                    new LibraryServiceConfiguration(Optional.empty(), Map.of("Tracks", LibraryServiceConfiguration.Access.READ_WRITE))));
                fixture.submit(3);
                after(h, () -> fixture.editor().replied(3), () -> fixture.assertWrittenAndFinish(h));
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = MEDIA_MENU_PARTIAL_RETRY_TICKS)
    public static void disconnectedMediaMenuDoesNotReplayAfterRestoringLibrary(GameTestHelper h) {
        withMediaMenu(h, LibraryServiceConfiguration.Access.READ_WRITE, fixture -> {
            var level = h.getLevel();
            var drive = fixture.library().getBehaviour(LibraryDriveBehaviour.TYPE);
            var port = fixture.host().serialDevice();
            fixture.submit(2);
            after(h, () -> fixture.editor().replied(2) || drive.snapshot().orElseThrow().transfer()
                .filter(t -> t.received() > 0 && t.received() < t.length() && t.commit().isEmpty()).isPresent()
                && port.status("s0").receive().frames() == 0, () -> {
                h.assertTrue(!fixture.editor().replied(2), "Cut the actual cable only after Library admitted part of the file");
                level.setBlockAndUpdate(fixture.cable(), Blocks.AIR.defaultBlockState());
                after(h, () -> fixture.editor().replied(2), () -> {
                    h.assertValueEqual(fixture.editor().replies.getLast().code(), "disconnected", "Cable loss reports a failure, not storage");
                    fixture.assertLocalOnly(h);
                    var interrupted = drive.snapshot().orElseThrow();
                    var transfer = interrupted.transfer().orElseThrow();
                    h.assertTrue(transfer.commit().isEmpty() && transfer.received() < transfer.length(), "No commit admitted before disconnect");
                    var restored = (LibraryBlockEntity) AuthoredLibraryWorldFixture.reconstruct(level, fixture.library());
                    var restoredDrive = restored.getBehaviour(LibraryDriveBehaviour.TYPE);
                    level.setBlockAndUpdate(fixture.cable(), CBBlocks.DATA_CABLE.get().defaultBlockState());
                    h.runAfterDelay(20, () -> {
                        var current = restoredDrive.snapshot().orElseThrow();
                        var partial = current.transfer().orElseThrow();
                        h.assertValueEqual(current.sequence(), interrupted.sequence(), "Reload and reconnection do not start a new operation");
                        h.assertValueEqual(partial.received(), transfer.received(), "Reload and reconnection do not replay chunks");
                        h.assertTrue(partial.commit().isEmpty(), "Reconnection cannot commit an incomplete file");
                        fixture.assertLocalOnly(h);
                        h.assertValueEqual(fixture.editor().replies.getLast().intent(), 2L, "No success response was fabricated on reconnect");
                        var p = restored.getBlockPos();
                        var target = new LibraryTrackAddress(p.getX(), p.getY(), p.getZ()).value();
                        frame(h, port, target, 700, "write_abort", current.sequence(), DataValue.NullValue.INSTANCE, aborted -> {
                            code(h, aborted, "aborted");
                            h.assertTrue(restoredDrive.snapshot().orElseThrow().transfer().isEmpty(), "Only an explicit authorized abort clears Library staging");
                            fixture.assertLocalOnly(h);
                            fixture.submit(3);
                            after(h, () -> fixture.editor().replied(3), () -> fixture.assertWrittenAndFinish(h));
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = MEDIA_MENU_PARTIAL_RETRY_TICKS)
    public static void mediaMenuKeepsLocalFileWhenCommitReplyIsLost(GameTestHelper h) {
        withMediaMenu(h, LibraryServiceConfiguration.Access.READ_WRITE, fixture -> {
            var level = h.getLevel();
            var p = fixture.library().getBlockPos();
            var target = new LibraryTrackAddress(p.getX(), p.getY(), p.getZ()).value();
            var drive = fixture.library().getBehaviour(LibraryDriveBehaviour.TYPE);
            var port = fixture.host().serialDevice();
            fixture.submit(2);
            after(h, () -> fixture.editor().replied(2) || drive.snapshot().map(state -> state.writing().isPresent()
                || state.transfer().flatMap(LibraryWriteTransfer::commit).isPresent()).orElse(false), () -> {
                h.assertTrue(!fixture.editor().replied(2), "Cut only after the Library admitted commit and before the completed upload reply");
                level.setBlockAndUpdate(fixture.cable(), Blocks.AIR.defaultBlockState());
                after(h, () -> fixture.editor().replied(2) && drive.snapshot().flatMap(LibraryReadHead.State::result).isPresent(), () -> {
                    h.assertValueEqual(fixture.editor().replies.getLast().code(), "disconnected", "A lost receipt cannot tell the menu that the write succeeded");
                    fixture.assertStaged(h);
                    fixture.assertWritten(h);
                    var written = fixture.shelf().getItem(2).copy();
                    var completed = drive.snapshot().orElseThrow();
                    h.assertValueEqual(completed.result().orElseThrow().code(), "written", "The device completes an admitted commit without the cable");
                    level.setBlockAndUpdate(fixture.cable(), CBBlocks.DATA_CABLE.get().defaultBlockState());
                    h.runAfterDelay(20, () -> frame(h, port, target, 701, "result", completed.sequence(), DataValue.NullValue.INSTANCE, observed -> {
                        code(h, observed, "written");
                        frame(h, port, target, 702, "ack", completed.sequence(), DataValue.NullValue.INSTANCE, acknowledged -> {
                            code(h, acknowledged, "acknowledged");
                            fixture.submit(3);
                            after(h, () -> fixture.editor().replied(3), () -> {
                                h.assertValueEqual(fixture.editor().replies.getLast().code(), "entry_exists", "New requests never overwrite a successfully written entry");
                                fixture.assertStaged(h);
                                fixture.assertWritten(h);
                                h.assertValueEqual(fixture.media().stat(fixture.shelf().getItem(2), "theme"),
                                    fixture.media().stat(written, "theme"), "Recovery and refusal preserve the same book version");
                                fixture.finish(h);
                            });
                        });
                    }));
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = MEDIA_MENU_FULL_RETRY_TICKS)
    public static void mediaMenuRetainsFileWhenWriteGrantIsRevokedAfterCommitAdmission(GameTestHelper h) {
        withMediaMenu(h, LibraryServiceConfiguration.Access.READ_WRITE, fixture -> {
            var drive = fixture.library().getBehaviour(LibraryDriveBehaviour.TYPE);
            var service = fixture.library().getBehaviour(LibraryServiceBehaviour.TYPE);
            fixture.submit(2);
            after(h, () -> fixture.editor().replied(2) || drive.snapshot().flatMap(LibraryReadHead.State::writing)
                .filter(work -> !work.committing()).isPresent(), () -> {
                h.assertTrue(!fixture.editor().replied(2), "Revoke only after the Library accepted and decoded commit, before the book write");
                fixture.assertLocalOnly(h);
                var admitted = drive.snapshot().orElseThrow();
                expect(h, "configured", service.configure(1, new LibraryServiceConfiguration(Optional.empty(),
                    Map.of("Tracks", LibraryServiceConfiguration.Access.READ_ONLY))));
                after(h, () -> fixture.editor().replied(2), () -> {
                    h.assertValueEqual(fixture.editor().replies.getLast().code(), "readonly", "Completion must recheck the live grant");
                    fixture.assertLocalOnly(h);
                    h.assertValueEqual(drive.snapshot().orElseThrow().sequence(), admitted.sequence(),
                        "The refusal still belongs to the admitted operation");
                    expect(h, "configured", service.configure(2, new LibraryServiceConfiguration(Optional.empty(),
                        Map.of("Tracks", LibraryServiceConfiguration.Access.READ_WRITE))));
                    h.runAfterDelay(20, () -> {
                        fixture.assertLocalOnly(h);
                        h.assertValueEqual(drive.snapshot().orElseThrow().sequence(), admitted.sequence(),
                            "Restoring the grant alone may not retry the old request");
                        fixture.submit(3);
                        after(h, () -> fixture.editor().replied(3), () -> fixture.assertWrittenAndFinish(h));
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = MEDIA_MENU_FULL_RETRY_TICKS)
    public static void mediaMenuKeepsFileWhenPhysicalBookIsFull(GameTestHelper h) {
        withMediaMenu(h, LibraryServiceConfiguration.Access.READ_WRITE, fixture -> {
            var config = CBConfigs.SERVER.biocomputing;
            int capacity = config.libraryBookBytes(true);
            var bounded = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),
                config.current(true).archive(), capacity);
            // Fill the actual component to the configured limit, without changing global or device settings.
            ItemStack full = fixture.book();
            for (int low = 1, high = capacity; low <= high; ) {
                int count = (low + high) / 2;
                try {
                    full = bounded.put(fixture.book(), "filler", new LibraryEntry(LibraryEntry.Kind.DATA, text("x".repeat(count))));
                    low = count + 1;
                } catch (ArchiveException failure) {
                    h.assertValueEqual(failure.code(), ArchiveException.Code.LIMIT_EXCEEDED, "Fixture hits a capacity boundary");
                    high = count - 1;
                }
            }
            h.assertTrue(bounded.read(full).containsKey("filler"), "Valid existing data occupies the real book budget");
            final var before = full;
            fixture.shelf().setItem(2, before.copy());
            fixture.submit(2);
            after(h, () -> fixture.editor().replied(2), () -> {
                h.assertValueEqual(fixture.editor().replies.getLast().code(), "archive_limit", "Book capacity is an explicit device failure");
                fixture.assertStaged(h);
                h.assertTrue(ItemStack.matches(fixture.shelf().getItem(2), before), "No partial edit or receipt changes the full book");
                // An explicit local edit frees space on the same medium; the same file is eligible for a new request.
                fixture.shelf().setItem(2, bounded.delete(before, "filler"));
                fixture.submit(3);
                after(h, () -> fixture.editor().replied(3), () -> fixture.assertWrittenAndFinish(h));
            });
        });
    }

    private static void withMediaMenu(GameTestHelper h, LibraryServiceConfiguration.Access grant, Consumer<MediaMenuFixture> ready) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1, 1, 1));
        var max = min.offset(4, 4, 4);
        var gateway = new BlockPos(max.getX(), min.getY() + 1, min.getZ() + 2);
        var member = gateway.west();
        var computer = AuthoredLibraryWorldFixture.computer(h, member, VillagerProfession.CLERIC, 5);
        var host = AuthoredLibraryWorldFixture.host(computer);
        for (var p : BlockPos.betweenClosed(min, max)) if (p.getX() == min.getX() || p.getX() == max.getX()
                || p.getY() == min.getY() || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
            level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(gateway, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState()
            .setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gateway);
        var access = new ClusterMemberEditorAccess(controller, computer);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "media-config"));
        actor.moveTo(Vec3.atCenterOf(gateway.east()));
        host.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        host.setPaused(true);
        host.configureEndpoint(new EndpointConfiguration("media", true));
        var members = Map.of("media", new ClusterConfiguration.Member(host.communicationIdentity().orElseThrow(), member));
        expect(h, "configured", controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of()), actor));
        host.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(access, actor, "s0", "media")));
        host.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        var libraryPos = gateway.east(3);
        for (int i = 1; i <= 2; i++) level.setBlock(gateway.east(i), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var libraryState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(libraryPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(libraryPos, libraryState.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(libraryPos.above(), libraryState.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var library = (LibraryBlockEntity)level.getBlockEntity(libraryPos);
        library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("menu-media"), 0, UUID.randomUUID(), 0));
        library.initialize();
        level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(
            media.upgrade(new ItemStack(Items.WRITABLE_BOOK)), "Tracks");
        shelf.setItem(2, book.copy());
        var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
        var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
        h.runAfterDelay(5, () -> {
            actor.moveTo(Vec3.atCenterOf(libraryPos));
            control.configure(actor, 0, Optional.of(control.discover(actor).stream().filter(choice -> choice.member().equals(member)).findFirst().orElseThrow()));
            expect(h, "configured", control.configureSerial(actor, 1, true));
            expect(h, "configured", service.configure(0, new LibraryServiceConfiguration(Optional.empty(),
                Map.of("Tracks", grant))));
            var editor = new MediaMenuPlayer(level, gateway.east(), access);
            var midi = HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000c00903c4060803c0000ff2f00");
            var track = LibraryTrackAddress.from(libraryPos, Direction.EAST, libraryPos.north());
            new SpeakerMediaChunk(editor.menu.containerId, editor.nonce, 1, "theme.mid", midi.length, 0, midi).handle(editor.player);
            h.assertValueEqual(editor.replies.getLast().code(), "media_staged", "Actual menu admitted the upload");
            var id = host.stagedFile(editor.player.getUUID()).orElseThrow().id();
            // Fixture wires and player chunk tickets were just created. Admit the request after discovery settles.
            h.runAfterDelay(10, () -> ready.accept(new MediaMenuFixture(h, controller, host, gateway.east(2), library, shelf, book,
                media, track, editor, id, midi)));
        });
    }

    private record MediaMenuFixture(GameTestHelper helper, ClusterControllerBlockEntity controller, ComputerBehaviour host,
            BlockPos cable, LibraryBlockEntity library, ChiseledBookShelfBlockEntity shelf, ItemStack book, LibraryBookMedia media,
            LibraryTrackAddress track, MediaMenuPlayer editor, UUID id, byte[] midi) {
        void submit(long intent) {
            editor.onPlayerTick = () -> {
                helper.assertTrue(!controller.physicalIoTickAvailable(), "Reproduce the actual pre-block-entity menu phase");
                editor.store(intent, id, library.getBlockPos(), track);
            };
        }

        void assertLocalOnly(GameTestHelper h) {
            assertStaged(h);
            h.assertTrue(ItemStack.matches(shelf.getItem(2), book), "The physical book remains unchanged");
        }

        void assertStaged(GameTestHelper h) {
            h.assertValueEqual(host.stagedFile(editor.player.getUUID()).orElseThrow().id(), id, "Failure retains the exact local file");
        }

        void assertWrittenAndFinish(GameTestHelper h) {
            h.assertValueEqual(editor.replies.getLast().code(), "media_stored", "Success follows the final correlated write receipt");
            h.assertTrue(host.stagedFile(editor.player.getUUID()).isEmpty(), "Only confirmed storage retires RAM");
            assertWritten(h);
            finish(h);
        }

        void assertWritten(GameTestHelper h) {
            var written = media.read(shelf.getItem(2)).get("theme");
            h.assertTrue(written != null && Arrays.equals(midi, SpeakerMediaLibraryFile.read(written.value(), midi.length)),
                "The physical book holds the exact uploaded media");
        }

        void finish(GameTestHelper h) {
            editor.player.closeContainer();
            h.getLevel().removePlayerImmediately(editor.player, Entity.RemovalReason.DISCARDED);
            h.succeed();
        }
    }

    private static final class MediaMenuPlayer {
        final ServerPlayer player;
        final ComputerProgramMenu menu;
        final UUID nonce;
        final List<SpeakerMediaPage> replies = new ArrayList<>();
        Runnable onPlayerTick;

        MediaMenuPlayer(ServerLevel level, BlockPos pos, ClusterMemberEditorAccess access) {
            var profile = new GameProfile(UUID.randomUUID(), "media-menu");
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation()) {
                @Override public void tick() {
                    var action = onPlayerTick;
                    onPlayerTick = null;
                    if (action != null) action.run();
                    super.tick();
                }
            };
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie) {
                @Override public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket message
                            && message.payload().type().equals(CBPackets.clientboundType().getType())) {
                        // The generic packet wraps several reply types. Observe its actual body; no production tracing hooks.
                        try {
                            var body = message.payload().getClass().getDeclaredMethod("packet");
                            body.setAccessible(true);
                            if (body.invoke(message.payload()) instanceof SpeakerMediaPage reply) replies.add(reply);
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                }
            };
            player.moveTo(Vec3.atCenterOf(pos));
            level.addNewPlayer(player);
            var provider = ComputerProgramMenu.provider(access);
            menu = (ComputerProgramMenu)provider.createMenu(11, player.getInventory(), player);
            player.containerMenu = menu;
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                provider.writeOpeningData(buffer);
                nonce = buffer.readUUID();
            } finally { buffer.release(); }
        }

        void store(long intent, UUID id, BlockPos library, LibraryTrackAddress track) {
            new SpeakerMediaStoreRequest(menu.containerId, nonce, intent, id, "s0", library,
                new BlockPos(track.x(), track.y(), track.z()), 2, "Tracks", "theme").handle(player);
        }
        boolean replied(long intent) { return replies.getLast().intent() == intent; }
    }

    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void publishedMediaProgramsPositionReadPlayCloseAndStopAcrossRealCluster(GameTestHelper h) {
        clusterPlayback(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void splitMediaProgramsPlayAndStopFromARealBookAcrossAClusterBus(GameTestHelper h) {
        clusterPlayback(h, true);
    }

    private static void clusterPlayback(GameTestHelper h, boolean split) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1, 1, 1));
        var max = min.offset(6, 4, 4);
        var gateway = new BlockPos(max.getX(), min.getY() + 1, min.getZ() + 2);
        var aBlock = AuthoredLibraryWorldFixture.computer(h, min.offset(1, 1, 2), VillagerProfession.CLERIC, 5);
        var bBlock = AuthoredLibraryWorldFixture.computer(h, gateway.west(), VillagerProfession.CLERIC, 5);
        var a = AuthoredLibraryWorldFixture.host(aBlock);
        var b = AuthoredLibraryWorldFixture.host(bBlock);
        var computers = new LinkedHashMap<String, ComputerBehaviour>(Map.of("a", a, "b", b));
        if (split) {
            for (var entry : Map.of("l", min.offset(2, 1, 1), "q", min.offset(3, 1, 1),
                    "s", min.offset(4, 1, 1), "f", min.offset(5, 1, 1)).entrySet()) {
                computers.put(entry.getKey(), AuthoredLibraryWorldFixture.host(
                    AuthoredLibraryWorldFixture.computer(h, entry.getValue(), VillagerProfession.CLERIC, 5)));
            }
        }
        for (var p : BlockPos.betweenClosed(min, max)) if (p.getX() == min.getX() || p.getX() == max.getX()
                || p.getY() == min.getY() || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
            level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(gateway, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gateway);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "media-driver"));
        actor.moveTo(Vec3.atCenterOf(gateway.east()));
        b.installDocument(example("speaker_media_wire"), ComputerRuntime.MemoryPolicy.RESET);
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        computers.forEach((name, computer) -> {
            if (computer != b) computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
            computer.configureEndpoint(new EndpointConfiguration(name, true));
            members.put(name, new ClusterConfiguration.Member(computer.communicationIdentity().orElseThrow(),
                computer.blockEntity.getBlockPos()));
        });
        var wires = split ? Map.of("library", wire("a", "x0", "l", "x0"), "query", wire("a", "x1", "q", "x0"),
                "status", wire("q", "x1", "s", "x0"), "frame", wire("s", "x1", "f", "x0"),
                "read_bus", wire("l", "x1", "b", "x0"), "speaker_bus", wire("f", "x1", "b", "x0"))
            : Map.of("media", wire("a", "x0", "b", "x0"));
        expect(h, "configured", controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), wires), actor));
        b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, bBlock), actor, "s0", "media")));
        b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        for (int n = 1; n <= 3; n++) level.setBlock(gateway.east(n), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);

        h.runAfterDelay(4, () -> configureMediaFixture(h, gateway, bBlock.getBlockPos(), computers, actor, split));
    }

    private static XBusWire wire(String left, String pin, String right, String peerPin) {
        return new XBusWire(new XBusWire.Pin(left, left, pin), new XBusWire.Pin(right, right, peerPin), true);
    }

    private static void configureMediaFixture(GameTestHelper h, BlockPos gateway, BlockPos member,
            Map<String, ComputerBehaviour> computers, FakePlayer actor, boolean split) {
        var a = computers.get("a");
        var level = h.getLevel();
        var libraryPos = gateway.east(4);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(libraryPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(libraryPos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(libraryPos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var library = (LibraryBlockEntity)level.getBlockEntity(libraryPos);
        library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("media-example"), 0, UUID.randomUUID(), 0));
        library.initialize();
        level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var file = new DataValue.RecordValue(Map.of("schema", text(SpeakerMediaLibraryFile.SCHEMA), "name", text("song.mp3"),
            "format", text("MP3"), "payload", text(Base64.getEncoder().encodeToString(loadMp3()))));
        var book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(
            media.put(new ItemStack(Items.WRITABLE_BOOK), "song", new LibraryEntry(LibraryEntry.Kind.DATA, file)), "Tracks");
        shelf.setItem(2, book.copy());
        var access = library.getBehaviour(LibraryServiceBehaviour.TYPE);
        expect(h, "configured", access.configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("Tracks", LibraryServiceConfiguration.Access.READ_ONLY))));
        var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
        actor.moveTo(Vec3.atCenterOf(libraryPos));
        control.configure(actor, 0, Optional.of(control.discover(actor).stream().filter(choice -> choice.member().equals(member)).findFirst().orElseThrow()));
        expect(h, "configured", control.configureSerial(actor, 1, true));

        var speakerPos = gateway.east(2).south();
        level.setBlock(speakerPos, CBBlocks.NARRATOR_OUTPUT.get().defaultBlockState().setValue(LocalTextOutputBlock.FACING, Direction.SOUTH), 3);
        var speaker = (LocalTextOutputBlockEntity)level.getBlockEntity(speakerPos);
        speaker.initialize();
        var serial = speaker.getBehaviour(LocalTextOutputSerialBehaviour.TYPE);
        actor.moveTo(Vec3.atCenterOf(speakerPos));
        expect(h, "configured", serial.configure(actor, 0, Optional.of(serial.discover(actor).stream().filter(choice -> choice.member().equals(member)).findFirst().orElseThrow())));
        var listener = new Listener(level, speakerPos.south(2), true);
        var libraryAddress = new LibraryTrackAddress(libraryPos.getX(), libraryPos.getY(), libraryPos.getZ()).value();
        var track = LibraryTrackAddress.from(libraryPos, Direction.EAST, libraryPos.north()).value();
        var inputs = new LinkedHashMap<String, DataValue>();
        inputs.put("track", track);
        if (split) {
            inputs.put("slot", new DataValue.IntegerValue(2));
            computers.get("l").installDocument(withInputs(bundledExample("speaker_media_library_stage"), Map.of("library", libraryAddress)), ComputerRuntime.MemoryPolicy.RESET);
            computers.get("q").installDocument(bundledExample("speaker_media_delivery_stage"), ComputerRuntime.MemoryPolicy.RESET);
            computers.get("s").installDocument(bundledExample("speaker_media_output_stage"), ComputerRuntime.MemoryPolicy.RESET);
            computers.get("f").installDocument(withInputs(bundledExample("speaker_media_frame_stage"), Map.of("speaker", serial.address())), ComputerRuntime.MemoryPolicy.RESET);
            a.installDocument(withInputs(bundledExample("speaker_media_default_play"), inputs), ComputerRuntime.MemoryPolicy.RESET);
        } else {
            inputs.put("speaker", serial.address());
            inputs.put("library", libraryAddress);
            a.installDocument(withInputs(example("speaker_media_program"), inputs), ComputerRuntime.MemoryPolicy.RESET);
        }
        // Players start one member at a time, with each upstream peer still paused.
        // Also keep this pace in tests so an eager blocking read cannot hide behind atomic startup.
        var workers = split ? List.of("b", "l", "q", "s", "f") : List.of("b");
        int startGap = 12;
        for (int i = 0; i < workers.size(); i++) {
            var worker = computers.get(workers.get(i));
            h.runAfterDelay(startGap * (i + 1), () -> worker.setPaused(false));
        }
        h.runAfterDelay(startGap * (workers.size() + 1), () -> {
            for (var name : workers) {
                var workerState = computers.get(name).flowSnapshot().orElseThrow().state();
                h.assertTrue(workerState.status() != FlowRuntime.Status.FAILED, name + " failed while awaiting its first caller: " + workerState.diagnostic());
            }
            a.setPaused(false);
            awaitProgram(h, computers, locals -> {
                h.assertValueEqual(locals.field("stage"), text("read_close"), "Player driver actually closes the ready read");
                code(h, (DataValue.RecordValue)locals.field("reply"), "acknowledged");
                h.assertValueEqual(playback((DataValue.RecordValue)locals.field("speaker_reply")), text("dispatched"), "Returned media state is separate");
                h.assertTrue(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().reading().isEmpty(), "No retained read lock");
                h.assertTrue(ItemStack.matches(shelf.getItem(2), book), "Read-only drive never edits a book");
                h.assertValueEqual(listener.deliveries, 3, "Exactly one delivery");
                long readSequence = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().sequence();
                a.reset(); // Ordinary explicit reset after completion, never collection or replay of receipts.
                if (split) {
                    a.installDocument(bundledExample("speaker_media_default_stop"), ComputerRuntime.MemoryPolicy.RESET);
                } else {
                    inputs.put("play", new DataValue.BooleanValue(false));
                    a.installDocument(withInputs(example("speaker_media_program"), inputs), ComputerRuntime.MemoryPolicy.RESET);
                }
                a.setPaused(false);
                awaitProgram(h, computers, stopped -> {
                    h.assertValueEqual(stopped.field("stage"), text("stop"), "Separate stop entry reached");
                    code(h, (DataValue.RecordValue)stopped.field("reply"), "accepted");
                    h.assertValueEqual(listener.deliveries, 4, "One explicit stop");
                    h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().sequence(), readSequence,
                        "The stop path never touches Library");
                    listener.close();
                    h.succeed();
                });
            });
        });
    }

    private static ProgramDocument bundledExample(String name) {
        var limits = CBConfigs.SERVER.biocomputing.current(true).archive();
        var entry = new ProgramExampleCatalog.Example(name,
            ResourceLocation.fromNamespaceAndPath("create_biotech", "biocomputing/examples/" + name + ".bcp"), name, List.of());
        return ProgramExampleCatalog.preview(entry, id -> SpeakerLibraryReferenceGameTests.class.getResourceAsStream(
            "/assets/" + id.getNamespace() + "/" + id.getPath()), limits);
    }

    private static ProgramDocument example(String name) {
        java.util.function.Function<ResourceLocation, java.io.InputStream> open = id ->
            SpeakerLibraryReferenceGameTests.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
        var entry = ProgramExampleCatalog.load(open).stream().filter(row -> row.id().equals(name)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, open, CBConfigs.SERVER.biocomputing.current(true).archive());
    }

    private static ProgramDocument withInputs(ProgramDocument document, Map<String, DataValue> supplied) {
        var body = document.flow().orElseThrow();
        var fields = new LinkedHashMap<>(body.initial().fields());
        fields.putAll(supplied);
        return new ProgramDocument(document.archive(), document.positions(), document.terminal(),
            Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(fields), body.canvas(), body.inline(), body.execution())));
    }

    private static void awaitProgram(GameTestHelper h, Map<String, ComputerBehaviour> computers, Consumer<DataValue.RecordValue> finished) {
        h.runAfterDelay(2, () -> {
            computers.forEach((name, machine) -> {
                var state = machine.flowSnapshot().orElseThrow().state();
                h.assertTrue(state.status() != FlowRuntime.Status.FAILED, name + ": " + state.diagnostic());
            });
            var first = computers.get("a").flowSnapshot().orElseThrow().state();
            if (first.status() == FlowRuntime.Status.COMPLETE) finished.accept(first.locals());
            else awaitProgram(h, computers, finished);
        });
    }

    private static void run(GameTestHelper h, String format, boolean playback, boolean corrupt) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(8, 2, 3));
        var block = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var library = (LibraryBlockEntity) level.getBlockEntity(pos);
        library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("speaker-media-reference"), 0, UUID.randomUUID(), 0));
        library.initialize();
        var computer = AuthoredLibraryWorldFixture.host(AuthoredLibraryWorldFixture.computer(h, pos.west(4), VillagerProfession.LIBRARIAN, 4));
        computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        for (int i = 1; i <= 3; i++) level.setBlock(pos.west(i), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "speaker-reference"));
        actor.moveTo(Vec3.atCenterOf(pos));
        var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
        control.configure(actor, 0, Optional.of(control.discover(actor).getFirst()));
        expect(h, "configured", control.configureSerial(actor, 1, true));
        var port = computer.serialDevice();
        port.configure("s0", Direction.EAST, true);

        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        byte[] audio = format.equals("MIDI") ? HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000c00903c4020803c0000ff2f00")
            : loadMp3();
        if (corrupt) audio = Arrays.copyOf(audio, audio.length - 1);
        var file = new DataValue.RecordValue(Map.of("schema", text(SpeakerMediaLibraryFile.SCHEMA), "name", text(format.equals("MIDI") ? "song.mid" : "song.mp3"),
            "format", text(format), "payload", text(Base64.getEncoder().encodeToString(audio))));
        var book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(
            media.put(new ItemStack(Items.WRITABLE_BOOK), "song", new LibraryEntry(LibraryEntry.Kind.DATA, file)), "Tracks");
        shelf.setItem(2, book.copy());
        var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
        expect(h, "configured", service.configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("Tracks", LibraryServiceConfiguration.Access.READ_ONLY))));
        var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE);
        var target = new LibraryTrackAddress(pos.getX(), pos.getY(), pos.getZ()).value();
        var track = LibraryTrackAddress.from(pos, Direction.EAST, pos.north()).value();
        var nullValue = DataValue.NullValue.INSTANCE;
        h.runAfterDelay(4, () -> frame(h, port, target, 1, "seek", 0, track, seek -> {
            code(h, seek, "accepted");
            after(h, () -> drive.snapshot().orElseThrow().result().isPresent(), () ->
                frame(h, port, target, 2, "ack", 1, nullValue, ack -> {
                    code(h, ack, "acknowledged");
                    frame(h, port, target, 3, "select", 1, number(2), select -> {
                        code(h, select, "accepted");
                        after(h, () -> drive.snapshot().orElseThrow().result().isPresent(), () ->
                            frame(h, port, target, 4, "ack", 2, nullValue, selected -> {
                                code(h, selected, "acknowledged");
                                frame(h, port, target, 5, "read_begin", 2, text("song"), reading -> {
                                    code(h, reading, "accepted");
                                    after(h, () -> drive.snapshot().orElseThrow().reading().filter(LibraryReadTransfer::ready).isPresent(),
                                        () -> frame(h, port, target, 6, "read_ref", 3, nullValue, reply -> {
                                            code(h, reply, "read_ref");
                                            var reference = (DataValue.RecordValue) reply.field("value");
                                            h.assertValueEqual(reference.fields().keySet(), Set.of("library", "device", "sequence", "version"), "No media data on this frame");
                                            h.assertValueEqual(reference.field("library"), target, "Live Library address");
                                            h.assertValueEqual(reference.field("device"), text(library.libraryDeviceId().toString()), "Live Library identity");
                                            h.assertValueEqual(reference.field("sequence"), number(3), "Existing read sequence");
                                            h.assertTrue(drive.snapshot().orElseThrow().reading().filter(LibraryReadTransfer::ready).isPresent(), "Does not consume the read");
                                            if (playback) {
                                                verifySpeaker(h, port, pos.west(2).south(), reference, corrupt);
                                                return;
                                            }
                                            expect(h, "configured", service.configure(1, new LibraryServiceConfiguration(Optional.empty(), Map.of())));
                                            frame(h, port, target, 7, "read_ref", 3, nullValue, denied -> {
                                                code(h, denied, "denied");
                                                h.assertValueEqual(denied.field("value"), nullValue, "No stale reference after revocation");
                                                h.assertTrue(ItemStack.matches(shelf.getItem(2), book), "Never changes the original book");
                                                h.succeed();
                                            });
                                        }));
                                });
                            }));
                    });
                }));
        }));
    }

    private static void verifySpeaker(GameTestHelper h, SerialDevice port, BlockPos pos, DataValue.RecordValue reference, boolean corrupt) {
        var level = h.getLevel();
        level.setBlock(pos, CBBlocks.NARRATOR_OUTPUT.get().defaultBlockState().setValue(LocalTextOutputBlock.FACING, Direction.SOUTH), 3);
        var speaker = (LocalTextOutputBlockEntity) level.getBlockEntity(pos);
        speaker.initialize();
        var serial = speaker.getBehaviour(LocalTextOutputSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "speaker-operator"));
        actor.moveTo(Vec3.atCenterOf(pos));
        expect(h, "configured", serial.configure(actor, 0, Optional.of(serial.discover(actor).getFirst())));
        var heard = new Listener(level, pos.south(2), true);
        var muted = new Listener(level, pos.south(3), false);
        h.runAfterDelay(4, () -> speakerFrame(h, port, serial.address(), "inspect", "status", Map.of(), status -> {
            code(h, status, "ok");
            var args = new LinkedHashMap<>(token(status));
            args.put("reference", reference);
            speakerFrame(h, port, serial.address(), "start", "play", args, admitted -> {
                code(h, admitted, "accepted");
                h.assertValueEqual(playback(admitted), text("admitted"), "A serial receipt is not acoustic playback");
                if (corrupt) {
                    after(h, () -> text("media_invalid").equals(serial.status().field("media")), () -> {
                        h.assertValueEqual(heard.deliveries, 0, "Corrupt audio never begins a client transfer");
                        h.assertValueEqual(muted.deliveries, 0, "Mute remains independent");
                        heard.close(); muted.close();
                        h.succeed();
                    });
                    return;
                }
                after(h, () -> text("dispatched").equals(serial.status().field("media")), () -> {
                    h.assertValueEqual(heard.deliveries, 3, "One begin, one data packet for the tiny file, one explicit end");
                    h.assertValueEqual(muted.deliveries, 0, "Muted client has not received media");
                    speakerFrame(h, port, serial.address(), "retry", "play", args, stale -> {
                        code(h, stale, "changed");
                        h.assertValueEqual(heard.deliveries, 3, "Replayed control never sends another track");
                        speakerFrame(h, port, serial.address(), "stop", "stop", token(admitted), stopped -> {
                            code(h, stopped, "accepted");
                            h.assertValueEqual(heard.deliveries, 4, "Explicit stop uses the active playback identity");
                            h.assertValueEqual(serial.status().field("result"), text("idle"), "Media never changes narration status");
                            heard.close(); muted.close();
                            h.succeed();
                        });
                    });
                });
            });
        }));
    }

    private static byte[] loadMp3() {
        try (var input = Objects.requireNonNull(SpeakerLibraryReferenceGameTests.class.getResourceAsStream("/biocomputing/speaker-test-tone.mp3"))) {
            return input.readAllBytes();
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static DataValue playback(DataValue.RecordValue reply) {
        return ((DataValue.RecordValue) reply.field("state")).field("playback");
    }
    private static Map<String, DataValue> token(DataValue.RecordValue reply) {
        var state = (DataValue.RecordValue)reply.field("state");
        return Map.of("device", state.field("device"), "session", state.field("session"), "revision", state.field("revision"));
    }
    private static void speakerFrame(GameTestHelper h, SerialDevice port, DataValue.RecordValue target, String request,
            String op, Map<String, DataValue> arguments, Consumer<DataValue.RecordValue> onReply) {
        exchange(h, port, new DataValue.RecordValue(Map.of("kind", text("speaker_media_command"), "version", number(1), "target", target,
            "request", text(request), "operation", text(op), "arguments", new DataValue.RecordValue(arguments))), "speaker_media_reply", onReply);
    }

    private static void frame(GameTestHelper h, SerialDevice port, DataValue.RecordValue target, long request,
            String op, long sequence, DataValue data, Consumer<DataValue.RecordValue> onReply) {
        exchange(h, port, new DataValue.RecordValue(Map.of("kind", text("library_command"), "target", target,
            "request", number(request), "operation", text(op), "sequence", number(sequence), "data", data)), "library_reply", onReply);
    }
    private static void exchange(GameTestHelper h, SerialDevice port, DataValue.RecordValue frame, String kind, Consumer<DataValue.RecordValue> onReply) {
        expect(h, "queued", port.send("s0", frame));
        after(h, () -> port.status("s0").receive().frames() > 0, () -> {
            var prepared = port.prepareReceive("s0");
            h.assertValueEqual(prepared.result().field("status"), text("received"), "Actual typed serial reply");
            h.assertTrue(prepared.publish(), "Reply consumed once");
            var reply = (DataValue.RecordValue) prepared.result().field("value");
            h.assertValueEqual(reply.field("kind"), text(kind), "Device response");
            h.assertValueEqual(reply.field("request"), frame.field("request"), "Correlation returned over the wire");
            onReply.accept(reply);
        });
    }

    private static void after(GameTestHelper h, BooleanSupplier ready, Runnable continuation) {
        h.runAfterDelay(2, () -> {
            if (ready.getAsBoolean()) continuation.run();
            else after(h, ready, continuation);
        });
    }
    private static void code(GameTestHelper h, DataValue.RecordValue reply, String code) {
        h.assertValueEqual(reply.field("code"), text(code), "Library business result");
    }
    private static void expect(GameTestHelper h, String expected, String actual) {
        h.assertValueEqual(actual, expected, "Endpoint operation result");
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }

    private static final class Listener implements AutoCloseable {
        final ServerPlayer player;
        int deliveries;
        Listener(ServerLevel level, BlockPos pos, boolean enabled) {
            var profile = new GameProfile(UUID.randomUUID(), "media-" + pos.getZ());
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie) {
                @Override public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket message
                        && message.payload().type().equals(CBPackets.clientboundType().getType())) deliveries++;
                }
            };
            player.moveTo(Vec3.atCenterOf(pos));
            level.addNewPlayer(player);
            new SpeakerPlaybackPreference(1, enabled).handle(player);
        }
        @Override public void close() {
            player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }
}
