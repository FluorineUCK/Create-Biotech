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
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
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

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingNativeOrderSupplyGameTests {
    @GameTest(template="empty", timeoutTicks=18000)
    public static void admittedNativeOrderUsesAlternateAuthorizedStockWithoutAcknowledgingTheInput(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1,1,1)); var max = min.offset(6,4,6);
        var gatePos = min.offset(6,1,2);
        var linkPos = gatePos.east(5); var outputPos = linkPos.north(); var readerPos = linkPos.south();
        var tickerPos = linkPos.east(2);
        var materialPos=gatePos.east(2).north(4);var materialLinkPos=materialPos.east();
        var names = List.of("wire","sample","prepare","order","item","handoff","ledger","supply","packet");
        var roles = List.of("serial_exchange","stock_native_sample","stock_native_prepare","stock_native_plain_order","stock_native_item","stock_native_handoff","stock_native_identity_selected","stock_native_supply","stock_native_packet");
        var places = List.of(gatePos.west(), min.offset(1,1,1),min.offset(1,1,4),min.offset(4,1,5),min.offset(3,1,1),min.offset(1,1,3),min.offset(2,1,3),min.offset(4,1,3),min.offset(3,1,4));
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
            cpu.installDocument(inputs(document(roles.get(i)),materialPos,readerPos),ComputerRuntime.MemoryPolicy.RESET);
            cpu.configureEndpoint(new EndpointConfiguration(names.get(i),true));
            members.put(names.get(i),new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),places.get(i)));
        }
        var authority=host(machines.getFirst());
        var admin = new MenuActor(level,"stock-admin"); admin.go(gatePos.east());
        var wires=Map.of("ws",wire("wire","x0","sample","x0"),"wd",wire("wire","x0","packet","x1"),
            "sv",wire("sample","x1","prepare","x0"),"vh",wire("prepare","x0","handoff","x0"),
            "vo",wire("prepare","x1","order","x0"),"oi",wire("order","x1","item","x0"),
            "hl",wire("handoff","x1","ledger","x0"),"hc",wire("handoff","x1","supply","x0"),
            "cd",wire("supply","x1","packet","x0"));
        var signals=Map.of("vh_signal",signal("prepare","p0","handoff","p1"),
            "hl_signal",signal("handoff","p0","ledger","p0"),"hc_signal",signal("handoff","p0","supply","p0"));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),wires,signals),admin),"configured","Nine independent Computers, explicit wires/signals");
        authority.configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(
            new ClusterMemberEditorAccess(controller,machines.getFirst()),admin,"s0","stock")));
        authority.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));

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
            for(int index:List.of(0,2,3,4,5,6,7,8))host(machines.get(index)).setPaused(false);
            for(int delay:List.of(800,2000,4000,8000,14000))h.runAfterDelay(delay,()->
                System.out.println("NATIVE_SUPPLY @"+delay+" "+machines.stream().map(cpu->{var v=host(cpu).flowSnapshot().orElseThrow().state();return v.status()+"/"+v.assembly().map(r->r.pc())+"/"+v.diagnostic();}).toList()));
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
                host(machines.get(1)).setPaused(false);
            }).thenWaitUntil(()->{
                var done=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(done.status(),FlowRuntime.Status.COMPLETE,"Supply ISA completes: "+machines.stream().map(cpu->{var v=host(cpu).flowSnapshot().orElseThrow().state();return v.status()+"/"+v.assembly().map(r->r.pc())+"/"+v.diagnostic();}).toList());
            }).thenExecute(()->{
                var done=host(machines.get(7)).flowSnapshot().orElseThrow().state();
                var reply=(DataValue.RecordValue)done.locals().field("reply");
                System.out.println("NATIVE_SUPPLY receipt "+reply);
                h.assertValueEqual(reply.field("code"),text("ok"),"Public program queries the admitted original job");
                h.assertValueEqual(reply.field("order"),done.locals().field("job"),"Business identity survives submission and fresh query correlation");
                h.assertValueEqual(reply.field("address"),text("alternate-supply"),"Frozen route matches original input address");
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
                System.out.println("NATIVE_SUPPLY alternate inventory -> physical native package -> public ISA receipt; original input retained, no delivery claim");
            }).thenSucceed();
        });
    }
    private static SimpleIoWire signal(String a,String ap,String b,String bp){return new SimpleIoWire(new SimpleIoWire.Pin(a,a,ap),new SimpleIoWire.Pin(b,b,bp),true);}

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
            BiocomputingNativeOrderSupplyGameTests.class.getClassLoader().getResourceAsStream("assets/"+location.getNamespace()+"/"+location.getPath());
        try(var stream=open.apply(ResourceLocation.parse("create_biotech:biocomputing/examples/"+id+".bcp"))){
            if(stream==null)throw new IllegalArgumentException("Missing public program "+id);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()),CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private static ProgramDocument inputs(ProgramDocument source,BlockPos output,BlockPos reader) {
        var draft=new ProgramWorkspace(source); var flow=draft.flow().orElseThrow();
        var fields=new LinkedHashMap<>(flow.initial().fields());
        if(fields.containsKey("output")) fields.put("output",BlockSnapshotReader.position(output));
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
