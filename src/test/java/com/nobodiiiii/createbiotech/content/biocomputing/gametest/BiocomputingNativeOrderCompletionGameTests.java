package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
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

/** Opt-in device proof: separate recovery and physical segmented archive variants. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingNativeOrderCompletionGameTests {
    @GameTest(template="empty", timeoutTicks=150000)
    public static void originalInputIsAcknowledgedOnlyAfterNativeDelivery(GameTestHelper h) {
        boolean scoped = Boolean.getBoolean("biocomputing.nativeCompletionScopedArchive");
        if (!scoped) h.runAfterDelay(18000, () -> h.fail("Native order completion exceeded its original tick window"));
        boolean checkpoint = Boolean.getBoolean("biocomputing.nativeCompletionCheckpoint");
        boolean recovery = scoped || Boolean.getBoolean("biocomputing.nativeCompletionRecovery");
        h.assertTrue(!scoped || !checkpoint,"Scoped cold recovery requires its own complete checkpoint contract");
        var cutFile = java.nio.file.Path.of("native-completion-cut.nbt");
        if (checkpoint) h.assertTrue(!java.nio.file.Files.exists(cutFile), "Never reseed an existing in-flight checkpoint");
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1,1,1)); var max = min.offset(6,scoped?6:4,6);
        var gatePos = min.offset(6,1,2);
        var disk = scoped ? new ScopedDisk(h,min.offset(4,1,4)) : null;
        var linkPos = gatePos.east(5); var outputPos = linkPos.north(); var readerPos = linkPos.south();
        var tickerPos = linkPos.east(2);
        var materialPos=gatePos.east(2).north(4);var materialLinkPos=materialPos.east();
        var names = new ArrayList<>(List.of("wire","sample","prepare","order","item","handoff","ledger","supply","packet","finish","guard","ack"));
        var roles = new ArrayList<>(List.of("serial_exchange","stock_native_sample","stock_native_prepare","stock_native_plain_order","stock_native_item","stock_native_handoff","stock_native_identity_selected","stock_native_supply_followup","stock_native_packet","stock_native_finish","stock_native_guard","stock_native_ack"));
        var places = new ArrayList<>(List.of(gatePos.west(), min.offset(1,1,1),min.offset(1,1,4),min.offset(4,1,5),min.offset(3,1,1),min.offset(1,1,3),min.offset(2,1,3),min.offset(4,1,3),min.offset(3,1,4),min.offset(2,1,4),min.offset(4,1,1),min.offset(2,1,5)));
        if (recovery) {
            roles.set(11,"stock_native_ack_current");
            names.add("renewal"); roles.add("stock_native_ack_exchange"); places.add(min.offset(3,1,5));
        }
        if(scoped) {
            names.set(6,"archive");roles.set(6,"stock_native_scope_archive");roles.set(5,"stock_native_scope_handoff");
            roles.set(7,"stock_native_scope_live");roles.set(8,"stock_native_packet_scoped");roles.set(9,"stock_native_scope_finish");
            names.addAll(List.of("read","check","identity","store","coordinator","adapter","b","c"));
            roles.addAll(List.of("library_segmented_session_read","stock_native_scope_check","stock_native_scope_identity","library_segmented_session_store",
                "library_segmented_session_coordinator","library_segmented_read_adapter","library_service_b","library_service_c"));
            // Fit the original seven-block enclosure limit, rather than silently expanding it.
            var bookWireOwner=disk.pos.west(2);
            var reserved=Set.of(disk.pos,disk.pos.north(),disk.pos.west(),disk.pos.north().west(),gatePos.west(),bookWireOwner);
            var remaining=new ArrayList<BlockPos>();
            for(int z=1;z<=5;z++)for(int x=1;x<=5;x++){var p=min.offset(x,1,z);if(!reserved.contains(p))remaining.add(p);}
            h.assertValueEqual(remaining.size(),19,"Physical Computer and disk positions do not overlap");
            places.clear();places.add(gatePos.west());places.addAll(remaining.subList(0,18));places.add(bookWireOwner);places.add(remaining.getLast());
            // Two-block-tall workers need their own floor and headroom. Their upper halves touch the ceiling.
            names.addAll(List.of("buffer","slice","pump","project","decode","collect","verify","stream"));
            roles.addAll(List.of("library_segmented_session_buffer","library_segmented_step_slice","library_segmented_step_pump",
                "library_segmented_range_reply","library_segmented_session_read_decode","library_segmented_archive_read_collect",
                "library_segmented_step_read_verify","library_segmented_step_read_stream"));
            for (int i=0;i<8;i++) places.add(min.offset(1+i%5,4,i<5?1:5));
        }
        var machines = new ArrayList<ComputerBlockEntity>();
        for (var pos : places) machines.add(computer(h,pos,VillagerProfession.CLERIC,5));
        for (var p : BlockPos.betweenClosed(min,max))
            if (p.getX()==min.getX() || p.getX()==max.getX() || p.getY()==min.getY() || p.getY()==max.getY() || p.getZ()==min.getZ() || p.getZ()==max.getZ())
                level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
        level.setBlock(gatePos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gatePos);
        var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
        for (int i=0;i<names.size();i++) {
            var cpu=host(machines.get(i));
            var installed=inputs(document(roles.get(i)),materialPos,readerPos,outputPos);
            if(scoped)installed=disk.inputs(installed,roles.get(i));
            try { cpu.installDocument(installed,ComputerRuntime.MemoryPolicy.RESET); }
            catch (RuntimeException invalid) { throw new IllegalStateException("Install "+roles.get(i)+" at "+places.get(i)+": "+invalid.getMessage(),invalid); }
            cpu.configureEndpoint(new EndpointConfiguration(names.get(i),true));
            members.put(names.get(i),new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),places.get(i)));
        }
        var authority=host(machines.getFirst());
        var admin = new MenuActor(level,"stock-admin"); admin.go(gatePos.east());
        var wires=new LinkedHashMap<>(Map.of("ws",wire("wire","x0","sample","x0"),"wd",wire("wire","x0","packet","x1"),
            "sv",wire("sample","x1","prepare","x0"),"vh",wire("prepare","x0","handoff","x0"),
            "vo",wire("prepare","x1","order","x0"),"oi",wire("order","x1","item","x0"),
            "hl",wire("handoff","x1","ledger","x0"),"hc",wire("handoff","x1","supply","x0"),
            "cd",wire("supply","x1","packet","x0")));
        wires.put("cf",wire("supply","x0","finish","x0"));wires.put("fg",wire("finish","x0","guard","x0"));
        wires.put("ga",wire("guard","x1","ack","x0"));wires.put("wf",wire("wire","x0","finish","x1"));wires.put("wa",wire("wire","x0","ack","x1"));
        if (recovery) {
            wires.remove("wa");
            wires.put("ar",wire("ack","x1","renewal","x0"));
            wires.put("rw",wire("renewal","x1","wire","x0"));
        }
        var signals=new LinkedHashMap<>(Map.of("vh_signal",signal("prepare","p0","handoff","p1"),
            "hl_signal",signal("handoff","p0","ledger","p0"),"hc_signal",signal("handoff","p0","supply","p0"),"cf_signal",signal("supply","p1","finish","p0"),"fg_signal",signal("finish","p1","guard","p0")));
        if(scoped) {
            wires.clear();
            String[][] links={{"wire","x0","sample","x0"},{"wire","x0","packet","x1"},
                {"sample","x1","prepare","x0"},{"prepare","x0","handoff","x0"},{"prepare","x1","order","x0"},{"order","x1","item","x0"},
                {"handoff","x1","supply","x1"},{"supply","x1","archive","x0"},{"supply","x0","packet","x0"},
                {"archive","x1","read","x0"},{"archive","x1","check","x0"},{"check","x1","identity","x0"},{"identity","x1","store","x0"},
                {"store","x1","coordinator","x1"},{"coordinator","x0","buffer","x0"},{"buffer","x1","slice","x0"},
                {"slice","x1","pump","x0"},{"pump","x1","project","x0"},{"project","x1","adapter","x0"},
                {"read","x1","coordinator","x1"},{"coordinator","x1","decode","x0"},{"decode","x1","collect","x0"},
                {"collect","x1","verify","x0"},{"verify","x1","stream","x0"},{"stream","x1","project","x0"},
                {"adapter","x1","b","x0"},{"b","x1","c","x0"},{"handoff","x1","finish","x0"},{"finish","x1","packet","x0"},
                {"finish","x0","guard","x0"},{"guard","x1","ack","x0"},{"ack","x1","renewal","x0"},{"renewal","x1","wire","x0"}};
            for(int i=0;i<links.length;i++){var p=links[i];wires.put("bus"+i,wire(p[0],p[1],p[2],p[3]));}
            signals.clear();
            String[][] pins={{"prepare","p0","handoff","p1"},{"supply","p0","archive","p1"},{"archive","p0","read","p0"},
                {"archive","p0","check","p0"},{"supply","p1","handoff","p0"},{"handoff","p0","finish","p0"},{"finish","p1","guard","p0"},
                {"coordinator","p0","store","p0"},{"coordinator","p0","decode","p0"},{"coordinator","p0","read","p1"}};
            for(int i=0;i<pins.length;i++){var p=pins[i];signals.put("signal"+i,signal(p[0],p[1],p[2],p[3]));}
        }
        configureThroughMenu(h,admin,controller,new ClusterConfiguration(members,Map.of(),Map.of(),wires,signals));
        authority.configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(
            new ClusterMemberEditorAccess(controller,machines.getFirst()),admin,"s0","stock")));
        authority.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
        if(scoped)disk.authorize(admin,machines.get(19));

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
        level.setBlock(linkPos.below().west(),Blocks.CHEST.defaultBlockState(),3);
        var originalChest=(net.minecraft.world.Container)level.getBlockEntity(linkPos.below().west());
        level.setBlock(materialPos,CBBlocks.STOCK_LINK_OUTPUT.get().defaultBlockState().setValue(StockLinkOutputBlock.FACING,Direction.EAST),3);
        level.setBlock(materialLinkPos.below().west(),Blocks.CHEST.defaultBlockState(),3);
        level.setBlock(materialLinkPos.below(),AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
        level.setBlock(materialLinkPos,AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
        var materialLink=(PackagerLinkBlockEntity)level.getBlockEntity(materialLinkPos);materialLink.placedBy=admin.getUUID();
        h.assertTrue(!materialLink.behaviour.freqId.equals(link.behaviour.freqId),"Alternate stock is a separate real logistics network");
        var materialOutput=(StockLinkOutputBlockEntity)level.getBlockEntity(materialPos);
        var packager=(com.simibubi.create.content.logistics.packager.PackagerBlockEntity)level.getBlockEntity(materialLinkPos.below());
        var chest=(net.minecraft.world.Container)level.getBlockEntity(materialLinkPos.below().west());
        var exact=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER,16);
        h.assertTrue(exact.has(net.minecraft.core.component.DataComponents.RARITY),"Remove a real default component");
        exact.remove(net.minecraft.core.component.DataComponents.RARITY);
        chest.setItem(0,exact.copy());chest.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER,16));
        originalChest.setItem(0,exact.copy());
        for(int n=1;n<=4;n++)level.setBlock(gatePos.east().north(n),cable,3);



        // Persist an authoritative Survival account: a menu proxy must never invent reader permissions.
        try (var account=new TestAccount(level,admin.getGameProfile())) { account.player.setGameMode(GameType.SURVIVAL); }
        for(int delay:List.of(800,2000,4000,8000,14000))h.runAfterDelay(delay,()->
                System.out.println("NATIVE_COMPLETION @"+delay+" "+machines.stream().map(cpu->{var v=host(cpu).flowSnapshot().orElseThrow().state();return v.status()+"/"+v.assembly().map(r->r.pc())+"/"+v.diagnostic();}).toList()));
        h.runAfterDelay(30,()->{
            admin.go(outputPos); h.assertTrue(StockApplicationMenu.openPhysical(admin,output),"Output menu opens");
            admin.application().request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            h.assertValueEqual(admin.application().code(),"bound","Only the wire Computer is authorized");
            admin.go(readerPos); h.assertTrue(ReaderMenu.open(admin,reader,"ok"),"Reader grant menu opens");
            var menu=(ReaderMenu)admin.containerMenu;
            var ids=((DataValue.ListValue)menu.view().field("catalog")).values();
            int component=java.util.stream.IntStream.range(0,ids.size()).filter(i -> ((DataValue.RecordValue)ids.get(i)).field("id")
                .equals(text(CreateReadComponents.STOCK_APPLICATION))).findFirst().orElseThrow();
            menu.request(ReaderRequest.Action.GRANT,0,List.of(component)).handle(admin);
            h.assertValueEqual(((ReaderMenu)admin.containerMenu).code(),"configured","Only stock component is granted");
            admin.go(materialPos);h.assertTrue(StockApplicationMenu.openPhysical(admin,materialOutput),"Manage alternate material output");
            admin.application().request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            h.assertValueEqual(admin.application().code(),"bound","Alternate output authorizes the same unchanged wire Computer");
            admin.application().request(StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1,"",0,"alternate-supply").handle(admin);
            h.assertValueEqual(admin.application().code(),"materials_granted","Explicit alternate stock/route grant");
            admin.openNative(ticker);
            admin.application().request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            var frontend=admin.application();
            for(int index=0;index<machines.size();index++) if(index!=1) host(machines.get(index)).setPaused(false);
            h.startSequence().thenWaitUntil(()->{
                frontend.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(frontend.view().field("status"),text("ready"),"Original Reader and grant ready");
            }).thenExecute(()->{
                admin.go(tickerPos);admin.openMenu(ticker.new RequestMenuProvider(),b->{});
                var requested=exact.copy();requested.setCount(2);
                var nativeOrder=com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(List.of(
                    new com.simibubi.create.content.logistics.BigItemStack(requested,7)));
                StockNativeOrderRequest.create(admin.containerMenu.containerId,nativeOrder,"alternate-supply",level.registryAccess()).handle(admin);
                admin.application().request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"").handle(admin);
                h.assertValueEqual(admin.application().code(),"input_queued","Actual native input is queued, never manually supplied to ISA");
                if (Boolean.getBoolean("biocomputing.stockMenuOnly")) {
                    admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                    h.assertValueEqual(integer(admin.application(),"pending"),1L,"Exactly one pending native input");
                    h.assertValueEqual(originalChest.getItem(0).getCount(),16,"Original stock not dispatched");
                    h.assertValueEqual(chest.getItem(0).getCount(),16,"Alternate stock untouched before archive");
                    h.assertTrue(disk.entries().values().isEmpty(),"No book entry invented during menu admission");
                    System.out.println("STOCK_MENU admission only: pending=1, original=16, alternate=16, book empty; no shipment or ACK");
                    h.succeed();
                } else host(machines.get(1)).setPaused(false);
            }).thenWaitUntil(()->{
                if(scoped) {
                    var state=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                    if (state.status()!=FlowRuntime.Status.COMPLETE && state.diagnostic().isEmpty())
                        h.assertTrue(!disk.entries().values().isEmpty(),"Waiting for actual book commit");
                }
            }).thenExecute(()->{
                if(scoped) {
                    if (disk.entries().values().isEmpty()) {
                        var state=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                        h.fail("Supply stopped before the book was committed: "+state.diagnostic()+" / "+state.locals().field("reply"));
                    }
                    h.assertValueEqual(chest.getItem(0).getCount(),16,"Book archival precedes actual native extraction");
                    h.assertValueEqual(originalChest.getItem(0).getCount(),16,"No original dispatch during archival");
                    System.out.println("NATIVE_SCOPED binding committed to physical book; both inventories still unchanged");
                }
            }).thenWaitUntil(()->{
                var done=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(done.status(),FlowRuntime.Status.COMPLETE,"Supply ISA completes after the verified archive");
            }).thenExecute(()->{
                var done=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                var reply=(DataValue.RecordValue)done.locals().field("reply");
                System.out.println("NATIVE_COMPLETION receipt "+reply);
                h.assertValueEqual(reply.field("code"),text(scoped?"accepted":"ok"),"Public program receives the actual admitted original job");
                h.assertValueEqual(reply.field("order"),scoped?disk.binding().field("j"):done.locals().field("job"),"Business identity survives submission and fresh query correlation");
                h.assertValueEqual(reply.field("address"),text("alternate-supply"),"Frozen route matches original input address");
                if(scoped) {
                    var savedSource=(DataValue.RecordValue)disk.binding().field("s");var scope=(DataValue.RecordValue)savedSource.field("r");
                    h.assertValueEqual(scope.field("source"),text(authority.communicationIdentity().orElseThrow().toString()),"Archive keeps actual sending Computer identity");
                    h.assertValueEqual(scope.field("target"),BlockSnapshotReader.position(materialPos),"Archive keeps actual output target");
                    h.assertValueEqual(scope.field("address"),text("alternate-supply"),"Archive keeps authorized route");
                    var queued=StockApplicationCodec.load(output.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockApplication"),UUID.randomUUID()).inputs().getFirst();
                    var originalHead=(DataValue.RecordValue)savedSource.field("h");
                    h.assertValueEqual(savedSource.field("d"),text(queued.device().toString()),"Book keeps actual input device");
                    h.assertValueEqual(originalHead.field("order"),queued.request().nativeOrder().orElseThrow(),"Exact native order NBT is archived, not a reconstructed summary");
                    h.assertValueEqual(originalHead.field("sequence"),new DataValue.IntegerValue(queued.sequence()),"Original input sequence is archived");
                }
            }).thenWaitUntil(()->h.assertTrue(com.simibubi.create.content.logistics.box.PackageItem.isPackage(packager.heldBox),"Alternate real packager produces native package"))
            .thenExecute(()->{
                h.assertValueEqual(chest.getItem(0).getCount(),9,"Exactly seven component-matched items extracted from alternate network");
                h.assertValueEqual(chest.getItem(1).getCount(),16,"Plain paper was not substituted");
                h.assertValueEqual(originalChest.getItem(0).getCount(),16,"Original network was not dispatched");
                var contents=com.simibubi.create.content.logistics.box.PackageItem.getContents(packager.heldBox);
                h.assertValueEqual(contents.getStackInSlot(0).getCount(),7,"Package contains requested count, not native stack count two");
                h.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(contents.getStackInSlot(0),exact),"Component removal reaches the physical package");
                admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(integer(admin.application(),"pending"),1L,"Dispatch receipt does not ACK original native input");
                System.out.println("NATIVE_COMPLETION alternate inventory -> physical native package -> public ISA receipt; original input retained, no delivery claim");
                if (checkpoint) {
                    // This opt-in run proves preparation only, NOT delivery/recovery. End the only
                    // test before the later unpack step so the server saves live machines normally.
                    // Metadata locates the real world; never restore host state from this file.
                    var cut = new net.minecraft.nbt.CompoundTag();
                    cut.putLong("Process", ProcessHandle.current().pid());
                    cut.putBoolean("RecoveryVariant", recovery);
                    cut.putLong("Controller", gatePos.asLong());
                    cut.putLong("OriginalOutput", outputPos.asLong());
                    cut.putLong("Reader", readerPos.asLong());
                    cut.putLong("MaterialOutput", materialPos.asLong());
                    cut.putLong("Packager", materialLinkPos.below().asLong());
                    cut.putLong("Ticker", tickerPos.asLong());
                    cut.putUUID("Grantor", admin.getUUID());
                    cut.putString("Job", ((DataValue.TextValue)host(machines.get(7)).flowSnapshot().orElseThrow().state().locals().field("job")).value());
                    for (int i=0;i<names.size();i++) cut.putLong(names.get(i), places.get(i).asLong());
                    try { net.minecraft.nbt.NbtIo.write(cut, cutFile); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    System.out.println("NATIVE_COMPLETION CHECKPOINT_ONLY: packaged, input still pending; no unpack or ACK; pid="+ProcessHandle.current().pid());
                    h.succeed();
                }
            }).thenIdle(100).thenExecute(()->{
                admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(integer(admin.application(),"pending"),1L,"Physical packaging and polling cannot ACK before native unpack");
            }).thenWaitUntil(()->h.assertTrue(com.simibubi.create.content.logistics.box.PackageItem.isPackage(
                new com.simibubi.create.content.logistics.packager.PackagerItemHandler(packager).extractItem(0,1,true)),"Finished package can actually be taken"))
            .thenExecute(()->{
                var box=new com.simibubi.create.content.logistics.packager.PackagerItemHandler(packager).extractItem(0,1,false);
                h.assertTrue(com.simibubi.create.content.logistics.box.PackageItem.isPackage(box),"Move the existing package, never copy a package into inventory");
                var pos=materialLinkPos.east(4).below();
                level.setBlock(pos.west(),Blocks.CHEST.defaultBlockState(),3);
                level.setBlock(pos,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
                level.setBlock(pos.above(),AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
                var receiverLink=(PackagerLinkBlockEntity)level.getBlockEntity(pos.above());
                LogisticallyLinkedBehaviour.remove(receiverLink.behaviour);receiverLink.behaviour.freqId=materialLink.behaviour.freqId;LogisticallyLinkedBehaviour.keepAlive(receiverLink.behaviour);
                var receiver=(com.simibubi.create.content.logistics.packager.PackagerBlockEntity)level.getBlockEntity(pos);
                h.assertTrue(new com.simibubi.create.content.logistics.packager.PackagerItemHandler(receiver).insertItem(0,box,false).isEmpty(),"Actual Create unpack consumes transported package");
                var destination=(net.minecraft.world.Container)level.getBlockEntity(pos.west());
                h.assertValueEqual(destination.getItem(0).getCount(),7,"Destination receives the seven real items");
                h.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(destination.getItem(0),exact),"Unpacking retains components");
                System.out.println("NATIVE_COMPLETION package manually transferred and natively unpacked; now permit completed receipt");
            }).thenWaitUntil(()->{
                var state=host(machines.get(11)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(state.status(),FlowRuntime.Status.COMPLETE,"ACK program finishes: "+state.diagnostic());
            }).thenExecute(()->{
                var ack=host(machines.get(11)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(((DataValue.RecordValue)ack.locals().field("reply")).field("code"),text("acknowledged"),"Only actual completed delivery permits public fenced ACK");
                admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(integer(admin.application(),"pending"),0L,"Exactly the original head is acknowledged");
                h.assertValueEqual(originalChest.getItem(0).getCount(),16,"Original native inventory still untouched");
                h.assertValueEqual(chest.getItem(0).getCount(),9,"No repeated alternate extraction during polling");
                if(scoped)disk.assertFinal();
                System.out.println("NATIVE_COMPLETION public query -> verified delivery -> fenced ACK, no duplicate extraction");
            }).thenSucceed();
        });
    }
    /** Exercise both packet bounds at logistics scale without replacing any real controller operation. */
    private static void configureThroughMenu(GameTestHelper h, MenuActor admin, ClusterControllerBlockEntity controller,
                                             ClusterConfiguration config) {
        var level = h.getLevel();
        var provider = ClusterControllerMenu.provider(controller);
        admin.openMenu(provider,provider::writeOpeningData);
        var originalConnection = admin.connection;
        var response = new java.util.concurrent.atomic.AtomicReference<ClusterControlResponse>();
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        try {
            admin.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), admin, CommonListenerCookie.createInitial(admin.getGameProfile(),false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                            && custom.payload() instanceof ClusterControlResponse reply) {
                        var replyBytes = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
                        try {
                            reply.write(replyBytes);
                            response.set(new ClusterControlResponse(replyBytes));
                        } finally { replyBytes.release(); }
                    }
                }
            };
            provider.writeOpeningData(buffer);
            var nonce = buffer.readUUID();
            var limits = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive();
            var bytes = ClusterControlWire.encodeConfiguration(config,limits);
            new ClusterControlRequest(admin.containerMenu.containerId,nonce,1,controller.configurationRevision(),
                ClusterControlSession.Action.CONFIGURE,bytes).write(buffer);
            new ClusterControlRequest(buffer).handle(admin);
            var reply = java.util.Objects.requireNonNull(response.get(),"Missing controller response");
            h.assertValueEqual(reply.code(),"configured","Menu rejects application wiring: "+reply.detail());
            var returned = ClusterControlWire.decodeView(reply.state(),reply.archiveLimits());
            h.assertValueEqual(returned.configuration(),config,"Response preserves the complete pin map");
            h.assertValueEqual(returned.candidates().size(),config.members().size(),"Member list is not truncated");
            h.assertValueEqual(controller.routingState().orElseThrow().configuration(),config,"Actual configuration saved");
            System.out.println("STOCK_MENU configured members="+config.members().size()+" XBus="+config.wires().size()
                +" SIO="+config.signals().size()+" request_bytes="+bytes.length+" view_bytes="+reply.state().length);
        } finally { admin.connection=originalConnection; buffer.release(); }
    }

    private static SimpleIoWire signal(String a,String ap,String b,String bp){return new SimpleIoWire(new SimpleIoWire.Pin(a,a,ap),new SimpleIoWire.Pin(b,b,bp),true);}

    /** Physical fixture only. Programs receive neither a supplied ledger nor a Java material request. */
    private static final class ScopedDisk {
        final GameTestHelper h;final BlockPos pos;
        final LibraryBlockEntity library;
        final net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity shelf;
        final LibraryBookMedia media;
        final net.minecraft.world.item.ItemStack original;
        ScopedDisk(GameTestHelper h,BlockPos pos) {
            this.h=h;this.pos=pos;var level=h.getLevel();
            var state=CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING,Direction.EAST);
            level.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(pos,state.setValue(LibraryCoreBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),3);
            level.setBlock(pos.above(),state.setValue(LibraryCoreBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
            library=(LibraryBlockEntity)level.getBlockEntity(pos);
            library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("scoped-orders"),0,UUID.randomUUID(),0));library.initialize();
            level.setBlock(pos.north(),Blocks.CHISELED_BOOKSHELF.defaultBlockState(),3);
            shelf=(net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
            level.setBlock(pos.north().west(),Blocks.LECTERN.defaultBlockState(),3);
            level.setBlock(pos.west(),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
            media=new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),CBConfigs.SERVER.biocomputing.current(true).archive());
            var book=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
            book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,new net.minecraft.world.item.component.WritableBookContent(
                List.of(net.minecraft.server.network.Filterable.passThrough("Physical scoped order ledger; preserve these notes."))));
            book=media.put(book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.ListValue(List.of())));
            book=new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book,"plant");
            shelf.setItem(2,book.copy());original=book.copy();
        }
        ProgramDocument inputs(ProgramDocument source,String role) {
            var draft=new ProgramWorkspace(source);var body=draft.flow().orElseThrow();var values=new LinkedHashMap<>(body.initial().fields());
            if(role.equals("library_segmented_read_adapter"))values.put("target",BlockSnapshotReader.position(pos));
            if(Set.of("library_segmented_session_store","library_segmented_session_read").contains(role)) {
                var rows=new ArrayList<>(((DataValue.ListValue)values.get("c")).values());
                var seek=new LinkedHashMap<>(((DataValue.RecordValue)rows.get(1)).fields());
                seek.put("d",LibraryTrackAddress.from(pos,Direction.EAST,shelf.getBlockPos()).value());
                rows.set(1,new DataValue.RecordValue(seek));values.put("c",new DataValue.ListValue(rows));
            }
            draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(values),body.canvas(),body.inline(),body.execution(),body.compilation())));
            return draft.snapshot();
        }
        void authorize(MenuActor actor,ComputerBlockEntity owner) {
            host(owner).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
            h.assertTrue(host(owner).serialDevice().view().ports().get("s0").gateway().isEmpty(),"Library wire is not an implicit second controller gateway");
            library.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0,new LibraryServiceConfiguration(Optional.empty(),Map.of("plant",LibraryServiceConfiguration.Access.READ_WRITE)));
            actor.go(pos);var control=library.getBehaviour(LibraryControlBehaviour.TYPE);
            var binding=control.discover(actor).stream().filter(c->c.member().equals(owner.getBlockPos())&&c.controller().isEmpty()).findFirst().orElseThrow();
            control.configure(actor,0,Optional.of(binding));control.configureSerial(actor,1,true);
        }
        DataValue.ListValue entries(){return (DataValue.ListValue)media.read(shelf.getItem(2)).get("reading").value();}
        DataValue.RecordValue binding(){h.assertValueEqual(entries().values().size(),1,"Exactly one persistent business binding");return (DataValue.RecordValue)entries().values().getFirst();}
        void assertFinal() {
            binding();var before=(DataValue.RecordValue)media.stat(original,"reading").field("version");
            var after=(DataValue.RecordValue)media.stat(shelf.getItem(2),"reading").field("version");
            h.assertValueEqual(after.field("medium"),before.field("medium"),"Same physical book identity");
            h.assertValueEqual(((DataValue.IntegerValue)after.field("revision")).value(),((DataValue.IntegerValue)before.field("revision")).value()+1,"Exactly one ledger write");
            h.assertValueEqual(shelf.getItem(2).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT),original.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT),"Original readable book pages are unchanged");
            System.out.println("NATIVE_SCOPED actual book -> real alternate stock -> native unpack -> original input ACK; one persistent binding and one ledger write");
        }
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
    private static XBusWire wire(String a,String ap,String b,String bp) {
        return new XBusWire(new XBusWire.Pin(a,a,ap),new XBusWire.Pin(b,b,bp),true);
    }
    private static DataValue.TextValue text(String value) {return new DataValue.TextValue(value);}
    private static DataValue.IntegerValue number(long value) {return new DataValue.IntegerValue(value);}
    private static int rows(StockApplicationMenu menu,String key) {return ((DataValue.ListValue)menu.view().field(key)).values().size();}
    private static long integer(StockApplicationMenu menu,String key) {return ((DataValue.IntegerValue)menu.view().field(key)).value();}
    private static ProgramDocument document(String id) {
        java.util.function.Function<ResourceLocation,java.io.InputStream> open=location ->
            BiocomputingNativeOrderCompletionGameTests.class.getClassLoader().getResourceAsStream("assets/"+location.getNamespace()+"/"+location.getPath());
        try(var stream=open.apply(ResourceLocation.parse("create_biotech:biocomputing/examples/"+id+".bcp"))){
            if(stream==null)throw new IllegalArgumentException("Missing public program "+id);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()),CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private static ProgramDocument inputs(ProgramDocument source,BlockPos output,BlockPos reader,BlockPos stock) {
        var draft=new ProgramWorkspace(source); var flow=draft.flow().orElseThrow();
        var fields=new LinkedHashMap<>(flow.initial().fields());
        if(fields.containsKey("output")) fields.put("output",BlockSnapshotReader.position(stock));
        if(fields.containsKey("target")) fields.put("target",BlockSnapshotReader.position(output));
        if(fields.containsKey("reader")) fields.put("reader",BlockSnapshotReader.position(reader));
        draft.setFlow(Optional.of(new FlowDocumentBody(flow.inputs(),flow.steps(),new DataValue.RecordValue(fields),flow.canvas(),flow.inline(),flow.execution(),flow.compilation())));
        return draft.snapshot();
    }
    private static final class MenuActor extends FakePlayer {
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
