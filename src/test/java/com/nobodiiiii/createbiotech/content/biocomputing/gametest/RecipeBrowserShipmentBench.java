package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingRecipeBrowserGameTests.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingStockApplicationGameTests.*;

/** Make a saved, wired bench. No checkout answer, order, receipt or package is created by this fixture. */
@GameTestHolder("create_biotech_task4") @PrefixGameTestTemplate(false)
public final class RecipeBrowserShipmentBench {
    static final List<String> ROLES=List.of("rom","wire","receive","query","terminal","desk","validate","verify","materials","choose","route","submit","packet","exchange");
    static ProgramDocument readSecondPage(BlockPos library, DataValue track) {
        var w=new ProgramWorkspace(doc("recipe_page_a"));var body=w.flow().orElseThrow();
        var rows=new ArrayList<>(((DataValue.ListValue)body.initial().field("commands")).values());
        rows.set(1,patch(rows.get(1),"d",track));int read=rows.size()-3;
        if(!((DataValue.RecordValue)rows.get(read)).field("o").equals(new DataValue.TextValue("recipe_page")))throw new IllegalStateException("Expected read page");
        rows.set(read,patch(rows.get(read),"d",new DataValue.IntegerValue(1)));
        var in=patch(patch(body.initial(),"target",com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockSnapshotReader.position(library)),"commands",new DataValue.ListValue(rows));
        w.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),in,body.canvas(),body.inline(),body.execution(),body.compilation())));return w.snapshot();
    }
    @GameTest(template="empty",timeoutTicks=15000)
    public static void preparePlayerShipmentBench(GameTestHelper h) {
        var base=h.absolutePos(new BlockPos(1,12,1));var extraMachines=new ArrayList<ComputerBlockEntity>();
        for(int i=0;i<8;i++)extraMachines.add(computer(h,base.offset(1+i%5,1,i<5?4:5),VillagerProfession.CLERIC,5));
        var station=new Station(h,true,6);var cp=station.library.getBlockPos().west(4);
        var gate=(ClusterControllerBlockEntity)h.getLevel().getBlockEntity(cp);
        var actor=new MenuActor(h.getLevel(),"browser-operator");actor.go(cp.east());
        var m=new ArrayList<>(List.of(station.members));
        m.addAll(extraMachines);
        var bindings=new LinkedHashMap<String,ClusterConfiguration.Member>();var wires=new LinkedHashMap<String,XBusWire>();
        var sig=new LinkedHashMap<String,SimpleIoWire>();
        var extra=List.of("recipe_preview_v","recipe_browser_dispatch","stock_application_recipe_materials","material_selection_service",
            "recipe_browser_route","stock_application_recipe_submit","stock_native_packet_scoped","serial_exchange");
        for(int i=0;i<m.size();i++){var cpu=host(m.get(i));String role=ROLES.get(i);
            if(i>=6){h.assertValueEqual(cpu.installDocument(doc(extra.get(i-6)),ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Install "+role);
                cpu.configureEndpoint(new EndpointConfiguration(role,true));}
            bindings.put(role,new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),m.get(i).getBlockPos()));}
        for(String[] link:new String[][]{{"rom_wire","rom","x0","wire","x0"},{"wire_rx","wire","x1","receive","x0"},{"rom_query","rom","x1","query","x0"},
            {"query_ui","query","x1","terminal","x0"},{"proposal","terminal","x1","desk","x0"},{"order","desk","x1","verify","x0"},
            {"validated","validate","x1","verify","x0"},{"materials","verify","x1","materials","x0"},{"choose","materials","x1","choose","x0"},
            {"selected","materials","x0","route","x0"},{"shipment","route","x1","submit","x0"},{"command","submit","x1","packet","x0"},
            {"serial","packet","x1","exchange","x0"}})wires.put(link[0],wire(link[1],link[2],link[3],link[4]));
        sig.put("ready",signal("materials","route"));
        h.assertValueEqual(gate.configure(new ClusterConfiguration(bindings,Map.of(),Map.of(),wires,sig),actor),"configured","14 independent CPUs");
        var workshop=Workshop.place(h,base.offset(5,1,5),cp.east(3).south(4),m.get(13));
        var submit=host(m.get(11));var rom=host(m.get(0));
        h.startSequence().thenWaitUntil(()->{
            h.assertValueEqual(host(m.get(1)).serialDevice().status("s0").connection(),"ready","ROM serial");
            h.assertValueEqual(host(m.get(13)).serialDevice().status("s0").connection(),"ready","Stock serial");
        }).thenExecute(()->{
            workshop.grant(h,actor,m.get(13));
            h.assertValueEqual(submit.installDocument(submitSettings(workshop.position(),DataValue.NullValue.INSTANCE),ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Probe before approving");
            for(int i:new int[]{13,12,11,2,1,0,3,4})host(m.get(i)).setPaused(false);
        }).thenWaitUntil(()->h.assertValueEqual(host(m.get(4)).flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.WAITING_ANSWER,"Search prompt"))
        .thenExecute(()->{station.action(ComputerControlSession.Action.READ_TERMINAL,station.empty);
            h.assertValueEqual(station.action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(station.intent,TerminalSubmit.QUESTION,"BRICK").encode()).code(),"ok","Filter the real book");})
        .thenWaitUntil(()->{
            h.assertValueEqual(rom.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"Drive acknowledged");
            h.assertValueEqual(submit.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"Live scope probe");
            h.assertValueEqual(host(m.get(4)).flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.WAITING_ANSWER,"Next search prompt");
            var answer=station.action(ComputerControlSession.Action.READ_TERMINAL,station.empty);
            var view=TerminalStatusView.decode(answer.dedicatedStatus().orElseThrow());
            var panel=(DataValue.RecordValue)((DataValue.RecordValue)view.roots().field("memory")).field("last_search");
            h.assertValueEqual(panel.field("q"),new DataValue.TextValue("BRICK"),"Complete search");
            var row=(DataValue.RecordValue)((DataValue.ListValue)panel.field("rows")).values().getFirst();
            h.assertValueEqual(row.field("page"),new DataValue.IntegerValue(1),"Second physical page");
        }).thenExecute(()->{
            var reply=(DataValue.RecordValue)submit.flowSnapshot().orElseThrow().state().locals().field("reply");
            h.assertValueEqual(reply.field("code"),new DataValue.TextValue("scope_not_approved"),"No implicit dispatch");
            var scope=(DataValue.RecordValue)reply.field("scope");h.assertValueEqual(scope.field("address"),new DataValue.TextValue("press"),"Live address");
            submit.setPaused(true);submit.reset();h.assertValueEqual(submit.installDocument(submitSettings(workshop.position(),scope),ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Approve exactly this scope");
            var track=((DataValue.RecordValue)((DataValue.ListValue)rom.document().orElseThrow().flow().orElseThrow().initial().field("commands")).values().get(1)).field("d");
            rom.setPaused(true);rom.reset();
            wires.remove("rom_query");wires.put("reread",wire("rom","x1","validate","x0"));actor.go(cp.east());
            h.assertValueEqual(gate.configure(new ClusterConfiguration(bindings,Map.of(),Map.of(),wires,sig),actor),"configured","Reconnect ROM to validator");
            h.assertValueEqual(rom.installDocument(readSecondPage(station.library.getBlockPos(),track),ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Select the page for an explicit player-initiated second read");
            for(int i:new int[]{11,10,9,8,7,6,5})host(m.get(i)).setPaused(false);
            h.assertTrue(workshop.packager().heldBox.isEmpty(),"No parcel preinstalled");h.assertValueEqual(workshop.chest().getItem(0).getCount(),16,"Untouched source");
            System.out.println("BROWSER_PLAYER_BENCH gate="+cp+" packager="+workshop.packager().getBlockPos()+" serial="+workshop.position()+" unopened=16");
        }).thenSucceed();
    }
    static SimpleIoWire signal(String from,String to){return new SimpleIoWire(new SimpleIoWire.Pin(from,from,"p0"),new SimpleIoWire.Pin(to,to,"p0"),true);}
}
