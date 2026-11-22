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
public final class BiocomputingNativeOrderReadGameTests {
    private record ReaderDeny(ServerLevel level, BlockPos target, UUID actor) {}
    private static final Set<ReaderDeny> DENIED = new HashSet<>();
    static {
        ReadComponents.registerPolicy(context -> DENIED.contains(new ReaderDeny(context.level(),context.target(),context.actor().getUUID()))
            ? Optional.of(new DataValue.ErrorValue("access_denied","Registered test target policy")) : Optional.empty());
    }

    @GameTest(template="empty", timeoutTicks=10000)
    public static void admittedNativeOrderIsReadAndConvertedWithoutAcknowledgement(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1,1,1)); var max = min.offset(6,4,6);
        var gatePos = min.offset(6,1,2);
        var linkPos = gatePos.east(5); var outputPos = linkPos.north(); var readerPos = linkPos.south();
        var tickerPos = linkPos.east(2);
        var names = List.of("wire","sample","inspect","order","item");
        var roles = List.of("serial_exchange","stock_native_sample","stock_native_inspect","stock_native_order","stock_native_item");
        var places = List.of(gatePos.west(), min.offset(1,1,1),min.offset(1,1,4),min.offset(4,1,5),min.offset(3,1,1));
        var machines = new ArrayList<ComputerBlockEntity>();
        for (var pos : places) machines.add(computer(h,pos,VillagerProfession.CLERIC,5));
        for (var p : BlockPos.betweenClosed(min,max))
            if (p.getX()==min.getX() || p.getX()==max.getX() || p.getY()==min.getY() || p.getY()==max.getY() || p.getZ()==min.getZ() || p.getZ()==max.getZ())
                level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
        level.setBlock(gatePos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gatePos);
        var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
        for (int i=0;i<5;i++) {
            var cpu=host(machines.get(i));
            cpu.installDocument(inputs(document(roles.get(i)),outputPos,readerPos),ComputerRuntime.MemoryPolicy.RESET);
            cpu.configureEndpoint(new EndpointConfiguration(names.get(i),true));
            members.put(names.get(i),new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),places.get(i)));
        }
        var authority=host(machines.getFirst());
        var admin = new MenuActor(level,"stock-admin"); admin.go(gatePos.east());
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of(
            "ws",wire("wire","x0","sample","x0"),"sv",wire("sample","x1","inspect","x0"),"vo",wire("inspect","x1","order","x0"),"oi",wire("order","x1","item","x0"))),admin),"configured","Real closed Cluster");
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


        // Persist an authoritative Survival account: a menu proxy must never invent reader permissions.
        try (var account=new TestAccount(level,admin.getGameProfile())) { account.player.setGameMode(GameType.SURVIVAL); }
        h.runAfterDelay(8,()->{
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
            admin.openNative(ticker);
            admin.application().request(StockApplicationMenuRequest.Action.BIND,0,"",0,"").handle(admin);
            var frontend=admin.application();
            for(int index:List.of(0,3,4))host(machines.get(index)).setPaused(false);
            var denied=new ReaderDeny(level,linkPos,admin.getUUID());
            h.startSequence().thenWaitUntil(()->{
                frontend.request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(frontend.view().field("status"),text("ready"),"Real reader/output/Cluster authority becomes ready");
            }).thenExecute(()->{
                admin.go(tickerPos); admin.openMenu(ticker.new RequestMenuProvider(),b->{});
                var paper=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER,2);
                h.assertTrue(paper.has(net.minecraft.core.component.DataComponents.RARITY),"Fixture removes an existing default component");
                paper.remove(net.minecraft.core.component.DataComponents.RARITY);
                var order=com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(List.of(
                    new com.simibubi.create.content.logistics.BigItemStack(paper,7),
                    new com.simibubi.create.content.logistics.BigItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE),3)));
                StockNativeOrderRequest.create(admin.containerMenu.containerId,order,"alternate-supply",level.registryAccess()).handle(admin);
                h.assertTrue(admin.containerMenu instanceof StockApplicationMenu,"Native order opens explicit preview");
                admin.application().request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"").handle(admin);
                h.assertValueEqual(admin.application().code(),"input_too_large","Two component-bearing rows exceed this default frame; never enlarge it silently");
                h.assertValueEqual(integer(admin.application(),"pending"),0L,"Oversized input never enters the queue");
                admin.openMenu(ticker.new RequestMenuProvider(),b->{});
                var bounded=com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(List.of(
                    new com.simibubi.create.content.logistics.BigItemStack(paper,7)));
                StockNativeOrderRequest.create(admin.containerMenu.containerId,bounded,"alternate-supply",level.registryAccess()).handle(admin);
                admin.application().request(StockApplicationMenuRequest.Action.CONFIRM_NATIVE,-1,"",0,"").handle(admin);
                h.assertValueEqual(admin.application().code(),"input_queued","Real native order admitted once");
                host(machines.get(2)).setPaused(false); host(machines.get(1)).setPaused(false);
            }).thenWaitUntil(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(done.status(),FlowRuntime.Status.COMPLETE,"Installed pipeline reads and converts: "+machines.stream()
                    .map(cpu->host(cpu).flowSnapshot().orElseThrow().state()).map(v->v.status()+"/"+v.cursor()+"/"+v.diagnostic()).toList());
            }).thenExecute(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                var input=(DataValue.RecordValue)done.locals().field("input"); var headValue=(DataValue.RecordValue)input.field("head");
                h.assertValueEqual(headValue.field("kind"),text("native_order"),"Not a fabricated catalog item");
                h.assertValueEqual(headValue.field("address"),text("alternate-supply"),"Original address remains available to application");
                h.assertTrue(headValue.field("order") instanceof DataValue.NbtValue,"Original native order and craft context retained");
                h.assertTrue(input.fields().containsKey("state"),"ACK fence is retained, not consumed");
                var converted=(DataValue.RecordValue)done.locals().field("result");
                h.assertValueEqual(converted.field("c"),text("ok"),"Full conversion succeeded");
                var items=(DataValue.ListValue)converted.field("i"); h.assertValueEqual(items.values().size(),1,"Entire accepted bounded request, not a truncated oversized request");
                var first=(DataValue.RecordValue)items.values().getFirst();
                h.assertValueEqual(first.field("count"),number(7),"Requested count, not stack count 2");
                var components=(DataValue.RecordValue)((DataValue.NbtValue)first.field("components")).value();
                h.assertTrue(components.fields().containsKey("!minecraft:rarity"),"Removed component crosses real Reader/serial/XBus");
                admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(integer(admin.application(),"pending"),1L,"Sampling/conversion never ACKs the native input");
            }).thenIdle(5).thenExecute(()->{
                DENIED.add(denied);
                // Explicitly restart the two one-shot stages. Reset retires completed receipts; do not bypass BUSY or replace the authority.
                for(int index:List.of(1,2)) {
                    host(machines.get(index)).setPaused(true);
                    host(machines.get(index)).reset();
                    host(machines.get(index)).setPaused(false);
                }
            }).thenWaitUntil(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(done.status(),FlowRuntime.Status.COMPLETE,"Denied read is an inspectable result, not an empty order");
            }).thenExecute(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(((DataValue.RecordValue)done.locals().field("result")).field("c"),text("read_failed"),"Target permission failure is not empty inventory");
                h.assertValueEqual(((DataValue.RecordValue)done.locals().field("sample")).field("code"),text("access_denied"),"Original reader refusal retained");
                admin.application().request(StockApplicationMenuRequest.Action.REFRESH,-1,"",0,"").handle(admin);
                h.assertValueEqual(integer(admin.application(),"pending"),1L,"Permission failure never ACKs or dispatches");
            }).thenIdle(5).thenExecute(()->{
                DENIED.remove(denied);
                level.setBlock(outputPos,level.getBlockState(outputPos).setValue(StockLinkOutputBlock.FACING,Direction.NORTH),3);
                for(int index:List.of(1,2)) {
                    host(machines.get(index)).setPaused(true);
                    host(machines.get(index)).reset();
                    host(machines.get(index)).setPaused(false);
                }
            }).thenWaitUntil(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(done.status(),FlowRuntime.Status.COMPLETE,"Component failure is inspectable");
            }).thenExecute(()->{
                var done=host(machines.get(2)).flowSnapshot().orElseThrow().state();
                h.assertValueEqual(((DataValue.RecordValue)done.locals().field("result")).field("c"),text("component_error"),"Missing output is not an empty queue");
                h.assertTrue(done.locals().field("input") instanceof DataValue.ErrorValue,"Original component error retained");
                var saved=output.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockApplication");
                h.assertValueEqual(StockApplicationCodec.load(saved,UUID.randomUUID()).inputs().size(),1,"Unavailable output retains the original pending order");
            }).thenExecute(()->{
                System.out.println("NATIVE_ORDER_READ actual admission -> Reader -> serial -> Cluster ISA conversion; queue retained; permission error retained");
            }).thenSucceed();
        });
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
            BiocomputingNativeOrderReadGameTests.class.getClassLoader().getResourceAsStream("assets/"+location.getNamespace()+"/"+location.getPath());
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
