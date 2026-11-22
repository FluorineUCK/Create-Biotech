package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Two physical ROM pages -> public reader -> public query program -> published player terminal. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingRecipeBrowserGameTests {
    @GameTest(template="empty",timeoutTicks=14000)
    public static void searchTwoBookPagesFromTheInstalledTerminal(GameTestHelper h){new Station(h,false).waitForCable(0);}

    @GameTest(batch="checkout",template="empty",timeoutTicks=14000)
    public static void chooseAndCancelAPhysicalBookOrder(GameTestHelper h){new Station(h,true).waitForCable(0);}

    static ProgramDocument doc(String name){
        java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> resources=id->
            BiocomputingRecipeBrowserGameTests.class.getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath());
        return ProgramExampleCatalog.preview(ProgramExampleCatalog.load(resources).stream().filter(e->name.equals(e.id())).findFirst().orElseThrow(),
            resources,CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    static DataValue.RecordValue patch(DataValue v,String key,DataValue replacement){
        var fields=new LinkedHashMap<>(((DataValue.RecordValue)v).fields());fields.put(key,replacement);return new DataValue.RecordValue(fields);
    }
    static XBusWire wire(String a,String ap,String b,String bp){
        return new XBusWire(new XBusWire.Pin(a,a,ap),new XBusWire.Pin(b,b,bp),true);
    }
    static String page(String product){
        return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":2}],"
            +"\"outputs\":[{\"item\":\"minecraft:"+product+"\",\"count\":1}],\"address\":\"press\"}";
    }
    static final class Station {
        final GameTestHelper h;
        final ComputerBlockEntity[] members;
        final boolean checkout;
        final LibraryBlockEntity library;
        final ChiseledBookShelfBlockEntity shelf;
        final ItemStack original;
        final UUID nonce=UUID.randomUUID();
        final ComputerControlSession menu=new ComputerControlSession(nonce);
        final DataValue.RecordValue empty=new DataValue.RecordValue(Map.of());
        long intent, deskIntent;
        final UUID deskNonce=UUID.randomUUID();
        final ComputerControlSession deskMenu=new ComputerControlSession(deskNonce);
        int stage;
        String previous="";
        Station(GameTestHelper h,boolean checkout){this(h,checkout,4);}
        Station(GameTestHelper h,boolean checkout,int depth){
            this.checkout=checkout;members=new ComputerBlockEntity[checkout?6:5];
            this.h=h;var level=h.getLevel();var min=h.absolutePos(new BlockPos(1,checkout?12:1,1));var max=min.offset(6,3,depth);
            var cp=min.offset(6,1,2);var lp=cp.east(4);
            var roles=List.of("rom","wire","receive","query","terminal","desk");var bindings=new LinkedHashMap<String,ClusterConfiguration.Member>();
            for(int n=0;n<members.length;n++){
                var pos=min.offset(1+2*(n%3),1,n<3?1:3);members[n]=computer(h,pos,VillagerProfession.CLERIC,5);
                var workspace=new ProgramWorkspace();
                workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(),List.of()).withAssembly(),empty)));
                var machine=host(members[n]);machine.installDocument(workspace.snapshot(),ComputerRuntime.MemoryPolicy.RESET);
                String name=roles.get(n);machine.configureEndpoint(new EndpointConfiguration(name,true));
                bindings.put(name,new ClusterConfiguration.Member(machine.communicationIdentity().orElseThrow(),pos));
            }
            for(var p:BlockPos.betweenClosed(min,max))if(p.getX()==min.getX()||p.getX()==max.getX()||
                p.getY()==min.getY()||p.getY()==max.getY()||p.getZ()==min.getZ()||p.getZ()==max.getZ())
                level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
            level.setBlock(cp,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
            var controller=(ClusterControllerBlockEntity)level.getBlockEntity(cp);
            var actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"recipe-browser"));actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(cp.east()));
            var wires=new LinkedHashMap<>(Map.of(
                "rom_wire",wire("rom","x0","wire","x0"),"wire_rx",wire("wire","x1","receive","x0"),
                "rom_query",wire("rom","x1","query","x0"),"query_ui",wire("query","x1","terminal","x0")));
            if(checkout)wires.put("proposal",wire("terminal","x1","desk","x0"));
            h.assertValueEqual(controller.configure(new ClusterConfiguration(bindings,Map.of(),Map.of(),wires),actor),"configured","Configured pins");
            var port=host(members[1]);
            port.configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,members[1]),actor,"s0","rom")));
            port.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
            for(int x=1;x<4;x++)level.setBlock(cp.east(x),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
            var core=CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING,Direction.EAST);
            level.setBlock(lp.below(),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(lp,core.setValue(LibraryCoreBlock.HALF,DoubleBlockHalf.LOWER),3);
            level.setBlock(lp.above(),core.setValue(LibraryCoreBlock.HALF,DoubleBlockHalf.UPPER),3);
            library=(LibraryBlockEntity)level.getBlockEntity(lp);
            library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("recipe-browser"),0,UUID.randomUUID(),0));library.initialize();
            level.setBlock(lp.north(),Blocks.CHISELED_BOOKSHELF.defaultBlockState(),3);shelf=(ChiseledBookShelfBlockEntity)level.getBlockEntity(lp.north());
            var book=new ItemStack(Items.WRITABLE_BOOK);book.set(DataComponents.WRITABLE_BOOK_CONTENT,
                new WritableBookContent(List.of(Filterable.passThrough(page("paper")),Filterable.passThrough(page("brick")))));
            var media=new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),CBConfigs.SERVER.biocomputing.current(true).archive());
            book=media.put(book,"note",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.TextValue("preserve")));
            book=new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book,"Recipes");
            original=book.copy();shelf.setItem(0,book);
            h.assertValueEqual(library.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0,new LibraryServiceConfiguration(Optional.empty(),
                Map.of("Recipes",LibraryServiceConfiguration.Access.READ_ONLY))),"configured","Read-only volume");
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(lp));
            var access=library.getBehaviour(LibraryControlBehaviour.TYPE);
            var delegate=access.discover(actor).stream().filter(c->c.member().equals(members[1].getBlockPos())&&c.controller().isPresent()).findFirst().orElseThrow();
            h.assertValueEqual(access.configure(actor,0,Optional.of(delegate)),"configured","Explicit reader");
            h.assertValueEqual(access.configureSerial(actor,1,true),"configured","Library serial port");

            var rom=new ProgramWorkspace(doc("recipe_page_a"));var body=rom.flow().orElseThrow();
            var initial=patch(body.initial(),"target",new DataValue.RecordValue(Map.of("x",num(lp.getX()),"y",num(lp.getY()),"z",num(lp.getZ()))));
            var commands=new ArrayList<>(((DataValue.ListValue)initial.field("commands")).values());
            commands.set(1,patch(commands.get(1),"d",LibraryTrackAddress.from(lp,Direction.EAST,shelf.getBlockPos()).value()));
            int last=commands.size()-1;var ack=commands.get(last);
            commands.set(last,patch(ack,"l",new DataValue.BooleanValue(false)));
            commands.add(patch(commands.get(last-2),"d",num(1)));commands.add(commands.get(last-1));commands.add(ack);
            rom.setFlow(Optional.of(FlowDocumentBody.fromProgram(body.program(rom.snapshot().archive()),patch(initial,"commands",new DataValue.ListValue(commands)))));
            var docs=List.of(rom.snapshot(),doc("library_read_b"),doc("library_read_c"),doc("recipe_browser_query"),doc(checkout?"recipe_browser_pick":"recipe_browser_terminal"));
            if(checkout)h.assertValueEqual(host(members[5]).installDocument(doc("recipe_browser_checkout"),ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED,"Install checkout desk");
            for(int i=0;i<docs.size();i++)h.assertValueEqual(host(members[i]).installDocument(docs.get(i),ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED,"Install "+roles.get(i));
        }
        static DataValue.IntegerValue num(int n){return new DataValue.IntegerValue(n);}
        void waitForCable(int ticks){
            if(!host(members[1]).serialDevice().status("s0").connection().equals("ready")){
                h.assertTrue(ticks<200,"External serial connection not ready");h.runAfterDelay(1,()->waitForCable(ticks+1));return;
            }
            if(checkout)host(members[5]).setPaused(false);
            for(int i:List.of(2,1,0,3,4))host(members[i]).setPaused(false);
            poll();
        }
        ComputerControlSession.Answer action(ComputerControlSession.Action act,DataValue.RecordValue payload){
            return action(4,act,payload);
        }
        ComputerControlSession.Answer action(int index,ComputerControlSession.Action act,DataValue.RecordValue payload){
            var ui=host(members[index]);
            if(index==5)return deskMenu.apply(deskNonce,++deskIntent,ui.programRevision(),act,()->payload,ui).orElseThrow();
            return menu.apply(nonce,++intent,ui.programRevision(),act,()->payload,ui).orElseThrow();
        }
        void poll(){h.runAfterDelay(2,()->{
            for(var member:members){
                var state=host(member).flowSnapshot().orElseThrow().state();
                h.assertTrue(state.status()!=FlowRuntime.Status.FAILED,member.getBlockPos()+": "+state.diagnostic());
            }
            if(checkout && stage>=3){
                var desk=host(members[5]);var state=desk.flowSnapshot().orElseThrow().state();
                if(stage==3 && state.status()==FlowRuntime.Status.WAITING_ANSWER){
                    var live=action(5,ComputerControlSession.Action.READ_TERMINAL,empty);
                    var dv=TerminalStatusView.decode(live.dedicatedStatus().orElseThrow());
                    var current=(DataValue.RecordValue)((DataValue.RecordValue)dv.roots().field("memory")).field("current");
                    var req=(DataValue.RecordValue)current.field("request");h.assertValueEqual(req.field("n"),num(4),"Player selected four batches");
                    var ref=(DataValue.RecordValue)req.field("d");h.assertValueEqual(ref.field("page"),num(1),"Selected brick book page");
                    var output=(DataValue.RecordValue)((DataValue.ListValue)current.field("o")).values().getFirst();
                    h.assertValueEqual(output.field("item"),new DataValue.TextValue("minecraft:brick"),"Frozen output shown");
                    h.assertValueEqual(action(5,ComputerControlSession.Action.TERMINAL_SUBMIT,
                        new TerminalSubmit(deskIntent,TerminalSubmit.QUESTION,"cancel").encode()).code(),"ok","Explicit cancel");
                    stage++;
                } else if(stage==4 && state.status()==FlowRuntime.Status.COMPLETE){
                    h.assertValueEqual(state.locals().field("reply"),new DataValue.TextValue("cancelled"),"No order sent");
                    h.assertTrue(ItemStack.matches(original,shelf.getItem(0)),"Physical source unchanged");h.succeed();return;
                }
                poll();return;
            }
            var ui=host(members[4]);
            if(ui.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_ANSWER){poll();return;}
            var read=action(ComputerControlSession.Action.READ_TERMINAL,empty);
            var view=TerminalStatusView.decode(read.dedicatedStatus().orElseThrow());var prompt=view.question().orElseThrow();
            if(previous.equals(prompt.token())){poll();return;}
            previous=prompt.token();
            var panel=(DataValue.RecordValue)((DataValue.RecordValue)view.roots().field("memory")).field("last_search");
            var rows=((DataValue.ListValue)panel.field("rows")).values();
            if(stage>0){
                h.assertValueEqual(panel.field("c"),new DataValue.TextValue("end"),"Complete search");
                h.assertValueEqual(rows.size(),stage==1?2:1,"Explicit matching rows");
                for(int i=0;i<rows.size();i++){
                    var item=(DataValue.RecordValue)((DataValue.ListValue)((DataValue.RecordValue)rows.get(i)).field("outputs")).values().getFirst();
                    h.assertValueEqual(item.field("item"),new DataValue.TextValue(stage==1&&i==0?"minecraft:paper":"minecraft:brick"),"Book result order and selection");
                }
            }
            if(stage==2){
                h.assertTrue(ItemStack.matches(original,shelf.getItem(0)),"Same physical book and components");
                h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().sequence(),4L,"Only seek, select and two page reads");
                if(checkout){
                    var nodes=new ArrayDeque<>(List.of(TerminalProjection.project(view,Map.of(),20,200)));String instance=null;
                    while(!nodes.isEmpty()){var node=nodes.removeFirst();if(node.node().equals("batches"))instance=node.instance();nodes.addAll(node.children());}
                    h.assertTrue(instance!=null,"Published batch input");
                    h.assertValueEqual(action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(intent,instance,"4").encode()).code(),"ok","Stage the displayed brick row");
                    stage++;poll();return;
                }
                h.succeed();return;
            }
            h.assertValueEqual(action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(intent,TerminalSubmit.QUESTION,stage++==0?"":"BRICK").encode()).code(),"ok","Player terminal submission");
            poll();
        });}
    }
}
