package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.junit.jupiter.api.*;

/** Exercises the production world adapter through the native capability API with a non-Computer gateway. */
class PeerDiscoveryWorldTest {
    @Test void selectedSourceFacesDoNotScanOrAuthorizeTheOtherNetwork() throws Exception {
        var world = world(); world.bothOriginSides = true; world.denied = new BlockPos(-1, 0, 0);
        var actor = actor(world);
        var peers = (DataValue.RecordValue) PeerDiscovery.discover(world.owner, actor, Set.of(Direction.EAST)).field("peers");
        assertEquals(Set.of("gateway"), peers.fields().keySet());
        assertEquals("access_denied", assertThrows(PeerDiscovery.DiscoveryException.class, () -> PeerDiscovery.discover(world.owner, actor)).getMessage());
        world.denied = null;
        assertEquals(Set.of("west"), ((DataValue.RecordValue) PeerDiscovery.discover(world.owner, actor, Set.of(Direction.WEST)).field("peers")).fields().keySet());
    }
    @Test void realSidedCapabilitySelectsTheTargetDomainAndReceivesThePortName() throws Exception {
        var world = world(); world.splitSides = true;
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        var payload = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(9)));
        var request = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(source, 7, event, 0, payload,
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageProtocol.Request(
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestContext(
                    new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestCorrelation(source, event, 0), "return")));
        assertTrue(EndpointTransport.deliverEnvelope(world.owner, Set.of(Direction.EAST), "private", request).accepted());
        assertEquals(List.of("internal/private"), world.receivedPorts);
        assertEquals(List.of(request), world.received); // Forwarding did not reallocate correlation, sequence or payload.
        assertTrue(EndpointTransport.deliverEnvelope(world.owner, Set.of(Direction.EAST), "public-b", request).accepted());
        assertEquals(List.of("internal/private", "external/public-b"), world.receivedPorts);
        assertTrue(world.sides.containsAll(Set.of(Direction.WEST, Direction.UP)));
    }
    @Test void emptyEgressDoesNotFallBackToAllFacesAndFaceSelectionIsCopied() throws Exception {
        var world = world(); world.bothOriginSides = true;
        var message = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0,
            new DataValue.RecordValue(Map.of()));
        assertEquals("not_found", EndpointTransport.deliverEnvelope(world.owner, Set.of(), "gateway", message).code());
        assertTrue(world.received.isEmpty());
        var selected = EnumSet.of(Direction.EAST);
        var adapter = new LoadedEndpointWorld(world, BlockPos.ZERO, pos -> true, selected);
        selected.clear(); selected.add(Direction.WEST);
        assertTrue(EndpointTransport.deliver(adapter, BlockPos.ZERO, new CommunicationNetworkLimits(100, 8), "gateway", message).accepted());
        assertEquals("not_found", EndpointTransport.deliver(adapter, BlockPos.ZERO, new CommunicationNetworkLimits(100, 8), "west", message).code());
    }
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null) type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
            .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var f=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");f.setAccessible(true);f.setBoolean(null,true);
    }
    @Test void actualAdapterDiscoversGatewayCapabilityWithoutComputerOrRam() throws Exception {
        var world=world(); var actor=actor(world);
        var directory=PeerDiscovery.discover(world.owner,actor);
        assertEquals(Set.of("gateway"),((DataValue.RecordValue)directory.field("peers")).fields().keySet());
        assertEquals(1,world.advertisements); assertTrue(world.sides.contains(Direction.WEST));
        world.accepts=false;
        assertTrue(((DataValue.RecordValue)PeerDiscovery.discover(world.owner,actor).field("peers")).fields().isEmpty());
    }
    @Test void sidedOnlyGatewayDoesNotNeedAnUnsidedCapability() throws Exception {
        var world=world();world.sidedOnly=true;
        assertEquals(Set.of("gateway"),((DataValue.RecordValue)PeerDiscovery.discover(world.owner,actor(world)).field("peers")).fields().keySet());
    }
    @Test void actualTransportUsesOnlyConnectedFaceOfNonComputerGateway() throws Exception {
        var world = world(); world.sidedOnly = true;
        var payload = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(9)));
        var outgoing = new com.nobodiiiii.createbiotech.content.biocomputing.computer.OutboundMessage(UUID.randomUUID(), 0, "gateway", payload, 10);
        var source = UUID.randomUUID();
        var result = EndpointTransport.deliver(world.owner, source, outgoing);
        assertTrue(result.accepted()); assertEquals(1, world.received.size());
        assertEquals(source, world.received.getFirst().source()); assertEquals(payload, world.received.getFirst().payload());
        assertEquals(Set.of(Direction.WEST), world.sides);
        world.accepts = false;
        assertEquals("not_found", EndpointTransport.deliver(world.owner, source, outgoing).code());
        assertEquals(1, world.received.size());
    }
    @Test void actualBehaviourTickCommitsThenRoutesAndReceiverWaitsForItsOwnTick() throws Exception {
        var world = world(); world.sidedOnly = true;
        var constructor = ComputerBehaviour.class.getDeclaredConstructor(SmartBlockEntity.class, java.util.function.BooleanSupplier.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        var limits = new ComputerHostLimits(new ResourceLimits(100, 100_000, 1), new ArchiveLimits(100_000, 1_000_000, 10_000_000));
        var selected = new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", limits);
        var computer = (ComputerBehaviour) constructor.newInstance(world.owner, (java.util.function.BooleanSupplier) () -> true,
            (java.util.function.Supplier<HardwareSelection>) () -> selected);
        var payload = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(9)));
        var sendGraph = new ComputationGraph(Map.of(), Map.of("name", new ComputationGraph.Constant(new DataValue.TextValue("gateway")),
            "payload", new ComputationGraph.Constant(payload)), Map.of("endpoint", "name", "payload", "payload"));
        computer.install(new ProgramArchive(new ProgramDefinition(sendGraph, Map.of(), List.of(), List.of(new ProgramDefinition.Send("endpoint", "payload"))), Map.of()), ComputerRuntime.MemoryPolicy.RESET);
        computer.configureEndpoint(new EndpointConfiguration("sender", true));
        var receiveGraph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of("input", new ComputationGraph.Input("value")), Map.of("value", "input"));
        world.receiverHost = new ComputerBlockHost(() -> {}); world.limits = limits;
        world.receiverHost.install(new ProgramArchive(new ProgramDefinition(receiveGraph, Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(new ProgramDefinition.Write("value", "value"))), Map.of()), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        world.sender = computer; computer.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; world.received.isEmpty(); i++) { assertTrue(i < 100); world.clock++; computer.tick(); }
        assertTrue(computer.transportResult().orElseThrow().accepted());
        assertEquals(1, computer.snapshot().orElseThrow().delivery().orElseThrow().acknowledged());
        assertEquals(new DataValue.IntegerValue(0), world.receiverHost.snapshot().orElseThrow().machine().memory().field("value"));
        world.receiverHost.tick(limits, true);
        for (int i = 0; world.receiverHost.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); world.receiverHost.tick(limits, true);
        }
        assertEquals(new DataValue.IntegerValue(9), world.receiverHost.snapshot().orElseThrow().machine().memory().field("value"));
    }
    @Test void permissionAndLoadedGuardsPrecedeProviderCallsAndFailuresAreExplicit() throws Exception {
        var world=world();var actor=actor(world);
        world.denied=new BlockPos(2,0,0);
        assertEquals("access_denied",assertThrows(PeerDiscovery.DiscoveryException.class,()->PeerDiscovery.discover(world.owner,actor)).getMessage());
        assertEquals(0,world.advertisements);
        world.denied=BlockPos.ZERO;
        assertEquals("access_denied",assertThrows(PeerDiscovery.DiscoveryException.class,()->PeerDiscovery.authorize(world.owner,actor)).getMessage());
        world.denied=null;world.unloaded=new BlockPos(1,1,0);
        assertEquals("topology_unloaded",assertThrows(PeerDiscovery.DiscoveryException.class,()->PeerDiscovery.discover(world.owner,actor)).getMessage());
        assertEquals(0,world.advertisements);
        world.unloaded=null;world.failPermission=true;
        assertEquals("provider_failure",assertThrows(PeerDiscovery.DiscoveryException.class,()->PeerDiscovery.authorize(world.owner,actor)).getMessage());
    }
    private static TestWorld world() throws Exception {
        var w=allocate(TestWorld.class);w.clock=42;w.border=new WorldBorder();w.sides=new HashSet<>();w.accepts=true;w.received=new ArrayList<>();w.receivedPorts=new ArrayList<>();
        w.port=Owner.PORT;w.cable=new DataCableBlock(BlockBehaviour.Properties.of());
        w.owner=new Owner(BlockPos.ZERO,w.port.defaultBlockState());w.owner.setLevel(w);
        w.chunks=allocate(LoadedChunks.class); w.chunks.chunk=allocate(LoadedChunk.class); w.chunks.chunk.world=w;
        return w;
    }
    private static TestPlayer actor(TestWorld world) throws Exception {var p=allocate(TestPlayer.class);p.world=world;return p;}
    private static <T>T allocate(Class<T> type)throws Exception{var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(((sun.misc.Unsafe)f.get(null)).allocateInstance(type));}
    private static final class TestPlayer extends ServerPlayer {
        TestWorld world;private TestPlayer(){super(null,null,null,null);}
        @Override public ServerLevel serverLevel(){return world;}
        @Override public double distanceToSqr(double x,double y,double z){return 0;}
    }
    private static final class LoadedChunks extends ServerChunkCache {
        LoadedChunk chunk;
        private LoadedChunks(){super(null,null,null,null,null,null,0,0,false,null,null,null);}
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x,int z){return chunk;}
    }
    private static final class LoadedChunk extends net.minecraft.world.level.chunk.LevelChunk {
        TestWorld world;
        private LoadedChunk(){super(null,new net.minecraft.world.level.ChunkPos(0,0));}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return world.getBlockEntity(pos);}
    }
    private static final class TestWorld extends ServerLevel {
        LoadedChunks chunks;
        Owner owner;WorldBorder border;BlockPos denied,unloaded;boolean failPermission,accepts,sidedOnly,bothOriginSides,splitSides;int advertisements;
        List<String> receivedPorts;
        PortBlock port;DataCableBlock cable;Set<Direction>sides;
        List<com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope> received;
        ComputerBlockHost receiverHost; ComputerHostLimits limits; ComputerBehaviour sender;
        long clock;
        private TestWorld(){super(null,null,null,null,null,null,null,false,0,List.of(),false,null);}
        @Override public boolean hasChunkAt(BlockPos pos){return !pos.equals(unloaded);}
        @Override public ServerChunkCache getChunkSource(){return chunks;}
        @Override public int getMinBuildHeight(){return -64;}
        @Override public int getHeight(){return 384;}
        @Override public boolean mayInteract(Player actor,BlockPos pos){if(failPermission)throw new IllegalStateException("private");return !pos.equals(denied);}
        @Override public WorldBorder getWorldBorder(){return border;}
        @Override public BlockEntity getBlockEntity(BlockPos pos){assertNotEquals(denied,pos);return pos.equals(BlockPos.ZERO)?owner:null;}
        @Override public BlockState getBlockState(BlockPos pos){
            assertTrue(hasChunkAt(pos));assertNotEquals(denied,pos);
            if (pos.equals(BlockPos.ZERO) || pos.equals(new BlockPos(2,0,0)) || bothOriginSides && pos.equals(new BlockPos(-2,0,0))) return port.defaultBlockState();
            if (pos.equals(new BlockPos(1,0,0)) || bothOriginSides && pos.equals(new BlockPos(-1,0,0))
                || splitSides && (pos.equals(new BlockPos(1,1,0)) || pos.equals(new BlockPos(2,1,0)))) return cable.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        @Override public long getGameTime(){return clock;}
        @Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public <T,C>T getCapability(BlockCapability<T,C> cap,BlockPos pos,C side){
            assertTrue(hasChunkAt(pos));assertNotEquals(denied,pos);assertSame(CommunicationEndpoint.CAPABILITY,cap);
            boolean west = bothOriginSides && pos.equals(new BlockPos(-2,0,0));
            if(!pos.equals(new BlockPos(2,0,0)) && !west)return null;
            if((sidedOnly || splitSides) && side==null)return null;
            if(splitSides && !west && side!=Direction.WEST && side!=Direction.UP)return null;
            if(side instanceof Direction d){sides.add(d);if(!accepts)return null;}
            return cap.typeClass().cast(new CommunicationEndpoint() {
                public String domain() { return splitSides && !west ? side == Direction.UP ? "internal" : "external" : "primary"; }
                public Optional<EndpointConfiguration> advertisement() { advertisements++; return Optional.of(new EndpointConfiguration(west ? "west" : "gateway",true)); }
                public List<EndpointConfiguration> advertisements() {
                    if (!splitSides || west) return CommunicationEndpoint.super.advertisements();
                    advertisements++;
                    return (side == Direction.UP ? List.of("private") : List.of("public-a", "public-b")).stream().map(name -> new EndpointConfiguration(name, true)).toList();
                }
                public com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus receive(String name,
                    com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope message) {
                    receivedPorts.add(domain() + "/" + name); return receive(message);
                }
                public com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus receive(
                    com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope message) {
                    if (sender != null) {
                        assertEquals(ComputationMachine.Status.SUCCEEDED, sender.snapshot().orElseThrow().machine().status());
                        var saved = new net.minecraft.nbt.CompoundTag(); sender.write(saved, RegistryAccess.EMPTY, false);
                        assertEquals(sender.snapshot().orElseThrow(), ComputerNbtCodec.decode(saved.getCompound(ComputerBehaviour.ROOT), limits.archive()));
                    }
                    received.add(message);
                    return receiverHost == null ? com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.ACCEPTED
                        : receiverHost.receive(message, limits, true);
                }
            });
        }
    }
    private static final class PortBlock extends Block implements DataPort {
        PortBlock(){super(BlockBehaviour.Properties.of());}
        public boolean acceptsCable(BlockState state,Direction side){return true;}
    }
    private static final class Owner extends SmartBlockEntity {
        static final PortBlock PORT=new PortBlock();
        static final BlockEntityType<Owner> TYPE=BlockEntityType.Builder.of(Owner::new,PORT).build(null);
        Owner(BlockPos pos,BlockState state){super(TYPE,pos,state);}
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours){}
        @Override public void setChanged() { /* The test has no chunk persistence backend; host NBT is asserted above. */ }
    }
}
