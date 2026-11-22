package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Existing native read/Computer fixture convention; no test-side selector or result injection. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingMaterialSelectionGameTests {
    private static DataValue.RecordValue record(Object... pairs) {
        var fields=new LinkedHashMap<String,DataValue>();
        for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],pairs[i+1] instanceof DataValue v?v:new DataValue.IntegerValue(((Number)pairs[i+1]).longValue()));
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.TextValue text(String s){return new DataValue.TextValue(s);}
    private static ProgramDocument document(String role) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> resources=id->
            BiocomputingMaterialSelectionGameTests.class.getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath());
        var entry=ProgramExampleCatalog.load(resources).stream().filter(e->e.id().equals("material_selection_"+role)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry,resources,CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    @GameTest(template="empty",timeoutTicks=12000)
    public static void realProtectedInventoryTagsFeedThePublicTwoComputerSelectionWithoutInventoryWrites(GameTestHelper h) {
        var level=h.getLevel();var min=h.absolutePos(new BlockPos(1,1,1));var max=min.offset(6,3,4);
        var controllerPos=min.offset(6,1,2);var computers=new ComputerBlockEntity[2];
        var members=new LinkedHashMap<String,ClusterConfiguration.Member>();
        for(int i=0;i<2;i++) {
            String role=i==0?"coordinator":"service";computers[i]=computer(h,min.offset(1+i*2,1,1),VillagerProfession.CLERIC,5);
            var empty=new ProgramWorkspace();empty.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(),List.of()).withAssembly(),record())));
            host(computers[i]).installDocument(empty.snapshot(),ComputerRuntime.MemoryPolicy.RESET);
            host(computers[i]).configureEndpoint(new EndpointConfiguration(role,true));
            members.put(role,new ClusterConfiguration.Member(host(computers[i]).communicationIdentity().orElseThrow(),computers[i].getBlockPos()));
        }
        for(var p:BlockPos.betweenClosed(min,max))if(p.getX()==min.getX()||p.getX()==max.getX()||p.getY()==min.getY()||p.getY()==max.getY()||p.getZ()==min.getZ()||p.getZ()==max.getZ())
            level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
        level.setBlock(controllerPos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
        var controller=(ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"material-selection"));actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of("selection",
            new XBusWire(new XBusWire.Pin("coordinator","coordinator","x0"),new XBusWire.Pin("service","service","x0"),true))),actor),"configured","Explicit public XBus wiring");
        var target=controllerPos.east(3);level.setBlock(target,Blocks.BARREL.defaultBlockState(),3);
        var barrel=(BarrelBlockEntity)level.getBlockEntity(target);barrel.setItem(0,new ItemStack(Items.OAK_LOG,11));barrel.setItem(1,new ItemStack(Items.BIRCH_LOG,13));
        var originals=List.of(barrel.getItem(0).copy(),barrel.getItem(1).copy());actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(target));
        var metadata=BlockSnapshotReader.readComponents(level,target,Direction.WEST,actor,new ReadLimits(4096,32768,65536),Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        var rows=(DataValue.ListValue)metadata.field(BuiltinReadComponents.INVENTORY_ITEM_TAGS);
        var oak=(DataValue.RecordValue)rows.values().get(0);var birch=(DataValue.RecordValue)rows.values().get(1);
        for(var descriptor:List.of(oak,birch))h.assertTrue(((DataValue.ListValue)descriptor.field("tags")).values().contains(text("minecraft:logs")),"Actual loaded registry tags, not fixture-synthesized membership");
        var patch=new DataValue.NbtValue(10,record("raw",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE))));
        var tagRow=record("item",DataValue.NullValue.INSTANCE,"tag",text("minecraft:logs"),"count",2,"components",patch);
        var materials=new DataValue.ListValue(List.of(tagRow,tagRow));var choices=new DataValue.ListValue(List.of(oak,birch));
        for(int i=0;i<2;i++) {
            var doc=document(i==0?"coordinator":"service");var body=doc.flow().orElseThrow();
            if(i==0){var initial=new LinkedHashMap<>(body.initial().fields());initial.put("materials",materials);initial.put("choices",choices);doc=new ProgramDocument(doc.archive(),doc.positions(),doc.terminal(),Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(initial),body.canvas(),body.inline(),body.execution())));}
            host(computers[i]).installDocument(doc,ComputerRuntime.MemoryPolicy.RESET);
            h.assertValueEqual(host(computers[i]).hardware().limits().hardware(),new ResourceLimits(256,65536,8),"Unmodified default per-Computer hardware");
            h.assertTrue(host(computers[i]).flowSnapshot().orElseThrow().state().paused(),"Install does not start automatically");
        }
        host(computers[1]).setPaused(false);
        h.runAfterDelay(40,()->{
            var waiting=host(computers[1]).flowSnapshot().orElseThrow().state();
            h.assertTrue(waiting.status()!=FlowRuntime.Status.FAILED&&waiting.status()!=FlowRuntime.Status.WAITING_READ,"Service polls safely before paused coordinator starts");
            h.assertValueEqual(host(computers[0]).flowSnapshot().orElseThrow().state().work(),0L,"Coordinator remains explicitly paused");
            host(computers[0]).setPaused(false);
            class Poll { int ticks;
                void next(){h.runAfterDelay(1,()->{
                    for(var computer:computers)h.assertTrue(host(computer).flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.FAILED,"Public ISA fault: "+host(computer).flowSnapshot().orElseThrow().state().diagnostic());
                    var state=host(computers[0]).flowSnapshot().orElseThrow().state();
                    if(state.status()!=FlowRuntime.Status.COMPLETE){h.assertTrue(++ticks<11000,"Bounded fixture wait");next();return;}
                    var result=(DataValue.RecordValue)state.locals().field("result");h.assertValueEqual(result.field("ok"),new DataValue.BooleanValue(true),"Public business selection succeeded");
                    h.assertValueEqual(result.field("code"),text("ok"),"Business code, not merely transport completion");
                    h.assertValueEqual(result.field("items"),new DataValue.ListValue(List.of(record("item",text("minecraft:oak_log"),"count",2,"components",patch),record("item",text("minecraft:birch_log"),"count",2,"components",patch))),"Independent explicit choices retain order, duplicates, count and native components");
                    h.assertValueEqual(state.locals().field("materials"),materials,"Original authored materials immutable");h.assertValueEqual(state.locals().field("choices"),choices,"Captured reader choices immutable");
                    for(int i=0;i<2;i++)h.assertTrue(ItemStack.matches(originals.get(i),barrel.getItem(i)),"Actual source inventory unchanged");
                    for(var computer:computers)host(computer).setPaused(true);h.succeed();
                });}
            }
            new Poll().next();
        });
    }
}
