package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortBehaviour;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packagerLink.*;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.Level;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingStockApplicationGameTests {
    private record ReaderDeny(ServerLevel level, BlockPos target, UUID actor) {}
    private static final Set<ReaderDeny> DENIED = new HashSet<>();
    static {
        ReadComponents.registerPolicy(context -> DENIED.contains(new ReaderDeny(context.level(),context.target(),context.actor().getUUID()))
            ? Optional.of(new DataValue.ErrorValue("access_denied","Registered test target policy")) : Optional.empty());
    }

    @GameTest(template="empty", timeoutTicks=10000)
    public static void publicCatalogAndInputTraverseOneClosedClusterAndBothExternalAdapters(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1,1,1)); var max = min.offset(6,4,6);
        var gatePos = min.offset(6,1,2);
        var linkPos = gatePos.east(5); var outputPos = linkPos.north(); var readerPos = linkPos.south();
        var tickerPos = linkPos.east(2);
        boolean recipe = Boolean.getBoolean("biocomputing.stockRecipeCatalogue");
        var names = recipe ? List.of("a","b","c","d","rom","head","match") : List.of("a","b","c","d");
        var roles = recipe ? List.of("stock_application_a","stock_application_b","stock_application_recipe_catalog","stock_application_recipe_row",
            "recipe_page_a","library_read_b","library_read_c") : List.of("stock_application_a","stock_application_b","stock_application_publish","stock_application_read");
        var places = new ArrayList<>(List.of(gatePos.west(), min.offset(1,1,1),min.offset(1,1,4),min.offset(4,1,5)));
        if(recipe) places.addAll(List.of(min.offset(1,1,2),min.offset(2,1,3),min.offset(2,1,5)));
        var libraryPos=min.offset(4,1,3);
        boolean previewOrder = recipe && Boolean.getBoolean("biocomputing.stockRecipeOrderPreview");
        boolean ship = previewOrder && Boolean.getBoolean("biocomputing.stockRecipeMaterials");
        var productionPos = min.offset(5,1,4);
        if (previewOrder) {
            names=new ArrayList<>(names); roles=new ArrayList<>(roles);
            names.addAll(List.of("sampler","selection","order","preview","materials","scale"));
            roles.addAll(List.of("stock_application_recipe_sample","stock_application_recipe_selection","stock_application_recipe_order",
                "recipe_preview_v","recipe_preview_p","recipe_preview_s"));
            places.addAll(List.of(min.offset(1,1,3),min.offset(1,1,5),min.offset(2,1,1),min.offset(3,1,1),min.offset(4,1,1),min.offset(5,1,1)));
        }
        if (ship) {
            roles.set(9,"stock_application_recipe_dispatch"); roles.set(11,"stock_application_recipe_materials");
            roles.set(12,"material_selection_service");
            names.addAll(List.of("route","submit","packet","exchange"));
            roles.addAll(List.of("stock_application_recipe_route","stock_application_recipe_submit","stock_native_packet_scoped","serial_exchange"));
            places.addAll(List.of(min.offset(2,1,2),min.offset(3,1,5),min.offset(5,1,5),min.offset(3,1,4)));
        }
        var machines = new ArrayList<ComputerBlockEntity>();
        for (var pos : places) machines.add(computer(h,pos,VillagerProfession.CLERIC,5));
        for (var p : BlockPos.betweenClosed(min,max))
            if (p.getX()==min.getX() || p.getX()==max.getX() || p.getY()==min.getY() || p.getY()==max.getY() || p.getZ()==min.getZ() || p.getZ()==max.getZ())
                level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
        level.setBlock(gatePos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gatePos);
        var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
        for (int i=0;i<roles.size();i++) {
            var cpu=host(machines.get(i));
            if (ship) h.assertTrue(cpu.hardware().limits().hardware().programUnits()==256,"Installed resident code capacity: "+roles.get(i));
            var program=inputs(document(roles.get(i)),outputPos,readerPos);
            if (roles.get(i).equals("recipe_page_a")) program=selectBook(program,libraryPos);
            cpu.installDocument(program,ComputerRuntime.MemoryPolicy.RESET);
            cpu.configureEndpoint(new EndpointConfiguration(names.get(i),true));
            members.put(names.get(i),new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),places.get(i)));
        }
        var authority=host(machines.getFirst());
        var admin = new MenuActor(level,"stock-admin"); admin.go(gatePos.east());
        var wires=new LinkedHashMap<>(Map.of("ab",wire("a","x0","b","x0"),"bc",wire("b","x1","c","x0"),
            "cd",wire("c","x1","d",recipe?"x1":"x0")));
        if(recipe) wires.putAll(Map.of("page",wire("rom","x1","d","x0"),"drive",wire("rom","x0","head","x0"),"check",wire("head","x1","match","x0")));
        var publishing = new LinkedHashMap<>(members);
        if (ship) publishing.keySet().removeAll(List.of("route","submit","packet","exchange"));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(publishing,Map.of(),Map.of(),wires),admin),"configured","Real closed Cluster");
        authority.configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(
            new ClusterMemberEditorAccess(controller,machines.getFirst()),admin,"s0","stock")));
        authority.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
        var book=recipe ? readOnlyBook(h,libraryPos,machines.get(5),admin) : null;

        level.setBlock(linkPos.below(),AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
        level.setBlock(linkPos,AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
        var link=(PackagerLinkBlockEntity)level.getBlockEntity(linkPos); link.placedBy=admin.getUUID();
        level.setBlock(outputPos,CBBlocks.STOCK_LINK_OUTPUT.get().defaultBlockState().setValue(StockLinkOutputBlock.FACING,Direction.SOUTH),3);
        level.setBlock(readerPos,CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING,Direction.NORTH),3);
        var cable=CBBlocks.DATA_CABLE.get().defaultBlockState();
        for (int i=1;i<=4;i++) level.setBlock(gatePos.east(i),cable,3);
        for (var side : List.of(Direction.NORTH,Direction.SOUTH)) {
            for(int i=1;i<=2;i++) level.setBlock(linkPos.west().relative(side,i),cable,3);
            level.setBlock(linkPos.relative(side,2),cable,3);
        }
        level.setBlock(tickerPos,AllBlocks.STOCK_TICKER.getDefaultState(),3);
        level.setBlock(tickerPos.east(),AllBlocks.BLAZE_BURNER.getDefaultState().setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING),3);
        var ticker=(StockTickerBlockEntity)level.getBlockEntity(tickerPos);
        ticker.behaviour.freqId=link.behaviour.freqId;
        var output=(StockLinkOutputBlockEntity)level.getBlockEntity(outputPos);
        var reader=(BlockReaderBlockEntity)level.getBlockEntity(readerPos);

        if (ship) reportFaults(h,machines);
        var workshop = ship ? Workshop.place(h,productionPos,tickerPos.south(6),machines.get(16)) : null;
        h.runAfterDelay(8,()->{
            h.assertTrue(!(ticker.getBlockState().getBlock() instanceof DataPort) && !(link.getBlockState().getBlock() instanceof DataPort),
                "Native blocks do not accept cables");
            admin.go(outputPos);
            h.assertTrue(StockApplicationMenu.openPhysical(admin,output),"Actual physical output menu");
            var out=admin.application();
            h.assertValueEqual(rows(out,"choices"),1,"Only A's explicit Cluster gateway is exposed");
            out.request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            h.assertValueEqual(out.code(),"bound","Actual physical output menu binds the authorized installation");
            admin.go(readerPos);
            h.assertTrue(ReaderMenu.open(admin,reader,"ok"),"Actual Reader grant menu");
            var read=(ReaderMenu)admin.containerMenu;
            var ids=((DataValue.ListValue)read.view().field("catalog")).values();
            int component=java.util.stream.IntStream.range(0,ids.size()).filter(i -> ((DataValue.RecordValue)ids.get(i)).field("id")
                .equals(text(CreateReadComponents.STOCK_APPLICATION))).findFirst().orElseThrow();
            read.request(ReaderRequest.Action.GRANT,0,List.of(component)).handle(admin);
            h.assertValueEqual(((ReaderMenu)admin.containerMenu).code(),"configured","Actual Reader grants only the stock component");
            h.assertTrue(ticker.isKeeperPresent(),"Native Stock Ticker recognizes the real occupied Blaze Burner");
            admin.go(ticker.getBlockPos());
            admin.openMenu(ticker.new RequestMenuProvider(),b->{});
            var controls = StockApplicationMenu.controls(admin, UUID.randomUUID());
            h.assertTrue(controls.application() && !controls.route(), "Admin can bind an adapter without offering takeover yet");
            StockApplicationMenuRequest.open(admin.containerMenu.containerId).handle(admin);
            var view=admin.application();
            h.assertValueEqual(rows(view,"choices"),1,"Only loaded network adapter offered");
            view.request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            h.assertValueEqual(view.code(),"frontend_bound","Native current-container Application menu pins adapter");
            h.assertValueEqual(view.view().field("status"),text("permission"),"Unknown offline account does not inherit proxy authority");
            restrictedGrantorCannotBorrowProxyPermissions(h, admin.getGameProfile(), admin, view);

            if (recipe) {
                for(int i:List.of(1,0,6,5,3,2,4)) host(machines.get(i)).setPaused(false);
                var originalBook=book.getItem(0).copy();
                var ordered=h.startSequence().thenWaitUntil(()->{
                    var p=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                    h.assertValueEqual(p.status(),FlowRuntime.Status.COMPLETE,"Publisher returned");
                }).thenExecute(()->{
                    var reply=(DataValue.RecordValue)host(machines.get(2)).flowSnapshot().orElseThrow().state().locals().field("reply");
                    h.assertValueEqual(reply.field("code"),text("accepted"),"Real stock port accepted selected ROM entry");
                    view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                    h.assertValueEqual(rows(view,"catalog"),1,"One ROM entry in the physical Stock Keeper");
                    var row=(DataValue.RecordValue)((DataValue.ListValue)view.view().field("catalog")).values().getFirst();
                    var key=((DataValue.TextValue)row.field("key")).value();
                    UUID.fromString(key);
                    h.assertValueEqual(row.field("title"),text("minecraft:paper"),"First physical output");
                    h.assertValueEqual(((DataValue.RecordValue)row.field("icon")).field("count"),number(3),"Items per batch");
                    view.request(StockApplicationMenuRequest.Action.CONFIRM,-1,key,2,"shop").handle(admin);
                    h.assertValueEqual(view.code(),"input_queued","Order 2 batches through the native front end");
                    var stock=StockApplicationCodec.load(output.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockApplication"),UUID.randomUUID());
                    h.assertValueEqual(stock.inputs().size(),1,"One frozen input");
                    var item=stock.inputs().getFirst().items().getFirst();
                    h.assertValueEqual(item.quantity(),2L,"Batch count, not multiplied or manufactured");
                    h.assertValueEqual(item.entry(),stock.catalog().getFirst(),"Frozen key, icon components, page number and fingerprint");
                    h.assertTrue(net.minecraft.world.item.ItemStack.matches(originalBook,book.getItem(0)),"ROM book was not changed by publishing or ordering");
                    System.out.println("STOCK_ROM_MENU real page -> typed catalogue -> physical Stock Keeper -> 2 batches queued; book unchanged");
                });
                if (previewOrder) {
                    ordered.thenWaitUntil(()->h.assertValueEqual(host(machines.get(4)).flowSnapshot().orElseThrow().state().status(),
                        FlowRuntime.Status.COMPLETE,"ROM driver must finish its actual device result ACK before reuse")).thenExecute(()->{
                        // Same already-granted stock and Library endpoints. Only the player-side consumers change.
                        var next = new LinkedHashMap<>(wires);
                        next.remove("bc"); next.remove("cd"); next.remove("page");
                        next.put("sample",wire("b","x1","sampler","x0"));
                        next.put("select",wire("sampler","x1","selection","x0"));
                        next.put("frozen",wire("selection","x1","order","x0"));
                        next.put("page",wire("rom","x1","preview","x0"));
                        next.put("validated",wire("preview","x1","order","x0"));
                        next.put("scale",wire("order","x1","materials","x0"));
                        next.put("rows",wire("materials","x1","scale","x0"));
                        admin.go(gatePos);
                        var running = new LinkedHashMap<>(members);
                        var signals = new LinkedHashMap<String,SimpleIoWire>();
                        if (ship) {
                            // D remains an active polling service, so keep its binding (disconnect only its wire).
                            // Only the completed publisher gives up a slot, never the granted stock gateway.
                            var publisher=host(machines.get(2));
                            h.assertValueEqual(publisher.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"Publisher has returned");
                            if (!publisher.communicationQuiescent()) { publisher.setPaused(true); publisher.reset(); }
                            h.assertTrue(publisher.communicationQuiescent(),"Do not detach a busy publisher");
                            running.remove("c");
                            next.put("selected",wire("materials","x0","route","x0"));
                            next.put("shipment",wire("route","x1","submit","x0"));
                            next.put("command",wire("submit","x1","packet","x0"));
                            next.put("serial",wire("packet","x1","exchange","x0"));
                            signals.put("ready",new SimpleIoWire(new SimpleIoWire.Pin("materials","materials","p0"),
                                new SimpleIoWire.Pin("route","route","p0"),true));
                        }
                        h.assertValueEqual(controller.configure(new ClusterConfiguration(running,Map.of(),Map.of(),next,signals),admin),"configured","Second phase wiring");
                        if (ship) {
                            workshop.grant(h,admin,machines.get(16));
                            var u=host(machines.get(14));
                            u.installDocument(submitSettings(productionPos,DataValue.NullValue.INSTANCE),ComputerRuntime.MemoryPolicy.RESET);
                            for(int i:List.of(16,15,14)) host(machines.get(i)).setPaused(false);
                        }
                        for(int i=7;i<(ship?11:13);i++) host(machines.get(i)).setPaused(false);
                    }).thenWaitUntil(()->h.assertValueEqual(host(machines.get(9)).flowSnapshot().orElseThrow().state()
                        .signals().outputs().getOrDefault("p0",0),15,"Frozen selection consumed before a second ROM read"));
                    if (ship) ordered.thenWaitUntil(()->{
                        var s=host(machines.get(14)).flowSnapshot().orElseThrow().state();
                        h.assertValueEqual(s.status(),FlowRuntime.Status.COMPLETE,"Inspect actual live scope without a shipment");
                    }).thenExecute(()->{
                        var u=host(machines.get(14));
                        var probe=(DataValue.RecordValue)u.flowSnapshot().orElseThrow().state().locals().field("reply");
                        h.assertValueEqual(probe.field("code"),text("scope_not_approved"),"No implicit authorization");
                        var scope=(DataValue.RecordValue)probe.field("scope");
                        h.assertValueEqual(scope.field("address"),text("press"),"Operator checks the press grant");
                        u.setPaused(true); u.reset();
                        u.installDocument(submitSettings(productionPos,scope),ComputerRuntime.MemoryPolicy.RESET);
                        for(int i:List.of(14,13,12,11)) host(machines.get(i)).setPaused(false);
                    });
                    ordered.thenExecute(()->{
                        var rom=host(machines.get(4)); rom.reset(); rom.setPaused(false);
                        System.out.println("STOCK_ROM_ORDER selected actual order; fresh book read started");
                    });
                    if(ship) ordered.thenWaitUntil(()->{
                        var s=host(machines.get(14)).flowSnapshot().orElseThrow().state();
                        h.assertValueEqual(s.status(),FlowRuntime.Status.COMPLETE,"Public submit and query return");
                        var receipt=(DataValue.RecordValue)s.locals().field("reply");
                        var stock=StockApplicationCodec.load(output.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockApplication"),UUID.randomUUID());
                        h.assertValueEqual(stock.inputs().size(),1,"Shipping ingredients does not ACK a customer");
                        h.assertValueEqual(receipt.field("order"),text(stock.fence().device()+"/materials/"+stock.inputs().getFirst().sequence()),"Original stock head owns the shipment");
                        h.assertValueEqual(receipt.field("code"),text("ok"),"Checked native order query");
                        h.assertValueEqual(receipt.field("address"),text("press"),"Receipt names the original workshop");
                        workshop.checkShipment(h);
                        h.assertTrue(ItemStack.matches(originalBook,book.getItem(0)),"No ROM mutation");
                    }).thenExecute(()->System.out.println("STOCK_MATERIALS real player order -> physical ROM -> 4 actual stone to press; query ok; customer head retained"));
                    else ordered.thenWaitUntil(()->{
                        var calc=host(machines.get(11)).flowSnapshot().orElseThrow().state();
                        h.assertValueEqual(calc.status(),FlowRuntime.Status.COMPLETE,"Material preview returned");
                        var result=(DataValue.RecordValue)((DataValue.RecordValue)calc.locals().field("result")).field("value");
                        h.assertValueEqual(result.field("n"),number(2),"Player batches, not V's default 3");
                        var material=(DataValue.RecordValue)((DataValue.ListValue)result.field("c")).values().getFirst();
                        h.assertValueEqual(material.field("item"),text("minecraft:stone"),"Read the real book, not the paper icon");
                        h.assertValueEqual(material.field("count"),number(4),"Two stone per batch times two actual batches");
                        var stock=StockApplicationCodec.load(output.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockApplication"),UUID.randomUUID());
                        h.assertValueEqual(stock.inputs().size(),1,"Preview must not ACK or duplicate original order");
                        h.assertTrue(net.minecraft.world.item.ItemStack.matches(originalBook,book.getItem(0)),"Preview must not change the book");
                        System.out.println("STOCK_ROM_ORDER same player order -> re-read physical ROM -> 4 stone; queue retained");
                    });
                }
                ordered.thenSucceed();
                return;
            }
            h.runAfterDelay(5,()->{
                host(machines.get(1)).setPaused(false);
                h.runAfterDelay(8,()->authority.setPaused(false));
                h.runAfterDelay(16,()->host(machines.get(2)).setPaused(false));
                var peer=new MenuActor(level,"stock-buyer");
                h.startSequence().thenWaitUntil(()->{
                    view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                    h.assertValueEqual(rows(view,"catalog"),1,"Catalog must be published by installed ISA");
                    var published=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                    h.assertValueEqual(published.status(),FlowRuntime.Status.COMPLETE,"C must finish the correlated publication reply before test mutates the transport");
                    h.assertValueEqual(((DataValue.RecordValue)published.locals().field("reply")).field("code"),text("accepted"),"C receives actual publish acknowledgement");
                }).thenExecute(()->{
                    h.assertValueEqual(integer(view,"pending"),0L,"Refresh creates no input");
                    System.out.println("STOCK_APPLICATION catalog published; both physical grants live");
                    var denied = new ReaderDeny(level,linkPos,admin.getUUID());
                    long sequence = archivedNextSequence(output);
                    DENIED.add(denied);
                    try {
                        var readActor = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,admin.getGameProfile());
                        var actual = BlockSnapshotReader.readComponents(level,linkPos,Direction.SOUTH,readActor,
                            CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot(),Set.of(CreateReadComponents.STOCK_APPLICATION));
                        h.assertTrue(actual.fields().get("error") instanceof DataValue.ErrorValue error && error.code().equals("access_denied"),
                            "Existing Reader pipeline recognizes the registered policy");
                        confirm(view,"policy-denied",1).handle(admin);
                        h.assertValueEqual(view.code(),"reader_unavailable","Application admission uses the same target policy");
                        h.assertValueEqual(integer(view,"pending"),0L,"Reader policy denial leaves queue untouched");
                        h.assertValueEqual(archivedNextSequence(output),sequence,"Reader policy denial does not spend the sequence");
                    } finally { DENIED.remove(denied); }
                    peer.openNative(ticker);
                    var other=peer.application();
                    var request=confirm(view,"station",3);
                    var conflict=confirm(other,"second",1);
                    authority.setPaused(true);
                    request.handle(admin);
                    h.assertValueEqual(view.code(),"paused","Pause is rechecked after opening the menu");
                    authority.setPaused(false);
                    request=confirm(view,"station",3);
                    request.handle(admin);
                    h.assertValueEqual(view.code(),"input_queued","Actual checked menu queues only the frozen input");
                    System.out.println("STOCK_APPLICATION menu queued input exactly once");
                    request.handle(admin);
                    h.assertValueEqual(integer(view,"pending"),1L,"Old nonce cannot replay admission");
                    conflict.handle(peer);
                    h.assertValueEqual(other.code(),"conflict","Two players cannot claim the same sequence");
                    h.assertValueEqual(integer(other,"pending"),1L,"Second actor did not add another input");
                    var restored=(StockLinkOutputBlockEntity)reconstruct(level,output);
                    h.assertTrue(restored!=output && !restored.getUpdateTag(level.registryAccess()).contains("BiocomputingStockGrant"),
                        "Reload keeps authority out of client synchronization");
                }).thenIdle(5).thenExecute(()->{
                    view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                    h.assertValueEqual(integer(view,"pending"),1L,"Actual output archive retains pending frozen input after replacement/load");
                    host(machines.get(3)).setPaused(false);
                    System.out.println("STOCK_APPLICATION restored pending input; D started after confirmation");
                    for (int delay : List.of(300,800,1400,2400,4000,6000)) h.runAfterDelay(delay,()->{
                        view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                        var cpu = machines.stream().map(machine -> host(machine).flowSnapshot().orElseThrow().state())
                            .map(f -> f.status()+"/"+f.cursor()+"/"+f.assembly().map(c -> c.pc()).orElse(-1)
                                +"/"+f.work()+"/"+f.diagnostic()).toList();
                        System.out.println("STOCK_APPLICATION wait "+delay+" "+cpu+" app="+view.view().field("status")
                            +"/"+view.view().field("last")+" reader="+reader.getBehaviour(ReaderSerialBehaviour.TYPE).status()
                            +" serial="+authority.serialDevice().status("s0"));
                    });
                }).thenWaitUntil(()->{
                    var state=host(machines.get(3)).flowSnapshot().orElseThrow().state();
                    h.assertValueEqual(state.status(),FlowRuntime.Status.COMPLETE,"Read+ACK document finishes through real adapters: "
                        + machines.stream().map(cpu -> host(cpu).flowSnapshot().orElseThrow().state())
                            .map(f -> f.status()+"/"+f.cursor()+"/"+f.diagnostic()).toList());
                    var head=(DataValue.RecordValue)state.locals().field("input");
                    h.assertValueEqual(head.field("address"),text("station"),"Exact frozen player address");
                    var item=(DataValue.RecordValue)((DataValue.ListValue)head.field("items")).values().getFirst();
                    h.assertValueEqual(item.field("quantity"),number(3),"Exact frozen quantity");
                    var icon=(DataValue.RecordValue)((DataValue.RecordValue)item.field("entry")).field("icon");
                    var patch=(DataValue.NbtValue)icon.field("components");
                    h.assertTrue(((DataValue.RecordValue)patch.value()).fields().containsKey("minecraft:custom_name"),"Icon components survive sample");
                    h.assertValueEqual(((DataValue.RecordValue)state.locals().field("reply")).field("code"),text("acknowledged"),"Real output accepted explicit ACK");
                    view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                    h.assertValueEqual(integer(view,"pending"),0L,"Only ACK retires the Reader head");
                }).thenExecute(()->{
                    System.out.println("STOCK_APPLICATION exact frozen input and explicit wire ACK observed");
                    peer.go(tickerPos);
                    peer.openMenu(ticker.new RequestMenuProvider(), b -> {});
                    var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
                    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Native takeover"));
                    var nativeOrder = com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(
                        List.of(new com.simibubi.create.content.logistics.BigItemStack(stack,2)));
                    var denied = new ReaderDeny(level,linkPos,admin.getUUID());
                    DENIED.add(denied);
                    try {
                        StockNativeOrderRequest.create(peer.containerMenu.containerId,nativeOrder,"retained",level.registryAccess()).handle(peer);
                        h.assertTrue(peer.containerMenu instanceof StockApplicationMenu,"Unavailable Reader still opens the visible native preview");
                        var blocked=peer.application();
                        h.assertValueEqual(blocked.view().field("status"),text("reader_unavailable"),"Native preview displays the refusal instead of hiding it behind the native screen");
                        blocked.request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"").handle(peer);
                        h.assertValueEqual(blocked.code(),"reader_unavailable","Preview never bypasses admission policy");
                        h.assertValueEqual(integer(blocked,"pending"),0L,"Rejected native confirmation does not admit input");
                    } finally { DENIED.remove(denied); }
                    var takeover=peer.application();
                    takeover.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(peer);
                    h.assertTrue(takeover.view().field("native_order") instanceof DataValue.NbtValue,"Native order has a separate confirmation page, not a fabricated catalog row");
                    h.assertValueEqual(integer(takeover,"pending"),0L,"Preview never enqueues or dispatches");
                    var confirmation=takeover.request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"");
                    confirmation.handle(peer);
                    h.assertValueEqual(takeover.code(),"input_queued","Real native menu confirms one application input");
                    confirmation.handle(peer);
                    takeover.request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"").handle(peer);
                    h.assertValueEqual(takeover.code(),"existing","Fresh menu nonce still observes the same fixed confirmation identity");
                    h.assertValueEqual(integer(takeover,"pending"),1L,"Duplicate native confirmations never become extra inputs");
                    level.removeBlock(linkPos,false);
                    level.setBlock(linkPos,AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
                    var replacement=(PackagerLinkBlockEntity)level.getBlockEntity(linkPos);
                    replacement.placedBy=peer.getUUID();
                    h.assertTrue(!ticker.behaviour.freqId.equals(replacement.behaviour.freqId),"Replacement uses a different Create network");
                }).thenIdle(6).thenExecute(()->{
                    var retained=(StockLinkOutputBlockEntity)level.getBlockEntity(outputPos);
                    long sequence=archivedNextSequence(retained);
                    var replacement=(PackagerLinkBlockEntity)level.getBlockEntity(linkPos);
                    h.assertTrue(replacement.behaviour.mayAdministrate(peer) && !replacement.behaviour.mayAdministrate(admin),
                        "Buyer is genuinely the different network B administrator");
                    peer.go(outputPos); admin.go(outputPos);
                    h.assertTrue(StockApplicationMenu.openPhysical(peer,retained),"B may inspect the physically adjacent adapter");
                    var b=peer.application();
                    h.assertValueEqual(b.view().field("may_withdraw"),new DataValue.BooleanValue(false),"B sees no withdraw/discard control");
                    b.request(StockApplicationMenuRequest.Action.REVOKE,-1,"",0,"").handle(peer);
                    h.assertValueEqual(b.code(),"access_denied","Network B cannot withdraw network A's grant");
                    b.request(StockApplicationMenuRequest.Action.DISCARD,1,"",0,"").handle(peer);
                    h.assertValueEqual(b.code(),"access_denied","Network B cannot discard network A's retained input");
                    h.assertValueEqual(integer(b,"pending"),1L,"B did not mutate the retained queue");
                    h.assertTrue(StockApplicationMenu.openPhysical(admin,retained),"Original authority may inspect retained input after target loss");
                    var recovery=admin.application();
                    h.assertValueEqual(recovery.view().field("may_withdraw"),new DataValue.BooleanValue(true),"Old authority still has the recovery action");
                    recovery.request(StockApplicationMenuRequest.Action.DISCARD,1,"",0,"").handle(admin);
                    h.assertValueEqual(recovery.code(),"discarded","Original grantor can explicitly recover old queue after target replacement");
                    h.assertValueEqual(integer(recovery,"pending"),0L,"Only the old authority discarded input");
                    h.assertValueEqual(archivedNextSequence(retained),sequence,"Recovery never resets the sequence watermark");
                    System.out.println("STOCK_APPLICATION replacement-network recovery checks passed");
                }).thenSucceed();
            });
        });
    }

    private static void restrictedGrantorCannotBorrowProxyPermissions(GameTestHelper h, GameProfile profile, MenuActor viewer, StockApplicationMenu view) {
        var level=h.getLevel(); var server=level.getServer(); var players=server.getPlayerList();
        var other=Objects.requireNonNull(server.getLevel(Level.NETHER));
        try (var account=new TestAccount(level,profile)) {
            account.player.teleportTo(other,0.5,80,0.5,0,0);
            account.player.setGameMode(GameType.SURVIVAL);
            view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(viewer);
            h.assertValueEqual(view.view().field("status"),text("paused"),"Cross-dimension Survival uses the same identity in the target world");
            account.player.setGameMode(GameType.ADVENTURE);
            h.assertTrue(players.getPlayer(profile.getId())==account.player && account.player.serverLevel()==other && !account.player.mayBuild(),
                "Registered real ServerPlayer is restricted in another dimension");
            view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(viewer);
            h.assertValueEqual(view.view().field("status"),text("permission"),"Cross-dimension account cannot borrow default proxy abilities");
        }
        h.assertTrue(players.getPlayer(profile.getId())==null,"Grantor has actually logged out");
        view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(viewer);
        h.assertValueEqual(view.view().field("status"),text("permission"),"Offline saved Adventure account remains restricted");
        try (var account=new TestAccount(level,profile)) {
            h.assertTrue(!account.player.mayBuild(),"Vanilla reload confirms the saved restricted account");
            account.player.setGameMode(GameType.SURVIVAL);
        }
        view.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(viewer);
        h.assertValueEqual(view.view().field("status"),text("paused"),"Authoritative saved Survival state authorizes the still-paused installation");
        System.out.println("STOCK_APPLICATION registered cross-dimension and saved-offline grantor checks passed");
    }
    private static final class TestAccount implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        TestAccount(ServerLevel level, GameProfile profile) {
            var cookie=CommonListenerCookie.createInitial(profile,false);
            var connection=new Connection(PacketFlow.SERVERBOUND);
            channel=new EmbeddedChannel(connection);
            // Test transport only: advertise Create's mandatory login synchronization payload.
            net.neoforged.neoforge.network.registration.NetworkRegistry.onMinecraftRegister(connection,
                Set.of(ResourceLocation.parse("create:sync_edge_group")));
            player=new ServerPlayer(level.getServer(),level,profile,cookie.clientInformation());
            level.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        }
        @Override public void close() {
            player.serverLevel().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }
    private static long archivedNextSequence(StockLinkOutputBlockEntity output) {
        var archive=output.saveWithFullMetadata(output.getLevel().registryAccess()).getCompound("BiocomputingStockApplication");
        return StockApplicationCodec.load(archive,UUID.randomUUID()).nextSequence();
    }

    private static StockApplicationMenuRequest confirm(StockApplicationMenu menu,String address,long quantity) {
        return menu.request(StockApplicationMenuRequest.Action.CONFIRM,-1,"demo-paper",quantity,address);
    }
    private static XBusWire wire(String a,String ap,String b,String bp) {
        return new XBusWire(new XBusWire.Pin(a,a,ap),new XBusWire.Pin(b,b,bp),true);
    }
    private static DataValue.TextValue text(String value) {return new DataValue.TextValue(value);}
    private static DataValue.IntegerValue number(long value) {return new DataValue.IntegerValue(value);}
    private static int rows(StockApplicationMenu menu,String key) {return ((DataValue.ListValue)menu.view().field(key)).values().size();}
    private static long integer(StockApplicationMenu menu,String key) {return ((DataValue.IntegerValue)menu.view().field(key)).value();}
    private static void reportFaults(GameTestHelper h,List<ComputerBlockEntity> machines) {
        h.runAfterDelay(20,()->{
            for (int i=0;i<machines.size();i++) {
                var state=host(machines.get(i)).flowSnapshot().orElseThrow().state();
                if (state.status()==FlowRuntime.Status.FAILED) h.fail("Computer "+i+" halted: "+state.diagnostic());
            }
            reportFaults(h,machines);
        });
    }
    static ProgramDocument submitSettings(BlockPos port,DataValue approved) {
        var draft=new ProgramWorkspace(document("stock_application_recipe_submit")); var body=draft.flow().orElseThrow();
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(Map.of(
            "target",BlockSnapshotReader.position(port),"approved_scope",approved)),body.canvas(),body.inline(),body.execution(),body.compilation())));
        return draft.snapshot();
    }
    /** Separate in-shell serial wire; Create itself connects the remote stock link wirelessly. */
    record Workshop(ProductionPortBehaviour port, BlockPos position, UUID network, Container chest, PackagerBlockEntity packager) {
        static Workshop place(GameTestHelper h,BlockPos panel,BlockPos pack,ComputerBlockEntity serial) {
            var l=h.getLevel();
            host(serial).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
            l.setBlock(panel.west(),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
            l.setBlock(panel,CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST),3);
            l.setBlock(pack.west(),Blocks.CHEST.defaultBlockState(),3);
            l.setBlock(pack,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
            l.setBlock(pack.north(),AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.WALL).setValue(PackagerLinkBlock.FACING,Direction.NORTH),3);
            var link=(PackagerLinkBlockEntity)l.getBlockEntity(pack.north());var network=UUID.randomUUID();
            LogisticallyLinkedBehaviour.remove(link.behaviour);link.behaviour.freqId=network;LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
            var chest=(Container)l.getBlockEntity(pack.west());chest.setItem(0,new ItemStack(Items.STONE,16));
            return new Workshop(((SmartBlockEntity)l.getBlockEntity(panel)).getBehaviour(ProductionPortBehaviour.TYPE),panel,network,chest,(PackagerBlockEntity)l.getBlockEntity(pack));
        }
        void grant(GameTestHelper h,MenuActor actor,ComputerBlockEntity serial) {
            actor.go(position);var link=new ItemStack(AllBlocks.STOCK_LINK.asItem()); var frequency=new CompoundTag();frequency.putUUID("Freq",network);
            link.set(DataComponents.BLOCK_ENTITY_DATA,CustomData.of(frequency));actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,link);
            var candidate=port.discover(actor).stream().filter(b->b.member().equals(serial.getBlockPos())).findFirst().orElseThrow();
            h.assertValueEqual(port.configure(actor,port.revision(),Optional.of(candidate),"press"),"configured","Player binds a particular sender and stock network");
        }
        void checkShipment(GameTestHelper h) {
            h.assertValueEqual(chest.getItem(0).getCount(),12,"Exactly four extracted from real inventory");
            h.assertTrue(PackageItem.isPackage(packager.heldBox),"Create packed a parcel");
            h.assertValueEqual(PackageItem.getAddress(packager.heldBox),"press","Workshop, not customer address");
            var contents=PackageItem.getContents(packager.heldBox);int count=0;
            for(int slot=0;slot<contents.getSlots();slot++){var stack=contents.getStackInSlot(slot); if(!stack.isEmpty()){
                h.assertTrue(ItemStack.isSameItemSameComponents(stack,new ItemStack(Items.STONE)),"Exact physical ingredients");count+=stack.getCount();}}
            h.assertValueEqual(count,4,"No manufactured or duplicate goods");
        }
    }
    private static ProgramDocument selectBook(ProgramDocument source,BlockPos core) {
        var draft=new ProgramWorkspace(source); var body=draft.flow().orElseThrow();
        var inputs=new LinkedHashMap<>(body.initial().fields());
        inputs.put("target",BlockSnapshotReader.position(core));
        var rows=new ArrayList<>(((DataValue.ListValue)inputs.get("commands")).values());
        var seek=new LinkedHashMap<>(((DataValue.RecordValue)rows.get(1)).fields());
        seek.put("d",LibraryTrackAddress.from(core,Direction.EAST,core.north()).value());
        rows.set(1,new DataValue.RecordValue(seek)); inputs.put("commands",new DataValue.ListValue(rows));
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(inputs),
            body.canvas(),body.inline(),body.execution(),body.compilation())));
        return draft.snapshot();
    }

    /** A separate physical Library wire inside the shell. Nothing passes a Java recipe or catalogue into the programs. */
    private static net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity readOnlyBook(
            GameTestHelper h,BlockPos pos,ComputerBlockEntity head,MenuActor actor) {
        var level=h.getLevel();
        var state=CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING,Direction.EAST);
        level.setBlock(pos,state.setValue(LibraryCoreBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),3);
        level.setBlock(pos.above(),state.setValue(LibraryCoreBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
        var library=(LibraryBlockEntity)level.getBlockEntity(pos);
        library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("catalog-rom"),0,UUID.randomUUID(),0));
        library.initialize();
        level.setBlock(pos.north(),Blocks.CHISELED_BOOKSHELF.defaultBlockState(),3);
        level.setBlock(pos.north().west(),Blocks.LECTERN.defaultBlockState(),3);
        var shelf=(net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var book=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,new net.minecraft.world.item.component.WritableBookContent(
            List.of(net.minecraft.server.network.Filterable.passThrough("""
                {"version":2,"type":"standard","inputs":[{"item":"minecraft:stone","count":2}],"outputs":[{"item":"minecraft:paper","count":3}],"address":"press"}
                """))));
        var media=new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),CBConfigs.SERVER.biocomputing.current(true).archive());
        book=media.put(book,"note",new LibraryEntry(LibraryEntry.Kind.DATA,text("leave intact")));
        shelf.setItem(0,new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book,"Recipes"));
        level.setBlock(pos.west(),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
        host(head).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
        var service=library.getBehaviour(LibraryServiceBehaviour.TYPE);
        h.assertValueEqual(service.configure(0,new LibraryServiceConfiguration(Optional.empty(),
            Map.of("Recipes",LibraryServiceConfiguration.Access.READ_ONLY))),"configured","Read-only book");
        actor.go(pos);var control=library.getBehaviour(LibraryControlBehaviour.TYPE);
        var delegate=control.discover(actor).stream().filter(c->c.member().equals(head.getBlockPos()) && c.controller().isEmpty()).findFirst().orElseThrow();
        h.assertValueEqual(control.configure(actor,0,Optional.of(delegate)),"configured","Only Library's physical reader is delegated");
        h.assertValueEqual(control.configureSerial(actor,1,true),"configured","Explicit Library wire");
        return shelf;
    }

    private static ProgramDocument document(String id) {
        java.util.function.Function<ResourceLocation,java.io.InputStream> open=location ->
            BiocomputingStockApplicationGameTests.class.getClassLoader().getResourceAsStream("assets/"+location.getNamespace()+"/"+location.getPath());
        var selected=ProgramExampleCatalog.load(open).stream().filter(entry->entry.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(selected,open,CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
    }
    private static ProgramDocument inputs(ProgramDocument source,BlockPos output,BlockPos reader) {
        var draft=new ProgramWorkspace(source); var flow=draft.flow().orElseThrow();
        var fields=new LinkedHashMap<>(flow.initial().fields());
        if(fields.containsKey("output")) fields.put("output",BlockSnapshotReader.position(output));
        if(fields.containsKey("reader")) fields.put("reader",BlockSnapshotReader.position(reader));
        draft.setFlow(Optional.of(new FlowDocumentBody(flow.inputs(),flow.steps(),new DataValue.RecordValue(fields),flow.canvas(),flow.inline(),flow.execution(),flow.compilation())));
        return draft.snapshot();
    }
    static final class MenuActor extends FakePlayer {
        private int next;
        MenuActor(ServerLevel level,String name) {super(level,new GameProfile(UUID.randomUUID(),name)); gameMode.changeGameModeForPlayer(GameType.SURVIVAL);}
        void go(BlockPos pos) {moveTo(Vec3.atCenterOf(pos));}
        @Override public OptionalInt openMenu(MenuProvider provider,Consumer<RegistryFriendlyByteBuf> opening) {
            int id=++next; containerMenu=provider.createMenu(id,getInventory(),this); return OptionalInt.of(id);
        }
        StockApplicationMenu application() {return (StockApplicationMenu)containerMenu;}
        void openNative(StockTickerBlockEntity ticker) {
            go(ticker.getBlockPos());
            openMenu(ticker.new RequestMenuProvider(),b->{});
            StockApplicationMenuRequest.open(containerMenu.containerId).handle(this);
        }
    }
}
