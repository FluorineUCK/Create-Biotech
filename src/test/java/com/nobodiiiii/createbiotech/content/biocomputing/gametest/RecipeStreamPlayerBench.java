package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingRecipeBrowserGameTests.*;

/** Prepare only the data source and wiring. A player in the copied world must submit the actual search. */
@GameTestHolder("create_biotech_task4") @PrefixGameTestTemplate(false)
public final class RecipeStreamPlayerBench {
    @GameTest(template="empty",timeoutTicks=120000)
    public static void prepareShapedRecipeSearch(GameTestHelper h) throws IOException {
        prepare(h,false);
    }
    static void prepare(GameTestHelper h,boolean scan)throws IOException {
        var level=h.getLevel();var base=h.absolutePos(new BlockPos(1,1,1));var extra=new ArrayList<ComputerBlockEntity>();
        for(int i=0;i<5;i++)extra.add(computer(h,base.offset(i+1,1,4),VillagerProfession.CLERIC,5));
        if(scan)extra.add(computer(h,base.offset(2,1,5),VillagerProfession.CLERIC,5));
        var s=new Station(h,false,6);var members=new ArrayList<>(List.of(s.members));members.addAll(extra);
        var lp=s.library.getBlockPos();var cp=lp.west(4);
        var actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"rom-stream"));actor.moveTo(Vec3.atCenterOf(cp.east()));
        var shelf=s.shelf;var book=shelf.getItem(0).copy();
        try(var file=Objects.requireNonNull(RecipeStreamPlayerBench.class.getResourceAsStream("/biocomputing/jei-iron-pickaxe-v3.json"))){
            var pages=new ArrayList<>(book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages());
            pages.add(Filterable.passThrough(new String(file.readAllBytes(),StandardCharsets.UTF_8).strip()));
            book.set(DataComponents.WRITABLE_BOOK_CONTENT,new WritableBookContent(pages));shelf.setItem(0,book);
        }
        var role=new ArrayList<>(List.of("rom","wire","receive","query","terminal","decode","collect","verify","stream","adapter"));
        var ids=new ArrayList<>(List.of("recipe_stream_read","library_service_b","library_service_c","recipe_stream_query","recipe_browser_terminal",
            "recipe_stream_decode","library_segmented_read_collect","library_segmented_read_verify","library_segmented_read_stream","library_segmented_read_adapter"));
        if(scan){role.add("pager");ids.add("recipe_book_gate");ids.set(0,"recipe_book_read");ids.set(3,"recipe_book_query");ids.set(5,"recipe_book_decode");}
        var bindings=new LinkedHashMap<String,ClusterConfiguration.Member>();var wires=new LinkedHashMap<String,XBusWire>();
        for(int i=0;i<members.size();i++){
            var m=members.get(i);var cpu=host(m);if(i<s.members.length)cpu.setPaused(true);
            var draft=new ProgramWorkspace(doc(ids.get(i)));var body=draft.flow().orElseThrow();var input=body.initial();
            if(i==0){
                var track=LibraryTrackAddress.from(lp,Direction.EAST,shelf.getBlockPos()).value();
                var commands=new ArrayList<>(((DataValue.ListValue)input.field("commands")).values());
                for(int n=0;n<commands.size();n++){
                    var r=(DataValue.RecordValue)commands.get(n);
                    var arg=switch(((DataValue.TextValue)r.field("o")).value()){
                        case "seek" -> (DataValue)track;case "select" -> new DataValue.IntegerValue(0);
                        case "recipe_page_begin" -> new DataValue.IntegerValue(scan?0:2);default -> null;};
                    if(arg!=null)commands.set(n,patch(r,"d",arg));
                }
                input=patch(input,"commands",new DataValue.ListValue(commands));
            }
            if(i==9)input=patch(input,"target",com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockSnapshotReader.position(lp));
            draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),input,body.canvas(),body.inline(),body.execution(),body.compilation())));
            h.assertValueEqual(cpu.installDocument(draft.snapshot(),ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Install "+role.get(i));
            if(i>=5)cpu.configureEndpoint(new EndpointConfiguration(role.get(i),true));
            bindings.put(role.get(i),new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),m.getBlockPos()));
        }
        for(var line:List.of("rom x0 adapter x0","rom x1 decode x0","rom x1 query x0","query x1 terminal x0","decode x1 collect x0",
            "collect x1 verify x0","verify x1 stream x0","stream x1 adapter x0","adapter x1 wire x0","wire x1 receive x0")){
            var p=line.split(" ");wires.put(p[0]+"_"+p[2],wire(p[0],p[1],p[2],p[3]));
        }
        var gate=(ClusterControllerBlockEntity)level.getBlockEntity(cp);
        var signals=new LinkedHashMap<String,SimpleIoWire>();
        if(scan){
            wires.remove("rom_query");wires.put("rom_pager",wire("rom","x1","pager","x0"));
            wires.put("pager_query",wire("pager","x1","query","x0"));
        }
        for(String dst:(scan?List.of("pager","decode"):List.of("query")))
            signals.put(dst,new SimpleIoWire(new SimpleIoWire.Pin("rom","rom","p0"),new SimpleIoWire.Pin(dst,dst,"p0"),true));
        h.assertValueEqual(gate.configure(new ClusterConfiguration(bindings,Map.of(),Map.of(),wires,signals),actor),"configured","Explicit connections");
        var run=h.startSequence().thenWaitUntil(()->h.assertValueEqual(host(members.get(1)).serialDevice().status("s0").connection(),"ready","Library serial"))
            .thenExecute(()->{members.forEach(m->host(m).setPaused(false));level.getServer().tickRateManager().requestGameToSprint(120000);});
        if(scan)run.thenWaitUntil(()->h.assertValueEqual(host(members.get(4)).flowSnapshot().orElseThrow().state().status(),
            FlowRuntime.Status.WAITING_ANSWER,"Installed terminal is asking")).thenExecute(()->{
                var read=s.action(ComputerControlSession.Action.READ_TERMINAL,s.empty);
                TerminalStatusView.decode(read.dedicatedStatus().orElseThrow()).question().orElseThrow();
                h.assertValueEqual(
                s.action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(s.intent,TerminalSubmit.QUESTION,"").encode()).code(),
                "ok","Request all pages before the scan finishes");});
        run.thenWaitUntil(()->{
            // Terminal failures are not transient wait conditions. Inspect the heavy checkpoints only once per second.
            h.assertTrue(h.getTick()%20==0,"Next status sample");
            for(var m:members){
                var f=host(m).flowSnapshot().orElseThrow();
                if(f.state().status()==FlowRuntime.Status.FAILED||f.tasks().contexts().stream().anyMatch(t->t.status()==FlowRuntime.Status.FAILED))return;
            }
            var rom=host(members.get(0)).flowSnapshot().orElseThrow().state();
            h.assertValueEqual(rom.status(),FlowRuntime.Status.COMPLETE,"Read and close");
            if(scan && ((DataValue.RecordValue)rom.locals().field("result")).field("c").equals(new DataValue.TextValue("")))
                h.assertTrue(host(members.get(4)).flowSnapshot().orElseThrow().state().locals().fields().containsKey("result"),"Terminal result");
        }).thenExecute(()->{
            for(var m:members){
                var f=host(m).flowSnapshot().orElseThrow();
                h.assertFalse(f.state().status()==FlowRuntime.Status.FAILED,"Fault at "+m.getBlockPos()+": "+f.state().diagnostic());
                for(var t:f.tasks().contexts())h.assertFalse(t.status()==FlowRuntime.Status.FAILED,"Task fault at "+m.getBlockPos()+": "+t.diagnostic());
            }
            var result=(DataValue.RecordValue)host(members.get(0)).flowSnapshot().orElseThrow().state().locals().field("result");
            if(scan){
                h.assertValueEqual(result.field("c"),new DataValue.TextValue(""),"Book scan: "+result);
                var panel=(DataValue.RecordValue)host(members.get(4)).flowSnapshot().orElseThrow().state().locals().field("result");
                h.assertValueEqual(panel.field("c"),new DataValue.TextValue("end"),"Completed, not a partial list");
                var rows=((DataValue.ListValue)panel.field("rows")).values();
                h.assertValueEqual(rows.size(),3,"All three positioned pages");
                for(int i=0;i<3;i++)h.assertValueEqual(((DataValue.RecordValue)rows.get(i)).field("page"),new DataValue.IntegerValue(i),"Physical page order");
                var third=(DataValue.RecordValue)rows.get(2);var product=(DataValue.RecordValue)((DataValue.ListValue)third.field("outputs")).values().getFirst();
                h.assertValueEqual(product.field("item"),new DataValue.TextValue("minecraft:iron_pickaxe"),"Large final page");
                h.assertValueEqual(((DataValue.RecordValue)third.field("body")).field("crafting_slots"),
                    new DataValue.ListValue(List.of(0,1,2,4,7).stream().<DataValue>map(DataValue.IntegerValue::new).toList()),"Original grid");
            } else h.assertValueEqual(result.field("ok"),new DataValue.BooleanValue(true),"Public ROM read: "+result);
            System.out.println((scan?"ROM_BOOK_PLAYER_GATE ":"ROM_STREAM_PLAYER_GATE ")+cp);
        }).thenSucceed();
    }
}
