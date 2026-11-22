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

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.computer;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.host;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.reconstruct;

/** Registered world path: production Cluster scheduler/history, finite serial, actual book and grants. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingAuthoredLibraryReadGameTests {
    @GameTest(template="empty",timeoutTicks=20000)
    public static void publicAuthoredReadSurvivesActualOwnerLibraryAndHistoryReconstruction(GameTestHelper h) {new Fixture(h,0).start();}
    @GameTest(template="empty",timeoutTicks=20000)
    public static void publicAuthoredReadReturnsActualMissingBookFailureWithoutAckOrRetry(GameTestHelper h) {new Fixture(h,1).start();}
    @GameTest(template="empty",timeoutTicks=20000)
    public static void publicAuthoredReadRetainsDeniedResultWhenGrantIsRevokedAfterAdmission(GameTestHelper h) {new Fixture(h,2).start();}

    public static boolean readyToRebuildRead(FlowBlockHost.Saved b, FlowBlockHost.Saved c, LibraryReadHead.State drive) {
        return drive.result().filter(result->result.code().equals("read")).isPresent() && atServiceYield(b) && atServiceYield(c);
    }
    private static boolean atServiceYield(FlowBlockHost.Saved owner) {
        var state=owner.state();
        // The Flow cursor projects an authored body to its definition; ISA calls are not in state.calls().
        return state.status()==FlowRuntime.Status.SLEEPING && state.entry().isEmpty() && state.calls().frames().isEmpty()
            && state.assembly().map(registers->registers.calls().map(calls->calls.frames().isEmpty()).orElse(true)).orElse(true)
            && owner.program().steps().get(state.cursor()) instanceof FlowProgram.Sleep sleep && sleep.sourceId().equals("service_yield");
    }

    static DataValue.RecordValue record(Object... pairs) {var fields=new LinkedHashMap<String,DataValue>();for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],pairs[i+1] instanceof DataValue v?v:new DataValue.IntegerValue(((Number)pairs[i+1]).longValue()));return new DataValue.RecordValue(fields);}
    static ProgramDocument document(String role) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> open=id->BiocomputingAuthoredLibraryReadGameTests.class.getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath());
        var entry=ProgramExampleCatalog.load(open).stream().filter(e->e.id().equals("library_read_"+role)).findFirst().orElseThrow();return ProgramExampleCatalog.preview(entry,open,CBConfigs.SERVER.biocomputing.current(true).archive());
    }
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
        void start() {for(int i=2;i>=0;i--)host(blocks[i]).setPaused(false);poll();}
        void poll() {h.runAfterDelay(1,()->{
            steps++;var drive=library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();maximumSequence=Math.max(maximumSequence,drive.sequence());h.assertTrue(maximumSequence<=3,"No duplicated mechanical operation");
            for(int i=0;i<3;i++){var owner=host(blocks[i]);h.assertTrue(owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.FAILED,"Native owner failed: "+owner.flowSnapshot().orElseThrow().state().diagnostic());h.assertValueEqual(owner.hardware(),hardware.get(i),"No pooled/upgraded hardware");h.assertValueEqual(owner.flowSnapshot().orElseThrow().program(),programs.get(i),"Public installed source unchanged");}
            h.assertValueEqual(host(blocks[3]).hardware(),hardware.get(3),"Ordinary member unchanged");h.assertTrue(host(blocks[3]).flowSnapshot().orElseThrow().state().paused(),"No hidden fourth-member work");
            if(failure==2&&!revoked&&drive.pending().filter(p->p.action()==LibraryReadHead.Action.READ).isPresent()) {h.assertTrue(drive.pending().orElseThrow().remainingTicks()>0,"Revoke only actual admitted read");var service=library.getBehaviour(LibraryServiceBehaviour.TYPE);h.assertValueEqual(service.configure(((DataValue.IntegerValue)service.status().field("revision")).value(),new LibraryServiceConfiguration(Optional.empty(),Map.of())),"configured","Actual public grant revocation");revoked=true;}
            var a=host(blocks[0]).flowSnapshot().orElseThrow().state();var value=a.locals().fields().get("read_result");
            if(failure==0&&value instanceof DataValue.RecordValue result) {h.assertValueEqual(result.field("value"),new DataValue.IntegerValue(43),"Actual book value retained before acknowledgement");if(drive.result().filter(r->r.code().equals("read")).isPresent())retainedRead=true;
                if(!reconstructed&&retainedRead&&readyToRebuildRead(host(blocks[1]).flowSnapshot().orElseThrow(),host(blocks[2]).flowSnapshot().orElseThrow(),drive)&&host(blocks[1]).serialDevice().status("s0").receive().frames()==0&&!Set.of("queued","transmitting").contains(host(blocks[1]).serialDevice().status("s0").transmit())) {rebuild(drive);return;}
            }
            if(a.status()!=FlowRuntime.Status.COMPLETE){if(steps%1000==0)System.out.println("AUTHORED_NATIVE progress failure="+failure+" tick="+steps+" seq="+drive.sequence()+" A="+a.status()+" B="+host(blocks[1]).flowSnapshot().orElseThrow().state().status()+" C="+host(blocks[2]).flowSnapshot().orElseThrow().state().status());poll();return;}
            h.assertTrue(ItemStack.matches(original,failure==1?carried:shelf.getItem(2)),"Physical book/components remain unchanged");h.assertTrue(controller.enclosure().enclosure().isPresent(),"Closed casing/controller/internal-air structure");h.assertTrue(library.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(),"No legacy direct service path");
            if(failure==0){h.assertTrue(reconstructed&&retainedRead,"Actual owner/library/history reconstruction and pre-ack retention occurred");h.assertValueEqual(a.locals().field("success"),new DataValue.BooleanValue(true),"Actual A/B/C world read completes");h.assertValueEqual(drive.sequence(),3L,"Exactly seek/select/read accepted");h.assertTrue(drive.result().isEmpty(),"Final explicit ACK clears retained read");}
            else {h.assertValueEqual(a.locals().field("success"),new DataValue.BooleanValue(false),"Business failure, never read success");h.assertValueEqual(a.locals().field("read_result"),DataValue.NullValue.INSTANCE,"No invented read value");var error=(DataValue.RecordValue)a.locals().field("error");h.assertValueEqual(error.field("code"),new DataValue.TextValue(failure==1?"missing_medium":"denied"),"Actual device failure");if(failure==2){h.assertTrue(revoked,"Read admission was observed before revocation");h.assertValueEqual(drive.result().orElseThrow().code(),"denied","Retained mechanical failure");}else h.assertTrue(drive.result().isEmpty(),"Rejected read has no admitted result");}
            System.out.println("AUTHORED_NATIVE completed failure="+failure+" steps="+steps+" sequence="+drive.sequence()+" reconstructed="+reconstructed+" retainedBeforeAck="+retainedRead+" revoked="+revoked+" historyVersion="+XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()).getInt("Version"));
            var completed=drive;var outcomes=List.of(host(blocks[0]).flowSnapshot(),host(blocks[1]).flowSnapshot(),host(blocks[2]).flowSnapshot());h.runAfterDelay(40,()->{h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(),completed,"No implicit retry/ACK after completion");h.assertValueEqual(host(blocks[0]).flowSnapshot(),outcomes.getFirst(),"Completed A never replays");h.succeed();});
        });}
        void rebuild(LibraryReadHead.State retained) {
            var before=new ArrayList<FlowBlockHost.Saved>();for(int i=0;i<3;i++){host(blocks[i]).setPaused(true);before.add(host(blocks[i]).flowSnapshot().orElseThrow());}
            var history=XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());h.assertValueEqual(history.getInt("Version"),7,"Authored world history uses schema7");
            for(int i=0;i<3;i++)blocks[i]=(ComputerBlockEntity)reconstruct(level,blocks[i]);library=(LibraryBlockEntity)reconstruct(level,library);level.getDataStorage().set(XBusHistorySavedData.DATA_NAME,XBusHistorySavedData.load(history,level.registryAccess()));h.assertValueEqual(history,XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),"Real SavedData reconstruction retains all identities");
            reconstructed=true;h.runAfterDelay(30,()->{for(int i=0;i<3;i++){host(blocks[i]).document();h.assertValueEqual(host(blocks[i]).flowSnapshot().orElseThrow(),before.get(i),"Reloaded owner continues exactly without reinstall");host(blocks[i]).setPaused(false);}h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(),retained,"Mechanical read retained across real Library BE reload");poll();});
        }
    }
}
