package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
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

/** Registered physical owners and schedulers. No direct exchange or emulated disk commands. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterJournalGameTests {
    @GameTest(template = "empty", timeoutTicks = 30000)
    public static void externalCallerUsesConcentratedSerialAndInternalPhysicalLibraryWithoutPoolingMembers(GameTestHelper h) {
        new Fixture(h, Scenario.WRITE).start();
    }

    @GameTest(template = "empty", timeoutTicks = 30000)
    public static void revokingAnAdmittedWriteReturnsDeniedAndRestoringAccessDoesNotRetry(GameTestHelper h) {
        new Fixture(h, Scenario.REVOKE).start();
    }

    @GameTest(template = "empty", timeoutTicks = 30000)
    public static void swappingAnAdmittedWritesBookForAnotherVolumeOfTheSameNameDoesNotRetargetIt(GameTestHelper h) {
        new Fixture(h, Scenario.SWAP).start();
    }

    private enum Scenario { WRITE, REVOKE, SWAP }

    private static ProgramDocument document(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = location ->
            BiocomputingClusterJournalGameTests.class.getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, open, CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    private static ProgramDocument initial(ProgramDocument document, Map<String, DataValue> values) {
        var body = document.flow().orElseThrow();
        return new ProgramDocument(document.archive(), document.positions(), document.terminal(), Optional.of(new FlowDocumentBody(
            body.inputs(), body.steps(), new DataValue.RecordValue(values), body.canvas(), body.inline(), body.execution(), body.compilation())));
    }

    private static final class Fixture {
        final GameTestHelper h;
        final ServerLevel level;
        final Map<String, ComputerBlockEntity> computers = new LinkedHashMap<>();
        final Map<String, HardwareSelection> hardware = new LinkedHashMap<>();
        final Map<String, FlowProgram> programs = new LinkedHashMap<>();
        final Map<String, UUID> identities = new LinkedHashMap<>();
        final ClusterControllerBlockEntity controller;
        final LibraryBlockEntity library;
        final ChiseledBookShelfBlockEntity shelf;
        final LibraryBookMedia media;
        final ItemStack original;
        final LibraryMutation.Version version;
        final BlockPos min, max, controllerPos, libraryPos;
        final Scenario scenario;
        ItemStack removed = ItemStack.EMPTY, replacement = ItemStack.EMPTY;
        boolean interrupted;
        int ticks, changes;
        DataValue previous = new DataValue.IntegerValue(43);

        Fixture(GameTestHelper h, Scenario scenario) {
            this.scenario = scenario;
            this.h = h; level = h.getLevel(); min = h.absolutePos(new BlockPos(3,1,1)); max = min.offset(6,4,6);
            controllerPos = min.offset(0,1,3); libraryPos = min.offset(5,1,3);
            computers.put("caller", computer(h, controllerPos.west(2), VillagerProfession.CLERIC, 5));
            computers.put("g", computer(h, min.offset(1,1,1), VillagerProfession.CLERIC, 5));
            computers.put("w", computer(h, min.offset(1,1,2), VillagerProfession.CLERIC, 5));
            computers.put("p", computer(h, min.offset(1,1,4), VillagerProfession.CLERIC, 5));
            computers.put("a", computer(h, min.offset(1,1,5), VillagerProfession.CLERIC, 5));
            computers.put("q", computer(h, min.offset(2,1,5), VillagerProfession.CLERIC, 5));
            computers.put("b", computer(h, min.offset(3,1,3), VillagerProfession.CLERIC, 5));
            computers.put("c", computer(h, min.offset(3,1,5), VillagerProfession.CLERIC, 5));
            computers.put("ordinary", computer(h, min.offset(5,1,5), VillagerProfession.NONE, 1));
            // The disk and its management furniture really are inside the casing, not a second gateway face.
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
            level.setBlock(libraryPos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(libraryPos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
            level.setBlock(libraryPos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
            library = (LibraryBlockEntity)level.getBlockEntity(libraryPos);
            library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("journal-native"),0,UUID.randomUUID(),0));
            library.initialize();
            level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
            level.setBlock(libraryPos.north().west(), Blocks.LECTERN.defaultBlockState(), 3);
            level.setBlock(libraryPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
            media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("Keep these human notes."))));
            book.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Physical cluster journal"));
            book = media.put(book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,previous));
            book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book,"plant");
            shelf.setItem(2,book.copy()); original = book.copy();
            version = LibraryMutation.Version.decode(media.stat(original,"reading").field("version"));
            for (var pos : BlockPos.betweenClosed(min,max))
                if(pos.getX()==min.getX()||pos.getX()==max.getX()||pos.getY()==min.getY()||pos.getY()==max.getY()||pos.getZ()==min.getZ()||pos.getZ()==max.getZ())
                    level.setBlock(pos,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
            level.setBlock(controllerPos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.WEST),3);
            controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
            level.setBlock(controllerPos.west(),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
            var actor = new FakePlayer(level,new GameProfile(UUID.randomUUID(),"journal-setup"));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.west()));
            var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
            for(var entry:computers.entrySet()) {
                var owner=host(entry.getValue());
                owner.installFlow(new FlowProgram(Map.of(),List.of()).withAssembly(),new DataValue.RecordValue(Map.of()));
                owner.configureEndpoint(new EndpointConfiguration(entry.getKey(),true));
                if(!entry.getKey().equals("caller")) members.put(entry.getKey(),new ClusterConfiguration.Member(owner.communicationIdentity().orElseThrow(),entry.getValue().getBlockPos()));
            }
            var wires = Map.of("gw",wire("g","x0","w","x0"), "wp",wire("w","x1","p","x0"),
                "pa",wire("p","x1","a","x1"), "aq",wire("a","x0","q","x0"),
                "qb",wire("q","x1","b","x0"), "bc",wire("b","x1","c","x0"));
            h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),wires),actor),"configured","Explicit six internal wires");
            var enclosure=controller.enclosure().enclosure().orElseThrow();
            h.assertValueEqual(enclosure.members().size(),8,"Seven working members and one ordinary member, caller excluded");
            h.assertTrue(level.getBlockState(min.offset(4,2,4)).isAir(),"Air remains valid inside the sealed casing");
            var gateway=SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,computers.get("g")),actor,"s0","telemetry");
            host(computers.get("g")).configureSerialGateway("s0",Optional.of(gateway));
            host(computers.get("g")).configureSerial(new SerialControlData.Configuration("s0",Direction.WEST,true));
            host(computers.get("caller")).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
            host(computers.get("b")).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
            h.assertTrue(host(computers.get("b")).serialDevice().view().ports().get("s0").gateway().isEmpty(),"Private B does not secretly use a second gateway channel");
            library.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0,new LibraryServiceConfiguration(Optional.empty(),Map.of("plant",LibraryServiceConfiguration.Access.READ_WRITE)));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(libraryPos));
            var control=library.getBehaviour(LibraryControlBehaviour.TYPE);
            var binding=control.discover(actor).stream().filter(c->c.member().equals(computers.get("b").getBlockPos())&&c.controller().isEmpty()).findFirst().orElseThrow();
            control.configure(actor,0,Optional.of(binding)); control.configureSerial(actor,1,true);
            var ids=Map.of("caller","cluster_serial_caller","g","cluster_serial_gateway","w","cluster_journal_worker",
                "p","cluster_journal_preparer","a","cluster_journal_coordinator","q","cluster_journal_adapter","b","library_service_b","c","library_service_c");
            for(var entry:ids.entrySet()) {
                var doc=document(entry.getValue()); var values=new LinkedHashMap<>(doc.flow().orElseThrow().initial().fields());
                if(entry.getKey().equals("q")) values.put("target",new LibraryTrackAddress(libraryPos.getX(),libraryPos.getY(),libraryPos.getZ()).value());
                if(entry.getKey().equals("a")) {
                    var rows=new ArrayList<>(((DataValue.ListValue)values.get("commands")).values());
                    var seek=new LinkedHashMap<>(((DataValue.RecordValue)rows.get(1)).fields());
                    seek.put("d",LibraryTrackAddress.from(libraryPos,Direction.EAST,shelf.getBlockPos()).value());
                    rows.set(1,new DataValue.RecordValue(seek)); values.put("commands",new DataValue.ListValue(rows));
                }
                var owner=host(computers.get(entry.getKey())); owner.installDocument(initial(doc,values),ComputerRuntime.MemoryPolicy.RESET);
                h.assertTrue(owner.flowSnapshot().orElseThrow().state().paused(),"Installation never starts a program");
            }
            computers.forEach((name,entity)->{var owner=host(entity); hardware.put(name,owner.hardware()); programs.put(name,owner.flowSnapshot().orElseThrow().program()); identities.put(name,owner.communicationIdentity().orElseThrow());});
            for (String role : ids.keySet()) {
                var limits = hardware.get(role).limits();
                h.assertTrue(limits.isa1a() && limits.isa1b(), role + " owns the parser and master type capabilities");
            }
            h.assertValueEqual(hardware.get("ordinary").limits().hardware().programUnits(),32L,"Ordinary member remains low-tier");
            System.out.println("CLUSTER_JOURNAL configured physical shell/internal disk/private B cable and external concentrated G; eight independent programs");
        }
        static XBusWire wire(String a,String ap,String b,String bp) { return new XBusWire(new XBusWire.Pin(a,a,ap),new XBusWire.Pin(b,b,bp),true); }
        void start() { startWhenWired(0); }
        void startWhenWired(int waited) {
            for (String name : List.of("b","g","caller")) {
                var connection = host(computers.get(name)).serialDevice().status("s0").connection();
                if (!connection.equals("ready")) {
                    h.assertTrue(waited < 200, name + ".s0 never ready: " + connection);
                    h.runAfterDelay(1, () -> startWhenWired(waited + 1));
                    return;
                }
            }
            for (var name : List.of("c","b","q","a","p","w","g","caller")) host(computers.get(name)).setPaused(false);
            poll();
        }
        void poll() { h.runAfterDelay(1,()->{
            ticks++;
            var drive=library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            if (scenario != Scenario.WRITE && !interrupted && drive.writing().isPresent()) {
                h.assertTrue(drive.writing().orElseThrow().remaining() > 0 && drive.result().isEmpty(),
                    "Interrupt only after physical write admission and before completion");
                h.assertTrue(ItemStack.matches(original, shelf.getItem(2)), "Admission does not alter the book");
                if (scenario == Scenario.REVOKE) configureAccess(LibraryServiceConfiguration.Access.CLOSED);
                else {
                    var other = media.put(new ItemStack(Items.WRITABLE_BOOK), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, previous));
                    replacement = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(other, "plant");
                    var otherVersion = LibraryMutation.Version.decode(media.stat(replacement,"reading").field("version"));
                    h.assertValueEqual(otherVersion.revision(), version.revision(), "Both entries have the same numeric revision");
                    h.assertTrue(!otherVersion.medium().equals(version.medium()), "Independent book UUIDs");
                    removed = shelf.removeItem(2,1);
                    shelf.setItem(2,replacement.copy());
                }
                interrupted = true;
                System.out.println("CLUSTER_JOURNAL " + scenario + " admitted write sequence=" + drive.sequence()
                    + " remaining=" + drive.writing().orElseThrow().remaining());
            }
            h.assertTrue(drive.sequence()<=5,"No repeated mechanical admissions");
            var value=media.read(shelf.getItem(2)).get("reading").value();
            if(!value.equals(previous)){changes++;previous=value;}
            h.assertTrue(changes<=1,"Book changes only once");
            for(var entry:computers.entrySet()) {
                var saved=host(entry.getValue()).flowSnapshot().orElseThrow();
                h.assertTrue(saved.state().status()!=FlowRuntime.Status.FAILED,entry.getKey()+" main fault "+saved.state().diagnostic());
                for(var child:saved.tasks().contexts()) h.assertTrue(child.status()!=FlowRuntime.Status.FAILED,entry.getKey()+" child fault "+child.diagnostic());
            }
            var caller=host(computers.get("caller")).flowSnapshot().orElseThrow();
            if(caller.state().status()!=FlowRuntime.Status.COMPLETE || computers.entrySet().stream()
                .filter(e->!Set.of("caller","g","ordinary").contains(e.getKey())).anyMatch(e->!host(e.getValue()).flowSnapshot().orElseThrow().tasks().contexts().isEmpty())) {
                if(ticks%500==0) {
                    System.out.println("CLUSTER_JOURNAL tick="+ticks+" mechanical="+drive.sequence()+" bookChanges="+changes);
                    computers.forEach((name,entity)->{var s=host(entity).flowSnapshot().orElseThrow();System.out.println("CLUSTER_JOURNAL "+name+" "+s.state().status()+" work="+s.state().work()+" children="+s.tasks().contexts().stream().map(t->t.status()+"/"+t.assembly().map(a->a.pc()).orElse(-1)).toList());});
                }
                poll(); return;
            }
            var transport=(DataValue.RecordValue)caller.state().locals().field("outcome");
            h.assertValueEqual(transport.field("ok"),new DataValue.BooleanValue(true),"Correlated external reply consumed");
            var frame=(DataValue.RecordValue)transport.field("v");
            var business=(DataValue.RecordValue)frame.field("v");
            assertMembersUnchanged();
            if (scenario != Scenario.WRITE) {
                String expected = scenario == Scenario.REVOKE ? "denied" : "medium_changed";
                h.assertTrue(interrupted, "Fault happened during the actual admitted write");
                h.assertValueEqual(business.field("ok"), new DataValue.BooleanValue(false), "No invented successful write");
                h.assertValueEqual(((DataValue.RecordValue)business.field("value")).field("code"),
                    new DataValue.TextValue(expected), "Public programs return the specific mechanical failure");
                h.assertValueEqual(changes, 0, "A rejected write changes neither entry");
                if (scenario == Scenario.SWAP) {
                    h.assertTrue(ItemStack.matches(original,removed), "Removed book preserves all original components");
                    h.assertTrue(ItemStack.matches(replacement,shelf.removeItem(2,1)), "Replacement receives neither data nor a receipt");
                    shelf.setItem(2,removed);
                }
                h.assertTrue(ItemStack.matches(original, shelf.getItem(2)), "All book components and pages remain unchanged");
                h.assertValueEqual(drive.sequence(), 4L, "Seek/select/stat/write only; failure prevents readback");
                h.assertValueEqual(drive.result().orElseThrow().code(), expected, "Failure remains until an explicit ACK");
                if (scenario == Scenario.REVOKE) configureAccess(LibraryServiceConfiguration.Access.READ_WRITE);
                h.runAfterDelay(40, () -> {
                    h.assertValueEqual(host(computers.get("caller")).flowSnapshot().orElseThrow(), caller,
                        "Restoring the original setup cannot change the settled caller or replay its request");
                    h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), drive,
                        "Restoration cannot discard or retry the retained mechanical failure");
                    h.assertTrue(ItemStack.matches(original, shelf.getItem(2)), "Restoration is not a new write request");
                    assertMembersUnchanged();
                    System.out.println("CLUSTER_JOURNAL " + expected + " retained after restoration ticks=" + ticks
                        + " mechanical=" + drive.sequence() + " bookChanges=" + changes);
                    h.succeed();
                });
                return;
            }
            h.assertValueEqual(business.field("ok"),new DataValue.BooleanValue(true),"Physical Library business completed");
            var readback=(DataValue.RecordValue)business.field("value");
            h.assertValueEqual(readback.field("value"),new DataValue.IntegerValue(7),"Actual physical readback returns text length7");
            h.assertValueEqual(value,new DataValue.IntegerValue(7),"Real book value, not an emulated response");
            h.assertValueEqual(changes,1,"One committed value change"); h.assertValueEqual(drive.sequence(),5L,"Seek/select/stat/write/read exactly once");
            h.assertTrue(drive.result().isEmpty(),"Final explicit ACK, not silent consumption");
            var after=LibraryMutation.Version.decode(media.stat(shelf.getItem(2),"reading").field("version"));
            h.assertValueEqual(after.medium(),version.medium(),"Same book identity");
            h.assertValueEqual(after.revision(),version.revision()+1,"One version increment");
            h.assertValueEqual(shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT),original.get(DataComponents.WRITABLE_BOOK_CONTENT),"Human pages unchanged");
            h.assertValueEqual(shelf.getItem(2).get(DataComponents.CUSTOM_NAME),original.get(DataComponents.CUSTOM_NAME),"Book name unchanged");
            System.out.println("CLUSTER_JOURNAL completed ticks="+ticks+" mechanical="+drive.sequence()+" valueChanges="+changes+" callerWork="+caller.state().work());
            h.succeed();
        }); }

        void configureAccess(LibraryServiceConfiguration.Access access) {
            var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
            long revision = ((DataValue.IntegerValue)service.status().field("revision")).value();
            h.assertValueEqual(service.configure(revision,
                new LibraryServiceConfiguration(Optional.empty(), Map.of("plant", access))), "configured", "Explicit volume grant change");
        }

        void assertMembersUnchanged() {
            computers.forEach((name,entity)->{var owner=host(entity);h.assertValueEqual(owner.hardware(),hardware.get(name),name+" no hardware pooling");h.assertValueEqual(owner.flowSnapshot().orElseThrow().program(),programs.get(name),name+" installed program unchanged");h.assertValueEqual(owner.communicationIdentity().orElseThrow(),identities.get(name),name+" identity unchanged");});
            var ordinary=host(computers.get("ordinary")).flowSnapshot().orElseThrow();
            h.assertTrue(ordinary.state().paused(),"Unrouted member never assists");h.assertValueEqual(ordinary.state().work(),0L,"No hidden ordinary CPU work");
            h.assertTrue(controller.enclosure().enclosure().isPresent(),"Real shell remains formed with an interior Library");
            h.assertTrue(host(computers.get("g")).serialDevice().status("s0").receive().frames()==0,"Private Library traffic never leaked onto external gateway RX");
        }
    }
}
