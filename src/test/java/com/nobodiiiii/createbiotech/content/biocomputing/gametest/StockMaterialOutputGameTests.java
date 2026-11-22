package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreateProductionReceipts;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Phase;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockSnapshotReader;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class StockMaterialOutputGameTests {
    private static final String ROUTE = "Materials Test", JOB = "public-material-order";
    private static final String COMPONENT = "Exact Components";
    private static final ModuleDefinition.Key ENTRY = new ModuleDefinition.Key("material_request", 1);

    @GameTest(template="empty", timeoutTicks=15000)
    public static void publicProgramRequestsStockWithoutPanelAndReadsDurableNativeReceipt(GameTestHelper h) {
        var level = h.getLevel();
        var min = h.absolutePos(new BlockPos(1,1,1)); var max = min.offset(6,3,4);
        var gate = min.offset(6,1,2); var outputPos = gate.east(4); var linkPos = outputPos.east();
        var packagerPos = linkPos.below();
        var named = new DataValue.NbtValue(10, record("minecraft:custom_name",
            new DataValue.NbtValue(8,text("{\"text\":\""+COMPONENT+"\"}"))));
        var manifest = new DataValue.ListValue(List.of(record("item",text("minecraft:paper"),"count",number(4),"components",named)));
        var initial = record("target",BlockSnapshotReader.position(outputPos),"job",text(JOB),"items",manifest);
        var programs = List.of(inputs(document("stock_material_a"),initial),document("production_b"),document("production_c"));
        // Loaded block-entity order may put the external device before its gateway.
        var outputState=CBBlocks.STOCK_LINK_OUTPUT.get().defaultBlockState().setValue(StockLinkOutputBlock.FACING,Direction.EAST);
        level.setBlock(outputPos,outputState,3);
        var cpus = new ArrayList<ComputerBehaviour>();
        var members = new LinkedHashMap<String,ClusterConfiguration.Member>();
        for (int i=0; i<programs.size(); i++) {
            String name = String.valueOf((char)('a'+i));
            var pos = min.offset(1+i*2,1,1);
            var cpu = host(computer(h,pos,VillagerProfession.CLERIC,5));
            cpu.installDocument(programs.get(i),ComputerRuntime.MemoryPolicy.RESET);
            cpu.configureEndpoint(new EndpointConfiguration(name,true));
            cpus.add(cpu); members.put(name,new ClusterConfiguration.Member(cpu.communicationIdentity().orElseThrow(),pos));
        }
        for (var p : BlockPos.betweenClosed(min,max))
            if (p.getX()==min.getX() || p.getX()==max.getX() || p.getY()==min.getY() || p.getY()==max.getY() || p.getZ()==min.getZ() || p.getZ()==max.getZ())
                level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
        level.setBlock(gate,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);
        var controller=(ClusterControllerBlockEntity)level.getBlockEntity(gate);
        var actor = new MenuActor(level); actor.go(gate.east()); persistAccount(level, actor.getGameProfile());
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of(
            "ab",wire("a","x0","b","x0"),"bc",wire("b","x1","c","x0"))),actor),"configured","Real closed cluster");
        var a=cpus.getFirst(); var b=cpus.get(1);
        b.configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,b.blockEntity),actor,"s0","materials")));
        b.configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
        for (int i=1; i<4; i++) level.setBlock(gate.east(i),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
        var linkState=AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR);
        level.setBlock(packagerPos.west(),Blocks.CHEST.defaultBlockState(),3);
        level.setBlock(packagerPos,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
        level.setBlock(linkPos,linkState,3);
        var link=(PackagerLinkBlockEntity)level.getBlockEntity(linkPos); link.placedBy=actor.getUUID();
        var output=(StockLinkOutputBlockEntity)level.getBlockEntity(outputPos);
        var packager=(PackagerBlockEntity)level.getBlockEntity(packagerPos);
        var chest=(Container)level.getBlockEntity(packagerPos.west());
        var paper=new ItemStack(Items.PAPER,16); paper.set(DataComponents.CUSTOM_NAME,Component.literal(COMPONENT));
        chest.setItem(0,paper); chest.setItem(1,new ItemStack(Items.PAPER,16));

        h.runAfterDelay(30,()->{
            var virginArchive = output.saveWithFullMetadata(level.registryAccess());
            actor.go(outputPos);
            h.assertTrue(StockApplicationMenu.openPhysical(actor,output),"Registered external output opens real menu");
            var menu=actor.menu();
            action(menu,StockApplicationMenuRequest.Action.BIND,0,"").handle(actor);
            h.assertValueEqual(menu.code(),"bound","Physical source grant is catalogue-only by default");
            h.assertValueEqual(menu.view().field("material_status"),text("closed"),"Explicit material grant is absent");
            var catalogueOnlyArchive = output.saveWithFullMetadata(level.registryAccess());
            cpus.reversed().forEach(cpu -> cpu.setPaused(false));
            for (int delay : List.of(400,1200,2200,4000,6500,9000,12000))
                h.runAfterDelay(delay, () -> {
                    var be = (StockLinkOutputBlockEntity)level.getBlockEntity(outputPos);
                    var service = be.getBehaviour(StockApplicationBehaviour.TYPE);
                    System.out.println("STOCK_MATERIAL_TEST wire @"+delay+" B="+b.serialDevice().status("s0")
                        +" output="+service.device().status("s0")+" CPUs="+cpus.stream().map(cpu -> {
                            var snap=cpu.flowSnapshot().orElseThrow();
                            return snap.state().status()+"/"+snap.state().cursor()+"/"+snap.state().assembly().map(x -> x.pc())
                                +"/"+snap.tasks().contexts().stream().map(t -> t.status()+"/"+t.cursor()+"/"+t.assembly().map(x -> x.pc())+"/"+t.diagnostic()).toList();
                        }).toList());
                });
            h.runAfterDelay(20,()->run(h,a,initial,"submit",denied->{
                h.assertValueEqual(denied.field("code"),text("denied"),"Publication permission does not confer extraction");
                h.assertValueEqual(chest.getItem(0).getCount(),16,"No extraction while closed");
                action(menu,StockApplicationMenuRequest.Action.REFRESH,-1,"").handle(actor);
                action(menu,StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1,"*").handle(actor);
                h.assertValueEqual(menu.code(),"invalid_material_address","Implicit wildcard is not a route");
                var grant=action(menu,StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1," "+ROUTE+" ");
                grant.handle(actor);
                h.assertValueEqual(menu.code(),"materials_granted","Only explicit grant enables dispatch");
                h.assertValueEqual(menu.view().field("material_address"),text(ROUTE),"Destination is normalized on the server");
                long grantedRevision = materialRevision(output);
                grant.handle(actor);
                h.assertValueEqual(materialRevision(output),grantedRevision,"Old UI nonce cannot rotate the durable grant");
                run(h,a,initial,"submit",accepted->{
                    h.assertValueEqual(accepted.field("code"),text("accepted"),"Only ordinary ISA assembles and sends the accepted request");
                    h.assertValueEqual(accepted.field("phase"),text("queued"),"Admission is not shipping");
                    h.startSequence().thenWaitUntil(()->h.assertTrue(PackageItem.isPackage(packager.heldBox),"Real packager publishes a box"))
                        .thenExecute(()->{
                            h.assertValueEqual(chest.getItem(0).getCount(),12,"Native Create extracts exactly the component-matched stack");
                            h.assertValueEqual(chest.getItem(1).getCount(),16,"Plain paper is not substituted");
                            var box=packager.heldBox.copy();
                            h.assertValueEqual(PackageItem.getAddress(box),ROUTE,"Actual package uses the configured destination");
                            h.assertTrue(PackageItem.hasOrderData(box),"Real wireless request supplies native order metadata");
                            h.assertTrue(ItemStack.isSameItemSameComponents(PackageItem.getContents(box).getStackInSlot(0),paper),"Package retains native components");
                            run(h,a,initial,"submit",duplicate->{
                                h.assertValueEqual(duplicate.field("code"),text("existing"),"Explicit repeated submit uses same stable job");
                                h.assertValueEqual(duplicate.field("phase"),text("waiting"),"Dispatch is not delivery");
                                h.assertValueEqual(chest.getItem(0).getCount(),12,"No second extraction");
                                var restored=(StockLinkOutputBlockEntity)reconstruct(level,output);
                                h.assertTrue(!restored.getUpdateTag(level.registryAccess()).contains("BiocomputingStockMaterials"),"No authority in client NBT");
                                h.runAfterDelay(20,()->run(h,a,initial,"order",status->{
                                    h.assertValueEqual(status.field("milestone"),text("dispatch_accepted"),"Restored output returns original native dispatch state");
                                    deliver(h,packagerPos.south(3),link.behaviour.freqId,box);
                                    run(h,a,initial,"order",delivered->{
                                        h.assertValueEqual(delivered.field("milestone"),text("materials_delivered"),"Native unpack, not manufacturing, records delivery");
                                        h.assertValueEqual(chest.getItem(0).getCount(),12,"Status queries do not extract");
                                        var missing=record("target",initial.field("target"),"job",text("missing-stock"),
                                            "items",new DataValue.ListValue(List.of(record("item",text("minecraft:diamond"),"count",number(1)))));
                                        run(h,a,missing,"submit",ignored->run(h,a,missing,"order",failed->{
                                            h.assertValueEqual(failed.field("phase"),text("failed"),"Unstocked material is not fabricated");
                                            h.assertValueEqual(failed.field("diagnostic"),text("no_stock"),"Native absence is explicit");
                                            h.assertValueEqual(chest.getItem(0).getCount(),12,"Unavailable stock does not alter inventory");
                                            mixedSaves(h, actor, b, restored, virginArchive, catalogueOnlyArchive, initial, recovered -> {
                                            b.setPaused(true); b.reset();
                                            b.installDocument(programs.get(1),ComputerRuntime.MemoryPolicy.RESET); b.setPaused(false);
                                            h.runAfterDelay(20,()->run(h,a,initial,"order",stale->{
                                                h.assertValueEqual(stale.field("code"),text("denied"),"New installation cannot borrow an old grant");
                                                h.assertTrue(StockApplicationMenu.openPhysical(actor,recovered),"Manage restored output");
                                                var current=actor.menu();
                                                action(current,StockApplicationMenuRequest.Action.BIND,0,"").handle(actor);
                                                action(current,StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1,ROUTE).handle(actor);
                                                h.assertValueEqual(current.code(),"materials_granted","New install requires explicit authority");
                                                level.removeBlock(linkPos,false); level.setBlock(linkPos,linkState,3);
                                                var replacement=(PackagerLinkBlockEntity)level.getBlockEntity(linkPos); replacement.placedBy=actor.getUUID();
                                                h.runAfterDelay(20,()->run(h,a,initial,"order",staleTarget->{
                                                    h.assertValueEqual(staleTarget.field("code"),text("denied"),"Replacement Link cannot use old anchor");
                                                    h.assertValueEqual(chest.getItem(0).getCount(),12,"Stale identity never causes extraction");
                                                    var foreign = recovered.saveWithFullMetadata(level.registryAccess());
                                                    foreign.getCompound("BiocomputingStockMaterials").putUUID("Device",UUID.randomUUID());
                                                    var copied = restoreArchive(level,recovered,foreign);
                                                    h.assertTrue(StockApplicationMenu.openPhysical(actor,copied),"Manage catalogue despite foreign material archive");
                                                    var management = actor.menu();
                                                    action(management,StockApplicationMenuRequest.Action.REVOKE,-1,"").handle(actor);
                                                    h.assertValueEqual(management.code(),"revoked","Material reconciliation cannot prevent catalogue withdrawal");
                                                    action(management,StockApplicationMenuRequest.Action.BIND,0,"").handle(actor);
                                                    h.assertValueEqual(management.code(),"bound","Catalogue can rebind independently of invalid material ownership");
                                                    h.assertValueEqual(management.view().field("material_status"),text("invalid_configuration"),"Foreign material authority stays closed");
                                                    h.assertTrue(copied.saveWithFullMetadata(level.registryAccess()).getCompound("BiocomputingStockMaterials").getBoolean("Suspended"),"Local material suspension survives block save");

                                                    h.succeed();
                                                }));
                                            }));
                                            });
                                        }));
                                    });
                                }));
                            });
                        });
                });
            }));
        });
    }
    private static long materialRevision(StockLinkOutputBlockEntity output) {
        return output.saveWithFullMetadata(output.getLevel().registryAccess()).getCompound("BiocomputingStockMaterials")
            .getCompound("Grant").getLong("Revision");
    }
    private static void mixedSaves(GameTestHelper h, MenuActor actor, ComputerBehaviour b, StockLinkOutputBlockEntity before,
                                   CompoundTag virginArchive, CompoundTag catalogueOnly, DataValue.RecordValue initial,
                                   Consumer<StockLinkOutputBlockEntity> finish) {
        var level = h.getLevel();
        var target = initial.field("target");
        var job = initial.field("job");
        var data = record("items", initial.field("items"));
        var activeArchive = before.saveWithFullMetadata(level.registryAccess());
        var interval = CBConfigs.SERVER.biocomputing.productionActivityTicks;
        int priorInterval = interval.get();
        wireExchange(h,b,target,"status",record(),statusReply -> {
            var captured = (DataValue.RecordValue)statusReply.field("state");
            var id = UUID.fromString(((DataValue.TextValue)captured.field("device")).value());
            var repository = CreateProductionReceipts.get(level.getServer()).repository();
            var queuedArgs = record("device",captured.field("device"),"session",captured.field("session"),
                "order",text("held-before-revoke"),"data",data);
            interval.set(1200); // Fixture only: deterministically keep the newly accepted intent queued.
            wireExchange(h,b,target,"submit",queuedArgs,queued -> {
                h.assertValueEqual(queued.field("code"),text("accepted"),"Queued intent is accepted before revocation");
                h.assertTrue(repository.snapshots().get(id).orders().values().stream().anyMatch(order -> order.phase() == Phase.QUEUED),
                    "Intent still queued in durable journal");
                h.assertTrue(StockApplicationMenu.openPhysical(actor,before),"Manage active grant before mixing saves");
                var menu = actor.menu();
                action(menu,StockApplicationMenuRequest.Action.REVOKE_MATERIALS,-1,"").handle(actor);
                h.assertValueEqual(menu.code(),"materials_revoked","Acknowledge only after durable revoke");
                h.assertValueEqual(repository.snapshots().get(id).materialGrant(),OptionalLong.of(0),"Durable authorization is closed");
                var staleGrant = restoreArchive(level,before,activeArchive);
                interval.set(priorInterval);
                h.runAfterDelay(15, () -> wireExchange(h,b,target,"order",record("order",job),denied -> {
                    h.assertValueEqual(denied.field("code"),text("denied"),"Restored saved grant cannot reanimate its durable revoked intent");
                    h.assertTrue(repository.snapshots().get(id).orders().values().stream().anyMatch(order -> order.phase() == Phase.QUEUED),
                        "Old active grant was not allowed to advance the durable queue");
                    var preGrant = restoreArchive(level,staleGrant,catalogueOnly);
                    h.runAfterDelay(15, () -> {
                        h.assertTrue(StockApplicationMenu.openPhysical(actor,preGrant),"Catalogue-only archive reopens on known slot");
                        var reopened = actor.menu();
                        action(reopened,StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1,ROUTE).handle(actor);
                        h.assertValueEqual(reopened.code(),"materials_granted","Pre-grant NBT reattaches newer existing journal");
                        var staleSubmit = record("device",captured.field("device"),"session",captured.field("session"),"order",job,"data",data);
                        wireExchange(h,b,target,"submit",staleSubmit,stale -> {
                            h.assertValueEqual(stale.field("code"),text("session_changed"),"Actual captured wire session cannot submit after mixed-save recovery");
                            var noIdentity = restoreArchive(level,preGrant,virginArchive);
                            h.runAfterDelay(15, () -> {
                                h.assertTrue(StockApplicationMenu.openPhysical(actor,noIdentity),"Restore first-use NBT with no saved identity");
                                var current = actor.menu();
                                action(current,StockApplicationMenuRequest.Action.BIND,0,"").handle(actor);
                                action(current,StockApplicationMenuRequest.Action.GRANT_MATERIALS,-1,ROUTE).handle(actor);
                                h.assertValueEqual(current.code(),"materials_granted","First-use rollback recovers stable material slot");
                                h.runAfterDelay(15, () -> wireExchange(h,b,target,"status",record(),freshReply -> {
                                    var fresh = (DataValue.RecordValue)freshReply.field("state");
                                    h.assertValueEqual(fresh.field("device"),captured.field("device"),"No second material namespace");
                                    var duplicate = record("device",fresh.field("device"),"session",fresh.field("session"),"order",job,"data",data);
                                    wireExchange(h,b,target,"submit",duplicate,existing -> {
                                        h.assertValueEqual(existing.field("code"),text("existing"),"First-use identity rollback cannot duplicate the same job");
                                        h.assertValueEqual(existing.field("phase"),text("complete"),"Original native receipt survives mixed saves");
                                        finish.accept(noIdentity);
                                    });
                                }));
                            });
                        });
                    });
                }));
            });
        });
    }
    private static StockLinkOutputBlockEntity restoreArchive(ServerLevel level, StockLinkOutputBlockEntity previous, CompoundTag archive) {
        var pos = previous.getBlockPos(); var state = previous.getBlockState();
        level.removeBlockEntity(pos);
        var output = (StockLinkOutputBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,archive.copy(),level.registryAccess());
        level.setBlockEntity(Objects.requireNonNull(output));
        return output;
    }
    /** Negative/mixed-save probes use the same finite physical wire, not another device or a handler shortcut. */
    private static void wireExchange(GameTestHelper h, ComputerBehaviour b, DataValue target, String operation,
                                     DataValue.RecordValue arguments, Consumer<DataValue.RecordValue> check) {
        var correlation = text(UUID.randomUUID().toString());
        var frame = record("kind",text("production_command"),"version",number(1),"target",target,
            "request",correlation,"operation",text(operation),"arguments",arguments);
        var sent = b.serialDevice().prepareSend("s0",frame);
        h.assertValueEqual(sent.result().field("status"),text("queued"),"Probe occupies the same physical serial sender");
        h.assertTrue(sent.publish(),"Probe frame queued");
        awaitWire(h,b,correlation,600,check);
    }
    private static void awaitWire(GameTestHelper h, ComputerBehaviour b, DataValue correlation, int remaining,
                                  Consumer<DataValue.RecordValue> check) {
        h.runAfterDelay(5, () -> {
            var receive = b.serialDevice().prepareReceive("s0");
            if (!text("received").equals(receive.result().field("status"))) {
                h.assertTrue(remaining > 0,"Bounded mixed-save wire probe timed out: "+b.serialDevice().status("s0"));
                awaitWire(h,b,correlation,remaining-5,check);
                return;
            }
            var reply = (DataValue.RecordValue)receive.result().field("value");
            h.assertTrue(receive.publish(),"Actual probe receive published");
            h.assertValueEqual(reply.field("request"),correlation,"Probe observes only its correlated real reply");
            System.out.println("STOCK_MATERIAL_MIXED_SAVE "+reply.fields());
            check.accept(reply);
        });
    }

    private static void deliver(GameTestHelper h, BlockPos pos, UUID network, ItemStack box) {
        var level=h.getLevel();
        level.setBlock(pos.west(),Blocks.CHEST.defaultBlockState(),3);
        level.setBlock(pos,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);
        level.setBlock(pos.above(),AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),3);
        var link=(PackagerLinkBlockEntity)level.getBlockEntity(pos.above());
        LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId=network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
        var receiver=(PackagerBlockEntity)level.getBlockEntity(pos);
        h.assertTrue(receiver.unwrapBox(box,true),"Simulated unpack is possible");
        var chest=(Container)level.getBlockEntity(pos.west());
        h.assertTrue(chest.isEmpty(),"Simulation does not deliver");
        h.assertTrue(new PackagerItemHandler(receiver).insertItem(0,box,false).isEmpty(),"Real Create unpack accepts native box");
        h.assertValueEqual(chest.getItem(0).getCount(),4,"Destination receives four actual items");
    }
    private static void run(GameTestHelper h,ComputerBehaviour a,DataValue.RecordValue initial,String mode,Consumer<DataValue.RecordValue> check) {
        var args=new LinkedHashMap<>(initial.fields()); args.put("action",text(mode));
        UUID task=a.startFlowTask(ENTRY,new DataValue.RecordValue(args));
        awaitTask(h, a, task, mode + "/" + initial.field("job"), 4000, check);
    }
    private static void awaitTask(GameTestHelper h, ComputerBehaviour a, UUID task, String label, int remaining,
                                  Consumer<DataValue.RecordValue> check) {
        h.runAfterDelay(5, () -> {
            var view=a.flowTask(task).orElseThrow();
            h.assertTrue(view.status()!=FlowRuntime.Status.FAILED, "ISA " + label + " failed: " + view.diagnostic());
            if (view.status()!=FlowRuntime.Status.COMPLETE) {
                h.assertTrue(remaining > 0, "ISA " + label + " timed out: " + view.status());
                awaitTask(h,a,task,label,remaining-5,check);
                return;
            }
            var reply=a.claimFlowTask(task).locals().field("reply");
            h.assertTrue(reply instanceof DataValue.RecordValue,"Actual reply, got: "+reply);
            System.out.println("STOCK_MATERIAL_TEST " + label + " " + ((DataValue.RecordValue)reply).fields());
            check.accept((DataValue.RecordValue)reply);
        });
    }
    private static StockApplicationMenuRequest action(StockApplicationMenu menu, StockApplicationMenuRequest.Action action, int choice, String address) {
        return menu.request(action,choice,"",0,address);
    }
    private static XBusWire wire(String a,String ap,String b,String bp) {
        return new XBusWire(new XBusWire.Pin(a,a,ap),new XBusWire.Pin(b,b,bp),true);
    }
    private static DataValue.RecordValue record(Object... pairs) {
        var fields=new LinkedHashMap<String,DataValue>();
        for (int i=0;i<pairs.length;i+=2) fields.put((String)pairs[i],(DataValue)pairs[i+1]);
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.TextValue text(String s) {return new DataValue.TextValue(s);}
    private static DataValue.IntegerValue number(long n) {return new DataValue.IntegerValue(n);}
    private static ProgramDocument document(String id) {
        Function<ResourceLocation,java.io.InputStream> open=loc -> StockMaterialOutputGameTests.class.getClassLoader().getResourceAsStream("assets/"+loc.getNamespace()+"/"+loc.getPath());
        var selected=ProgramExampleCatalog.load(open).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(selected,open,CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
    }
    private static ProgramDocument inputs(ProgramDocument source,DataValue.RecordValue initial) {
        var draft=new ProgramWorkspace(source); var flow=draft.flow().orElseThrow();
        draft.setFlow(Optional.of(new FlowDocumentBody(flow.inputs(),flow.steps(),initial,flow.canvas(),flow.inline(),flow.execution(),flow.compilation())));
        return draft.snapshot();
    }
    private static void persistAccount(ServerLevel level, GameProfile profile) {
        var cookie=CommonListenerCookie.createInitial(profile,false); var connection=new Connection(PacketFlow.SERVERBOUND);
        var channel=new EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.onMinecraftRegister(connection,Set.of(ResourceLocation.parse("create:sync_edge_group")));
        var player=new ServerPlayer(level.getServer(),level,profile,cookie.clientInformation());
        var players=level.getServer().getPlayerList();
        players.placeNewPlayer(connection,player,cookie);
        player.setGameMode(GameType.SURVIVAL);
        players.remove(player);
        channel.finishAndReleaseAll();
    }
    private static final class MenuActor extends FakePlayer {
        private int next;
        MenuActor(ServerLevel level) {super(level,new GameProfile(UUID.randomUUID(),"materials-test"));gameMode.changeGameModeForPlayer(GameType.SURVIVAL);}
        void go(BlockPos pos) {moveTo(Vec3.atCenterOf(pos));}
        StockApplicationMenu menu() {return (StockApplicationMenu)containerMenu;}
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            int id=++next; containerMenu=provider.createMenu(id,getInventory(),this); return OptionalInt.of(id);
        }
    }
}
