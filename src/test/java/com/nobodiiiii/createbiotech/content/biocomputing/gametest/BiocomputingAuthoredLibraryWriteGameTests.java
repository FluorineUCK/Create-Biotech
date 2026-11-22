package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.computer;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.host;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.reconstruct;

/** Real registered owners, Cluster scheduler, serial wire, grants and media. No command/source emulation. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingAuthoredLibraryWriteGameTests {
    @GameTest(template="empty", timeoutTicks=30000)
    public static void publicAuthoredWriteSurvivesPausedOwnersAndActualWorldReconstruction(GameTestHelper h) {
        new Fixture(h).start();
    }

    private static ProgramDocument document(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = location ->
            BiocomputingAuthoredLibraryWriteGameTests.class.getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing public example catalog entry: " + id));
        return ProgramExampleCatalog.preview(entry, open, CBConfigs.SERVER.biocomputing.current(true).archive());
    }

    private static ProgramDocument initial(ProgramDocument document, Map<String, DataValue> values) {
        var body = document.flow().orElseThrow();
        return new ProgramDocument(document.archive(), document.positions(), document.terminal(), Optional.of(
            new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(values), body.canvas(), body.inline(), body.execution())));
    }

    private static final class Fixture {
        final GameTestHelper h;
        final ServerLevel level;
        final ComputerBlockEntity[] computers = new ComputerBlockEntity[5];
        final List<HardwareSelection> hardware = new ArrayList<>();
        final List<FlowProgram> programs = new ArrayList<>();
        final BlockPos controllerPos, libraryPos;
        final ClusterControllerBlockEntity controller;
        final FakePlayer actor;
        final ChiseledBookShelfBlockEntity shelf;
        final LibraryBookMedia media;
        final ItemStack original;
        final LibraryMutation.Version version;
        LibraryBlockEntity library;
        boolean pausedForWrite, reconstructed, autonomyObserved;
        final long[] pausedWork = new long[4];
        LibraryDeviceWrite admittedOperation;
        int ticks, valueChanges;
        DataValue lastValue = new DataValue.IntegerValue(43);

        Fixture(GameTestHelper h) {
            this.h = h; level = h.getLevel();
            // The initial integration RED must be the real missing public entry, not an authoring fallback.
            var aDocument = document("library_write_a");
            var dDocument = document("library_write_d");
            var bDocument = document("library_read_b");
            var cDocument = document("library_read_c");
            var min = h.absolutePos(new BlockPos(1, 1, 1));
            var max = min.offset(6, 4, 6);
            controllerPos = min.offset(6, 1, 2); libraryPos = controllerPos.east(4);
            computers[0] = computer(h, min.offset(1, 1, 1), VillagerProfession.MASON, 5);
            computers[1] = computer(h, controllerPos.west(), VillagerProfession.LIBRARIAN, 5);
            computers[2] = computer(h, min.offset(1, 1, 4), VillagerProfession.ARMORER, 4);
            computers[3] = computer(h, min.offset(3, 1, 4), VillagerProfession.MASON, 5);
            computers[4] = computer(h, min.offset(4, 1, 5), VillagerProfession.NONE, 1);
            for (var p : BlockPos.betweenClosed(min, max))
                if (p.getX()==min.getX() || p.getX()==max.getX() || p.getY()==min.getY() || p.getY()==max.getY() || p.getZ()==min.getZ() || p.getZ()==max.getZ())
                    level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
            level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
            controller = (ClusterControllerBlockEntity) level.getBlockEntity(controllerPos);
            actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "authored-write"));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
            var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
            var names = List.of("a", "b", "c", "d", "ordinary");
            for (int i=0; i<5; i++) {
                host(computers[i]).installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
                host(computers[i]).configureEndpoint(new EndpointConfiguration(names.get(i), true));
                members.put(names.get(i), new ClusterConfiguration.Member(host(computers[i]).communicationIdentity().orElseThrow(), computers[i].getBlockPos()));
            }
            var wires = Map.of(
                "ab", new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true),
                "bc", new XBusWire(new XBusWire.Pin("b", "b", "x1"), new XBusWire.Pin("c", "c", "x0"), true),
                "ad", new XBusWire(new XBusWire.Pin("a", "a", "x1"), new XBusWire.Pin("d", "d", "x0"), true));
            h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), wires), actor), "configured", "Explicit three production XBus wires");
            var b = host(computers[1]);
            var gateway = SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, computers[1]), actor, "s0", "write");
            b.configureSerialGateway("s0", Optional.of(gateway));
            b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
            for (int i=1; i<=3; i++) level.setBlock(controllerPos.east(i), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
            level.setBlock(libraryPos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(libraryPos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
            level.setBlock(libraryPos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
            library = (LibraryBlockEntity) level.getBlockEntity(libraryPos);
            library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("authored-write"), 0, UUID.randomUUID(), 0));
            library.initialize();
            level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
            shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(libraryPos.north());
            media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("Preserve original human notes and recipe page"))));
            book.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Native write witness"));
            book = media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43)));
            book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book, "WriteBook");
            shelf.setItem(2, book.copy()); original = book.copy();
            version = LibraryMutation.Version.decode(media.stat(original, "reading").field("version"));
            library.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("WriteBook", LibraryServiceConfiguration.Access.READ_WRITE)));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(libraryPos));
            var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
            var selected = control.discover(actor).stream().filter(candidate -> candidate.member().equals(computers[1].getBlockPos()) && candidate.controller().isPresent()).findFirst().orElseThrow();
            control.configure(actor, 0, Optional.of(selected)); control.configureSerial(actor, 1, true);

            var values = new LinkedHashMap<>(aDocument.flow().orElseThrow().initial().fields());
            values.put("target", new LibraryTrackAddress(libraryPos.getX(), libraryPos.getY(), libraryPos.getZ()).value());
            values.put("replacement", new DataValue.IntegerValue(91));
            var rows = new ArrayList<>(((DataValue.ListValue) values.get("commands")).values());
            var seek = new LinkedHashMap<>(((DataValue.RecordValue) rows.get(1)).fields());
            seek.put("d", LibraryTrackAddress.from(libraryPos, Direction.EAST, libraryPos.north()).value());
            rows.set(1, new DataValue.RecordValue(seek)); values.put("commands", new DataValue.ListValue(rows));
            var dValues = new LinkedHashMap<>(dDocument.flow().orElseThrow().initial().fields());
            dValues.put("volume", new DataValue.TextValue("WriteBook"));
            host(computers[0]).installDocument(initial(aDocument, values), ComputerRuntime.MemoryPolicy.RESET);
            host(computers[1]).installDocument(bDocument, ComputerRuntime.MemoryPolicy.RESET);
            host(computers[2]).installDocument(cDocument, ComputerRuntime.MemoryPolicy.RESET);
            host(computers[3]).installDocument(initial(dDocument, dValues), ComputerRuntime.MemoryPolicy.RESET);
            for (int i=0; i<5; i++) hardware.add(host(computers[i]).hardware());
            for (int i=0; i<4; i++) programs.add(host(computers[i]).flowSnapshot().orElseThrow().program());
            for (int i : List.of(0, 1, 3)) h.assertValueEqual(hardware.get(i).limits().hardware(), new ResourceLimits(256, 65536, 8), "Independent A/B/D default limits");
            h.assertValueEqual(hardware.get(2).limits().hardware(), new ResourceLimits(192, 49152, 6), "C remains lower capacity");
            h.assertValueEqual(hardware.get(4).limits().hardware().programUnits(), 32L, "Ordinary member remains 32 words");
        }

        void start() { for (int i=3; i>=0; i--) host(computers[i]).setPaused(false); poll(); }

        void poll() { h.runAfterDelay(1, () -> {
            ticks++;
            var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            h.assertTrue(drive.sequence() <= 5, "Exactly one seek/select/stat/write/read, no automatic repeat");
            var current = media.read(shelf.getItem(2)).get("reading").value();
            if (!current.equals(lastValue)) { valueChanges++; lastValue=current; }
            h.assertTrue(valueChanges <= 1, "Book content changes at most once");
            for (int i=0; i<4; i++) {
                var owner = host(computers[i]);
                h.assertTrue(owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.FAILED, "Native owner " + i + " failed: " + owner.flowSnapshot().orElseThrow().state().diagnostic());
                h.assertValueEqual(owner.hardware(), hardware.get(i), "No pooled or upgraded hardware");
                h.assertValueEqual(owner.flowSnapshot().orElseThrow().program(), programs.get(i), "Installed public source stays unchanged");
            }
            h.assertValueEqual(host(computers[4]).hardware(), hardware.get(4), "Ordinary member unchanged");
            h.assertTrue(host(computers[4]).flowSnapshot().orElseThrow().state().paused(), "Ordinary member never assists hidden work");
            h.assertValueEqual(host(computers[4]).flowSnapshot().orElseThrow().state().work(), 0L, "Ordinary CPU remains unused");
            if (!pausedForWrite && drive.writing().filter(w -> w.remaining()>0).isPresent()) {
                admittedOperation = drive.writing().orElseThrow().operation();
                h.assertValueEqual(admittedOperation.mutation().expected(), version, "Write uses observed physical stat version");
                h.assertValueEqual(current, new DataValue.IntegerValue(43), "Admission does not immediately mutate media");
                for (int i=0; i<4; i++) { host(computers[i]).setPaused(true); pausedWork[i]=host(computers[i]).flowSnapshot().orElseThrow().state().work(); }
                pausedForWrite=true;
            }
            if (pausedForWrite && !reconstructed) {
                for (int i=0; i<4; i++) h.assertValueEqual(host(computers[i]).flowSnapshot().orElseThrow().state().work(), pausedWork[i], "Library progresses without owner CPU work");
                if (drive.result().filter(r -> r.code().equals("written")).isPresent()) {
                    autonomyObserved=true;
                    h.assertValueEqual(current, new DataValue.IntegerValue(91), "Physical write commits while all programs paused");
                    if (serialQuiescent()) { rebuild(drive); return; }
                }
            }
            var a = host(computers[0]).flowSnapshot().orElseThrow().state();
            if (a.status()!=FlowRuntime.Status.COMPLETE) {
                if (ticks%500==0) {
                    System.out.println("AUTHORED_WRITE progress tick="+ticks+" sequence="+drive.sequence()+" paused="+pausedForWrite+" rebuilt="+reconstructed);
                    for (int i=0; i<4; i++) {
                        var state = host(computers[i]).flowSnapshot().orElseThrow().state();
                        System.out.println("AUTHORED_WRITE owner="+i+" status="+state.status()+" work="+state.work()+" cursor="+state.cursor()
                            +" pc="+state.assembly().map(s -> s.pc()).orElse(-1)+" locals="+state.locals().fields());
                    }
                    System.out.println("AUTHORED_WRITE serial B="+host(computers[1]).serialDevice().status("s0")
                        +" Library="+library.getBehaviour(LibrarySerialBehaviour.TYPE).device().status("s0"));
                }
                poll(); return;
            }
            h.assertTrue(pausedForWrite && autonomyObserved && reconstructed, "Actual autonomous write and same-JVM reconstruction occurred");
            h.assertValueEqual(a.locals().field("success"), new DataValue.BooleanValue(true), "Public four-owner write/readback succeeds");
            h.assertValueEqual(a.locals().field("error"), DataValue.NullValue.INSTANCE, "No hidden failure");
            h.assertValueEqual(((DataValue.RecordValue)a.locals().field("read_result")).field("value"), new DataValue.IntegerValue(91), "Actual readback equals caller replacement");
            h.assertValueEqual(drive.sequence(), 5L, "Five physical admissions only");
            h.assertTrue(drive.result().isEmpty(), "Final explicit ACK consumed readback");
            h.assertValueEqual(valueChanges, 1, "Exactly one observed physical content change");
            assertBook();
            h.assertTrue(controller.enclosure().enclosure().isPresent(), "Actual closed enclosure remains valid");
            h.assertTrue(library.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(), "No legacy advertised service path");
            var finalA = host(computers[0]).flowSnapshot();
            h.runAfterDelay(40, () -> {
                h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), drive, "No implicit post-completion retry or ACK");
                h.assertValueEqual(host(computers[0]).flowSnapshot(), finalA, "Completed A does not replay");
                assertBook();
                System.out.println("AUTHORED_WRITE completed ticks="+ticks+" sequence="+drive.sequence()+" valueChanges="+valueChanges+" autonomy="+autonomyObserved+" sameJvmReconstruction="+reconstructed);
                h.succeed();
            });
        }); }

        boolean serialQuiescent() {
            var b = host(computers[1]).serialDevice().status("s0");
            var l = library.getBehaviour(LibrarySerialBehaviour.TYPE).device().status("s0");
            // B may retain the real accepted reply. No injected reply, cleared buffer, or retransmission.
            return !Set.of("queued", "transmitting").contains(b.transmit())
                && !Set.of("queued", "transmitting").contains(l.transmit()) && l.receive().frames()==0;
        }

        void rebuild(LibraryReadHead.State retained) {
            var before = new ArrayList<FlowBlockHost.Saved>();
            for (int i=0; i<4; i++) before.add(host(computers[i]).flowSnapshot().orElseThrow());
            var serialBefore = host(computers[1]).serialDevice().write();
            var librarySerial = library.getBehaviour(LibrarySerialBehaviour.TYPE).archive();
            var history = XBusHistorySavedData.get(level).save(new CompoundTag(), level.registryAccess());
            h.assertValueEqual(history.getInt("Version"), 7, "Production history schema 7");
            for (int i=0; i<4; i++) computers[i]=(ComputerBlockEntity) reconstruct(level, computers[i]);
            library=(LibraryBlockEntity) reconstruct(level, library);
            level.getDataStorage().set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(history, level.registryAccess()));
            h.assertValueEqual(history, XBusHistorySavedData.get(level).save(new CompoundTag(), level.registryAccess()), "Real SavedData identities preserved");
            reconstructed=true;
            h.runAfterDelay(30, () -> {
                for (int i=0; i<4; i++) {
                    host(computers[i]).document();
                    h.assertValueEqual(host(computers[i]).flowSnapshot().orElseThrow(), before.get(i), "Exact paused checkpoint without reset/reinstall");
                }
                h.assertValueEqual(host(computers[1]).serialDevice().write(), serialBefore, "Actual B buffered reply preserved");
                h.assertValueEqual(library.getBehaviour(LibrarySerialBehaviour.TYPE).archive(), librarySerial, "Library serial state preserved");
                h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), retained, "Same retained mechanical result and operation identity");
                h.assertValueEqual(retained.writing().orElseThrow().operation(), admittedOperation, "No new write identity on reconstruction");
                for (int i=3; i>=0; i--) host(computers[i]).setPaused(false);
                poll();
            });
        }

        void assertBook() {
            var actual = shelf.getItem(2);
            var after = LibraryMutation.Version.decode(media.stat(actual, "reading").field("version"));
            h.assertValueEqual(after.medium(), version.medium(), "Same physical medium identity");
            h.assertValueEqual(after.revision(), version.revision()+1, "Exactly one entry version increment");
            h.assertValueEqual(media.read(actual).get("reading").value(), new DataValue.IntegerValue(91), "Committed replacement persists");
            var normalized=actual.copy();
            normalized.set(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), original.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()));
            h.assertTrue(ItemStack.matches(normalized, original), "All non-Library components, original text/name and volume remain unchanged");
        }
    }
}
