package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Registered world path: production Cluster scheduler/history, finite serial, actual book and grants. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingAuthoredLibraryStartupGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void publicServicesWaitForStaggeredStartsBeforeFirstProductionHandoff(GameTestHelper h) {new Fixture(h,0).start();}

    static ComputerBehaviour host(ComputerBlockEntity b) {return Objects.requireNonNull(b.getBehaviour(ComputerBehaviour.TYPE));}
    static DataValue.RecordValue record(Object... pairs) {var fields=new LinkedHashMap<String,DataValue>();for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],pairs[i+1] instanceof DataValue v?v:new DataValue.IntegerValue(((Number)pairs[i+1]).longValue()));return new DataValue.RecordValue(fields);}
    static ProgramDocument document(String role) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> open=id->BiocomputingAuthoredLibraryStartupGameTests.class.getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath());
        var entry=ProgramExampleCatalog.load(open).stream().filter(e->e.id().equals("library_read_"+role)).findFirst().orElseThrow();return ProgramExampleCatalog.preview(entry,open,CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    static ComputerBlockEntity computer(GameTestHelper h,BlockPos pos,VillagerProfession profession,int rank) {
        var l=h.getLevel();l.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);var lower=CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF,DoubleBlockHalf.LOWER);l.setBlock(pos,lower,3);l.setBlock(pos.above(),lower.setValue(ComputerBlock.HALF,DoubleBlockHalf.UPPER),3);
        var b=(ComputerBlockEntity)l.getBlockEntity(pos);var villager=Objects.requireNonNull(EntityType.VILLAGER.create(l));villager.setBaby(false);villager.setVillagerData(new VillagerData(VillagerType.PLAINS,profession,rank));var box=new ItemStack(CBItems.CARDBOARD_BOX.get());h.assertTrue(CapturedEntityBoxHelper.captureEntity(box,villager),"Capture actual default resident");var player=new FakePlayer(l,new GameProfile(UUID.randomUUID(),"read-resident"));player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));player.setItemInHand(InteractionHand.MAIN_HAND,box);h.assertValueEqual(b.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Normal resident install");return b;
    }
    static BlockEntity reconstruct(ServerLevel level,BlockEntity before) {var pos=before.getBlockPos();var state=before.getBlockState();var tag=before.saveWithFullMetadata(level.registryAccess());level.removeBlockEntity(pos);var next=Objects.requireNonNull(BlockEntity.loadStatic(pos,state,tag,level.registryAccess()));level.setBlockEntity(next);return next;}
    static final class Fixture {
        final GameTestHelper h;final ServerLevel level;final int failure;
        final ComputerBlockEntity[] blocks=new ComputerBlockEntity[4];
        final BlockPos controllerPos,libraryPos;final ClusterControllerBlockEntity controller;final FakePlayer actor;
        LibraryBlockEntity library;final ChiseledBookShelfBlockEntity shelf;final LibraryBookMedia media;final ItemStack original;
        final List<HardwareSelection> hardware=new ArrayList<>();
        final List<FlowProgram> programs=new ArrayList<>();
        boolean reconstructed,revoked,retainedRead;long maximumSequence;int steps;ItemStack carried=ItemStack.EMPTY;
        Fixture(GameTestHelper h,int failure) {
            this.h=h;this.level=h.getLevel();this.failure=failure;
            var min=h.absolutePos(new BlockPos(1,1,1));var max=min.offset(6,4,6);controllerPos=min.offset(6,1,2);libraryPos=controllerPos.east(4);
            blocks[0]=computer(h,min.offset(1,1,1),VillagerProfession.MASON,5);blocks[1]=computer(h,controllerPos.west(),VillagerProfession.LIBRARIAN,5);blocks[2]=computer(h,min.offset(1,1,4),VillagerProfession.ARMORER,4);blocks[3]=computer(h,min.offset(4,1,5),VillagerProfession.NONE,1);
            for(var p:BlockPos.betweenClosed(min,max))if(p.getX()==min.getX()||p.getX()==max.getX()||p.getY()==min.getY()||p.getY()==max.getY()||p.getZ()==min.getZ()||p.getZ()==max.getZ())level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
            level.setBlock(controllerPos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);controller=(ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"authored-read"));actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
            var members=new LinkedHashMap<String,ClusterConfiguration.Member>();var names=List.of("a","b","c","ordinary");for(int i=0;i<4;i++){host(blocks[i]).installFlow(new FlowProgram(Map.of(),List.of()),new DataValue.RecordValue(Map.of()));host(blocks[i]).configureEndpoint(new EndpointConfiguration(names.get(i),true));members.put(names.get(i),new ClusterConfiguration.Member(host(blocks[i]).communicationIdentity().orElseThrow(),blocks[i].getBlockPos()));}
            var ab=new XBusWire(new XBusWire.Pin("a","a","x0"),new XBusWire.Pin("b","b","x0"),true);var bc=new XBusWire(new XBusWire.Pin("b","b","x1"),new XBusWire.Pin("c","c","x0"),true);h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of("ab",ab,"bc",bc)),actor),"configured","Explicit production XBus declarations");
            var b=host(blocks[1]);var gateway=SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,blocks[1]),actor,"s0","read");b.configureSerialGateway("s0",Optional.of(gateway));b.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));for(int i=1;i<=3;i++)level.setBlock(controllerPos.east(i),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
            var state=CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING,Direction.EAST);level.setBlock(libraryPos.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(libraryPos,state.setValue(LibraryCoreBlock.HALF,DoubleBlockHalf.LOWER),3);level.setBlock(libraryPos.above(),state.setValue(LibraryCoreBlock.HALF,DoubleBlockHalf.UPPER),3);library=(LibraryBlockEntity)level.getBlockEntity(libraryPos);library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("authored-read"),0,UUID.randomUUID(),0));library.initialize();level.setBlock(libraryPos.north(),Blocks.CHISELED_BOOKSHELF.defaultBlockState(),3);shelf=(ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
            media=new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),CBConfigs.SERVER.biocomputing.current(true).archive());var book=media.put(new ItemStack(Items.WRITABLE_BOOK),"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.IntegerValue(43)));book=new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book,"ReadBook");shelf.setItem(2,book.copy());original=book.copy();var service=library.getBehaviour(LibraryServiceBehaviour.TYPE);service.configure(0,new LibraryServiceConfiguration(Optional.empty(),Map.of("ReadBook",LibraryServiceConfiguration.Access.READ_ONLY)));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(libraryPos));var control=library.getBehaviour(LibraryControlBehaviour.TYPE);var selected=control.discover(actor).stream().filter(candidate->candidate.member().equals(blocks[1].getBlockPos())&&candidate.controller().isPresent()).findFirst().orElseThrow();control.configure(actor,0,Optional.of(selected));control.configureSerial(actor,1,true);
            var aDoc=document("a");var body=aDoc.flow().orElseThrow();var values=new LinkedHashMap<>(body.initial().fields());values.put("target",new LibraryTrackAddress(libraryPos.getX(),libraryPos.getY(),libraryPos.getZ()).value());var rows=new ArrayList<>(((DataValue.ListValue)values.get("commands")).values());var seek=new LinkedHashMap<>(((DataValue.RecordValue)rows.get(1)).fields());seek.put("d",LibraryTrackAddress.from(libraryPos,Direction.EAST,libraryPos.north()).value());rows.set(1,new DataValue.RecordValue(seek));values.put("commands",new DataValue.ListValue(rows));aDoc=new ProgramDocument(aDoc.archive(),aDoc.positions(),aDoc.terminal(),Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(values),body.canvas(),body.inline(),body.execution())));host(blocks[0]).installDocument(aDoc,ComputerRuntime.MemoryPolicy.RESET);host(blocks[1]).installDocument(document("b"),ComputerRuntime.MemoryPolicy.RESET);host(blocks[2]).installDocument(document("c"),ComputerRuntime.MemoryPolicy.RESET);
            for(int i=0;i<4;i++)hardware.add(host(blocks[i]).hardware());for(int i=0;i<3;i++)programs.add(host(blocks[i]).flowSnapshot().orElseThrow().program());
            h.assertValueEqual(hardware.get(0).limits().hardware(),new ResourceLimits(256,65536,8),"A individual default");h.assertValueEqual(hardware.get(1).limits().hardware(),new ResourceLimits(256,65536,8),"B individual default");h.assertValueEqual(hardware.get(2).limits().hardware(),new ResourceLimits(192,49152,6),"C lower individual default");h.assertValueEqual(hardware.get(3).limits().hardware().programUnits(),32L,"Ordinary fourth member stays low capacity");
            if(failure==1)carried=shelf.removeItem(2,1);
        }
        void start() {host(blocks[2]).setPaused(false);pollStartup();}
        void pollStartup() {h.runAfterDelay(1,()->{
            steps++;
            for(int i=0;i<3;i++) {
                var owner=host(blocks[i]);var s=owner.flowSnapshot().orElseThrow().state();
                h.assertTrue(s.status()!=FlowRuntime.Status.FAILED,"Staggered native owner failed: "+s.diagnostic());
                h.assertValueEqual(owner.hardware(),hardware.get(i),"Independent hardware unchanged");
                h.assertValueEqual(owner.flowSnapshot().orElseThrow().program(),programs.get(i),"Public installed program unchanged");
            }
            h.assertTrue(host(blocks[3]).flowSnapshot().orElseThrow().state().paused(),"Fourth member remains paused");
            if(steps<=200) {
                var c=host(blocks[2]);h.assertValueEqual(c.sampleFlowSignals().get("x0"),0,"Actual connected STATUS is zero without upstream sender");
                h.assertTrue(c.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_READ,"C does not issue an unsolicited read");
                h.assertTrue(host(blocks[0]).flowSnapshot().orElseThrow().state().paused(),"A waits for player start");
                h.assertValueEqual(host(blocks[0]).flowSnapshot().orElseThrow().state().work(),0L,"Paused A executes no work");
                h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().sequence(),0L,"No mechanical command during idle startup");
                h.assertValueEqual(host(blocks[1]).serialDevice().status("s0").transmit(),"idle","No idle serial output");
                if(steps==100) {h.assertTrue(c.flowSnapshot().orElseThrow().state().work()>10,"C paid repeated probe/yield cycles");host(blocks[1]).setPaused(false);}
                if(steps>100) {
                    h.assertValueEqual(host(blocks[1]).sampleFlowSignals().get("x0"),0,"B actual STATUS remains zero while A is paused");
                    h.assertTrue(host(blocks[1]).flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_READ,"B does not issue an unsolicited read");
                }
                if(steps==200) {h.assertTrue(host(blocks[1]).flowSnapshot().orElseThrow().state().work()>10,"B paid repeated probe/yield cycles");host(blocks[0]).setPaused(false);}
            } else {
                var b=host(blocks[1]).flowSnapshot().orElseThrow();
                if(b.state().locals().fields().containsKey("packet")) {
                    h.assertValueEqual(b.state().locals().field("bus_status"),new DataValue.IntegerValue(15),"Actual sender sample admitted first READ");
                    h.assertTrue(!b.receipts().isEmpty(),"Actual production handoff publishes a receipt");
                    h.assertValueEqual(XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()).getInt("Version"),7,"Actual authored handoff history");
                    h.assertTrue(ItemStack.matches(original,shelf.getItem(2)),"Physical book unchanged by startup");
                    h.assertTrue(controller.enclosure().enclosure().isPresent(),"Closed registered Cluster used");
                    System.out.println("STARTUP_NATIVE C-only=100ticks B-with-A-paused=100ticks firstABHandoff="+steps+" sample="+b.state().locals().field("bus_status")+" receiptCount="+b.receipts().size()+" work="+host(blocks[0]).flowSnapshot().orElseThrow().state().work()+","+b.state().work()+","+host(blocks[2]).flowSnapshot().orElseThrow().state().work());h.succeed();return;
                }
            }
            pollStartup();
        });}
    }
}
