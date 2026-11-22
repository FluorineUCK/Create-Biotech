package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BoundedEditorEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeripheralNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.items.wrapper.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real container/capability getters; substitutes only the headless world and registration boundary. */
public class InventoryReadSafetyTest {
    private static final ReadLimits LIMITS = new ReadLimits(4096, 32768, 65536);
    private static final ResourceKey<net.minecraft.world.level.storage.loot.LootTable> LOOT =
        ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));

    @BeforeAll static void bootstrap() throws Exception { CapabilitySnapshotValuesTest.bootstrap(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void completeEmptyChestCaptureIncludingCatalogReachesThePlayerWithoutDroppingSlots(boolean paired) throws Exception {
        var world = chest(paired);
        int slots = paired ? 54 : 27;
        var player = actor(world);
        var origin = world.target.west(3);
        var reader = world.target.west();
        var captured = PeripheralCaptureService.capture(new PeripheralCaptureService.CaptureWorld() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                if (pos.equals(world.target.west(2))) return new CableDiscovery.Cell(CableDiscovery.Kind.CABLE, null);
                if (pos.equals(reader)) return new CableDiscovery.Cell(CableDiscovery.Kind.READER, Direction.EAST);
                return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
            }
            public long gameTime() { return world.getGameTime(); }
            public String dimension() { return world.dimension().location().toString(); }
            public DataValue.RecordValue componentTypes() { return ReadComponents.registry().describe(); }
            public DataValue.RecordValue read(BlockPos device, BlockPos target, Direction face) {
                assertEquals(reader, device); assertEquals(world.target, target); assertEquals(Direction.WEST, face);
                return BlockSnapshotReader.read(world, target, face, player, LIMITS);
            }
        }, origin, new PeripheralNetworkLimits(100, 8, LIMITS));
        var snapshot = record(record(captured.field("readers")).fields().values().iterator().next());
        var inventory = list(snapshot.field("inventory"));
        assertEquals(slots, inventory.values().size());
        for (int i = 0; i < slots; i++) {
            var slot = record(inventory.values().get(i));
            assertEquals(new DataValue.IntegerValue(i), slot.field("slot"));
            assertEquals(new DataValue.TextValue("minecraft:air"), slot.field("item"));
            assertCount(0, slot); assertInstanceOf(DataValue.RecordValue.class, slot.field("components"));
        }
        var components = record(snapshot.field("components"));
        assertEquals(13, components.fields().size());
        assertTrue(components.fields().containsKey(CreateReadComponents.STOCK_APPLICATION));
        assertFalse(components.fields().containsKey(CreateFluidReadComponent.ID),
            "Optional pipe telemetry does not enlarge existing default component values");
        assertEquals(new DataValue.ListValue(List.of()), components.field(EntityReadComponents.ENTITIES));
        assertEquals(new DataValue.IntegerValue(0), record(components.field(RedstoneReadComponent.ID)).field("comparator"));
        assertInstanceOf(DataValue.RecordValue.class, components.field(WorldReadComponents.WORLD));
        assertInstanceOf(DataValue.RecordValue.class, components.field(WorldReadComponents.ENVIRONMENT));
        assertInstanceOf(DataValue.RecordValue.class, components.field(BuiltinReadComponents.SERIALIZED_DATA));
        var inputs = new DataValue.RecordValue(Map.of("peripherals", captured));
        var envelope = new DataValue.RecordValue(Map.of("captured_inputs", inputs));
        var archives = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        System.out.printf("capture measurement: slots=%d catalog=%d inventory=%d full=%d units=%d%n", slots,
            rawWireSize(captured.field("component_types"), archives), rawWireSize(inventory, archives),
            rawWireSize(envelope, archives), inputs.storageUnits());
        var answer = new ComputerControlSession.Answer("ok", 1, "", Optional.empty(), Optional.of(inputs));
        var response = ProgramStatusResponse.fromAnswer(1, UUID.randomUUID(), 1, answer, archives);
        assertEquals("ok", response.code(), response.detail());
        System.out.printf("compacted capture: slots=%d bytes=%d%n", slots, response.status().length);
        assertEquals(envelope, BoundedEditorEnvelope.CAPTURE.decode(response.status(), archives));
    }

    private static int rawWireSize(DataValue value, ArchiveLimits limits) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { buffer.writeNbt(ValueNbtCodec.encode(value, limits)); return buffer.readableBytes(); }
        finally { buffer.release(); }
    }

    @Test void pendingBarrelLootIsRejectedBeforeCapabilityOrGetItemAndClearedLootReadsNormally() throws Exception {
        var world = barrel(); var target = (ObservedBarrel) world.entity;
        target.getItemsForTest().set(0, new ItemStack(Items.STONE, 7));
        target.setLootTable(LOOT, 73L);
        assertError("unresolved_loot", inventory(world, Direction.NORTH));
        assertEquals(0, world.capabilities); assertEquals(0, target.unpacks);
        assertEquals(LOOT, target.getLootTable()); assertEquals(73L, target.getLootTableSeed());
        assertEquals(7, target.getItemsForTest().getFirst().getCount());
        target.setLootTable(null);
        var copy = list(inventory(world, Direction.NORTH));
        assertEquals(27, copy.values().size()); assertCount(7, copy.values().getFirst());
        target.getItemsForTest().getFirst().setCount(9);
        assertCount(7, copy.values().getFirst());
        assertCount(9, list(inventory(world, Direction.NORTH)).values().getFirst());
    }

    @Test void doubleChestMustPreflightBeforeResolvingCapabilityAndNeverReturnHalfAnInventory() throws Exception {
        var world = chest(true);
        assertEquals(54, list(inventory(world, Direction.NORTH)).values().size());
        world.capabilities = 0; world.unloaded = world.target.east();
        assertError("target_unloaded", inventory(world, Direction.NORTH));
        assertEquals(0, world.capabilities); assertEquals(0, world.unloadedReads);
        world.unloaded = world.target.west();
        assertEquals(54, list(inventory(world, Direction.NORTH)).values().size());
        world.state = world.state.setValue(ChestBlock.TYPE, ChestType.SINGLE);
        world.unloaded = world.target.east();
        assertEquals(27, list(inventory(world, Direction.NORTH)).values().size());
    }

    @Test void inventoryIgnoresLidObstructionButStillProtectsPendingParticipatingLoot() throws Exception {
        var world = chest(true);
        world.states.put(world.target.above(), Blocks.STONE.defaultBlockState());
        world.states.put(world.target.east().above(), Blocks.STONE.defaultBlockState());
        assertEquals(54, list(inventory(world, Direction.NORTH)).values().size());
        world.unloaded = world.target.above();
        assertEquals(54, list(inventory(world, Direction.NORTH)).values().size());
        var partner = (ObservedChest) world.entities.get(world.target.east());
        partner.unpacks = 0; partner.setLootTable(LOOT, 31L); world.capabilities = 0;
        assertError("unresolved_loot", inventory(world, Direction.NORTH));
        assertEquals(0, world.capabilities); assertEquals(0, partner.unpacks);
        assertEquals(LOOT, partner.getLootTable()); assertEquals(31L, partner.getLootTableSeed());
    }

    @Test void onlyActualPartnersContributeLootAndMissingOrMismatchedPartnersKeepVanillaSingleFallback() throws Exception {
        for (String mismatch : List.of("single", "same_type", "facing", "block", "missing_entity")) {
            var world = chest(true); var pos = world.target.east();
            ((ObservedChest) world.entities.get(pos)).setLootTable(LOOT);
            switch (mismatch) {
                case "single" -> world.states.put(pos, world.states.get(pos).setValue(ChestBlock.TYPE, ChestType.SINGLE));
                case "same_type" -> world.states.put(pos, world.state);
                case "facing" -> world.states.put(pos, world.states.get(pos).setValue(ChestBlock.FACING, Direction.SOUTH));
                case "block" -> world.states.put(pos, Blocks.STONE.defaultBlockState());
                case "missing_entity" -> world.entities.remove(pos);
            }
            assertEquals(27, list(inventory(world, Direction.NORTH)).values().size(), mismatch);
            ((ObservedChest) world.entity).setLootTable(LOOT);
            assertError("unresolved_loot", inventory(world, Direction.NORTH));
        }
    }

    @Test void realSidedWrapperKeepsFaceAndVisibleSlotsRatherThanExposingEveryContainerSlot() throws Exception {
        var world = base(); world.state = Blocks.FURNACE.defaultBlockState();
        var furnace = new SeededFurnace(world.target, world.state); world.entity = furnace;
        furnace.seed(0, new ItemStack(Items.IRON_ORE, 3));
        furnace.seed(1, new ItemStack(Items.COAL, 5));
        furnace.seed(2, new ItemStack(Items.IRON_INGOT, 7));
        var top = list(inventory(world, Direction.UP));
        assertEquals(Direction.UP, world.lastFace); assertEquals(1, top.values().size()); assertCount(3, top.values().getFirst());
        var bottom = list(inventory(world, Direction.DOWN));
        assertEquals(Direction.DOWN, world.lastFace); assertEquals(2, bottom.values().size());
        assertCount(7, bottom.values().get(0)); assertCount(5, bottom.values().get(1));
    }

    @Test void errorsCacheInOneCaptureAndFreshSessionCanReadWithoutSerializedDataPermission() throws Exception {
        var world = barrel(); var target = (ObservedBarrel) world.entity; target.setLootTable(LOOT);
        var context = new BlockReadContext(world, world.target, Direction.EAST, actor(world), world.state,
            target, LIMITS, false);
        var session = ReadComponents.registry().session(context, LIMITS);
        var first = session.read(BuiltinReadComponents.INVENTORY);
        assertError("unresolved_loot", first);
        target.setLootTable(null);
        assertSame(first, session.read(BuiltinReadComponents.INVENTORY)); assertEquals(0, world.capabilities);
        assertEquals(27, list(ReadComponents.registry().session(context, LIMITS).read(BuiltinReadComponents.INVENTORY)).values().size());
    }

    @Test void authorizationAndProviderFailuresRemainExplicitAndPrecedeUnsafeAccess() throws Exception {
        var world = barrel();
        assertEquals(27, list(inventory(world, Direction.NORTH)).values().size());
        world.capabilities = 0; world.allowed = false;
        assertError("access_denied", selected(world, LIMITS).field("error"));
        assertEquals(0, world.capabilities);
        world.allowed = true; world.unloaded = world.target;
        assertError("target_unloaded", selected(world, LIMITS).field("error"));
        assertEquals(0, world.capabilities); assertEquals(0, world.unloadedReads);
        world.unloaded = null; world.failCapability = true;
        assertError("provider_failure", inventory(world, Direction.NORTH));
        world.failCapability = false; world.noCapability = true;
        assertError("unsupported", inventory(world, Direction.NORTH));
        world.noCapability = false;
        assertError("snapshot_limit", selected(world, new ReadLimits(20, 32768, 65536)).field(BuiltinReadComponents.INVENTORY));
        assertEquals(27, list(inventory(world, Direction.NORTH)).values().size());
    }

    @Test void fullCaptureKeepsInventoryErrorAndLegacyAliasWithoutLootGenerationOrChangingOtherComponents() throws Exception {
        var world = chest(true); var pending = (ObservedChest) world.entities.get(world.target.east());
        pending.setLootTable(LOOT, 19L);
        var snapshot = BlockSnapshotReader.read(world, world.target, Direction.NORTH, actor(world), LIMITS);
        assertError("unresolved_loot", snapshot.field("inventory"));
        var components = record(snapshot.field("components"));
        assertEquals(snapshot.field("inventory"), components.field(BuiltinReadComponents.INVENTORY));
        assertEquals(new DataValue.TextValue("minecraft:chest"), record(components.field(BuiltinReadComponents.BLOCK)).field("id"));
        assertInstanceOf(DataValue.RecordValue.class, components.field(WorldReadComponents.WORLD));
        assertEquals(0, pending.unpacks); assertEquals(LOOT, pending.getLootTable());
        var archiveLimits = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        assertEquals(snapshot, ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(snapshot, archiveLimits), archiveLimits));
        pending.setLootTable(null);
        var normal = BlockSnapshotReader.read(world, world.target, Direction.NORTH, actor(world), LIMITS);
        assertEquals(54, list(normal.field("inventory")).values().size());
        assertError("unresolved_loot", snapshot.field("inventory"));
    }

    @Test void explicitRangeOnRealBarrelReachesFiniteReaderFrameWithoutReadingWholeInventory() throws Exception {
        var world=barrel();var target=(ObservedBarrel)world.entity;target.getItemsForTest().set(5,new ItemStack(Items.STONE,7));
        var player=actor(world);var address=BlockSnapshotReader.position(world.target.west());var device=UUID.randomUUID();var session=UUID.randomUUID();
        var protocol=new ReaderSamplingProtocol(device,session,address,Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        var command=new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("reader_command"),"version",new DataValue.IntegerValue(1),"target",address,"request",new DataValue.TextValue("range-1"),"operation",new DataValue.TextValue("sample_range"),"arguments",new DataValue.RecordValue(Map.of("device",new DataValue.TextValue(device.toString()),"session",new DataValue.TextValue(session.toString()),"component",new DataValue.TextValue(BuiltinReadComponents.INVENTORY_ITEM_TAGS),"offset",new DataValue.IntegerValue(5),"count",new DataValue.IntegerValue(2)))));
        var reply=protocol.respond(true,command,LIMITS,ids->{fail("Range must not call whole component capture");return null;},(component,range)->{var page=BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,component,range);var fields=new LinkedHashMap<>(page.fields());fields.put("game_time",new DataValue.IntegerValue(world.getGameTime()));fields.put("target",BlockSnapshotReader.position(world.target));return new DataValue.RecordValue(fields);}).orElseThrow();
        assertEquals(new DataValue.TextValue("ok"),reply.field("code"));
        var value=record(reply.field("value"));var rows=list(record(value.field("components")).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));assertEquals(2,rows.values().size());assertEquals(List.of(5,6),target.slotsRead);
        assertEquals(new DataValue.IntegerValue(5),record(rows.values().getFirst()).field("slot"));assertEquals(new DataValue.TextValue("minecraft:stone"),record(rows.values().getFirst()).field("item"));assertEquals(new DataValue.TextValue("minecraft:air"),record(rows.values().getLast()).field("item"));
        assertEquals(new DataValue.IntegerValue(27),record(value.field("range")).field("total"));assertEquals(new DataValue.IntegerValue(7),record(value.field("range")).field("next"));
        var config=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());var codec=config.serialCodec(false);int frame=config.readerSerial(false).ports().get("s0").maxFrameBytes();
        var bounded=new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(frame,codec.maxDecodeHeapBytes(),codec.values());int bytes=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(reply,bounded).length;assertTrue(bytes<=4096);System.out.println("READER_RANGE_BARREL offset=5 count=2 total=27 reply="+bytes+" frame="+frame);assertEquals(7,target.getItemsForTest().get(5).getCount());assertEquals(Direction.NORTH,world.lastFace);
    }

    @Test void optInTagFactsUseIdenticalGuardsAndFaceWhileLegacyInventoryStaysUnchanged() throws Exception {
        var world = barrel(); var target = (ObservedBarrel)world.entity;
        target.getItemsForTest().set(0, new ItemStack(Items.STONE,7));
        var before = inventory(world,Direction.NORTH);
        var metadata = BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,actor(world),LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        var rows = list(metadata.field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        assertEquals(27,rows.values().size());assertEquals(Set.of("slot","item","tags"),record(rows.values().getFirst()).fields().keySet());
        var readerAddress=BlockSnapshotReader.position(world.target.west());var device=UUID.randomUUID();var session=UUID.randomUUID();
        var protocol=new ReaderSamplingProtocol(device,session,readerAddress,Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        var request=new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("reader_command"),"version",new DataValue.IntegerValue(1),"target",readerAddress,"request",new DataValue.TextValue("00000000-0000-0000-0000-000000000000:9223372036854775806"),"operation",new DataValue.TextValue("sample"),"arguments",new DataValue.RecordValue(Map.of("device",new DataValue.TextValue(device.toString()),"session",new DataValue.TextValue(session.toString()),"components",new DataValue.ListValue(List.of(new DataValue.TextValue(BuiltinReadComponents.INVENTORY_ITEM_TAGS)))))));
        var reply=protocol.respond(true,request,ids->new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(world.getGameTime()),"target",BlockSnapshotReader.position(world.target),"components",metadata))).orElseThrow();
        var codec=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder()).serialCodec(false);
        int componentBytes=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(metadata,codec).length;
        int replyBytes=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(reply,codec).length;
        System.out.println("ITEM_TAG_READER_FRAME slots=27 component="+componentBytes+" reply="+replyBytes+" codec="+codec.maxEncodedBytes()+" correlation=max_uuid_sequence");
        var config=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        int frame=config.readerSerial(false).ports().get("s0").maxFrameBytes();assertEquals(4096,frame);
        var frameCodec=new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(frame,codec.maxDecodeHeapBytes(),codec.values());
        assertTrue(replyBytes>frame);var refusal=assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Failure.class,
            ()->com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(reply,frameCodec));
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Code.PAYLOAD_TOO_LARGE,refusal.code());
        assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(ReaderSamplingProtocol.failure(reply,"payload_too_large"),frameCodec).length<=frame,"Normal visible rejection fits; no truncation");
        var sided=base();sided.state=Blocks.FURNACE.defaultBlockState();var furnace=new SeededFurnace(sided.target,sided.state);sided.entity=furnace;furnace.seed(0,new ItemStack(Items.IRON_ORE,3));
        var oneSlot=BlockSnapshotReader.readComponents(sided,sided.target,Direction.UP,actor(sided),LIMITS,Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        assertEquals(1,list(oneSlot.field(BuiltinReadComponents.INVENTORY_ITEM_TAGS)).values().size());assertEquals(Direction.UP,sided.lastFace);
        var small=protocol.respond(true,request,ids->new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(sided.getGameTime()),"target",BlockSnapshotReader.position(sided.target),"components",oneSlot))).orElseThrow();
        int smallBytes=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(small,frameCodec).length;
        System.out.println("ITEM_TAG_READER_FRAME sided_furnace_slots=1 reply="+smallBytes+" frame="+frame+" codec="+codec.maxEncodedBytes());
        assertCount(3,list(inventory(sided,Direction.UP)).values().getFirst());


        assertEquals(Direction.NORTH,world.lastFace);assertEquals(before,inventory(world,Direction.NORTH));
        var legacy=BlockSnapshotReader.read(world,world.target,Direction.NORTH,actor(world),LIMITS);
        assertFalse(record(legacy.field("components")).fields().containsKey(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        assertEquals(before,legacy.field("inventory"));
        target.setLootTable(LOOT);world.capabilities=0;target.unpacks=0;
        assertError("unresolved_loot",BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,actor(world),LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS)).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        assertEquals(0,world.capabilities);assertEquals(0,target.unpacks);target.setLootTable(null);
        world.noCapability=true;
        assertError("unsupported",BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,actor(world),LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS)).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        world.noCapability=false;world.failCapability=true;
        assertError("provider_failure",BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,actor(world),LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS)).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        world.failCapability=false;world.allowed=false;world.capabilities=0;
        assertError("access_denied",BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,actor(world),LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS)).field("error"));assertEquals(0,world.capabilities);
    }

    /** Real guarded barrel fixture shared with the public three-owner serial test. */
    public static final class RangeFixture {
        private final TestWorld world; private final TestPlayer player;
        private RangeFixture() throws Exception {
            bootstrap(); world=barrel(); player=actor(world);
            ((ObservedBarrel)world.entity).getItemsForTest().set(5,new ItemStack(Items.STONE,7));
        }
        public BlockPos reader(){return world.target.west();}
        public DataValue.RecordValue capture(String id,ReadRange range){
            var page=BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,id,range);
            if(page.fields().containsKey("error"))return page;
            var fields=new LinkedHashMap<>(page.fields());fields.put("game_time",new DataValue.IntegerValue(world.getGameTime()));fields.put("target",BlockSnapshotReader.position(world.target));return new DataValue.RecordValue(fields);
        }
        public DataValue.RecordValue full(Set<String> ids){
            return new DataValue.RecordValue(Map.of("game_time",new DataValue.IntegerValue(world.getGameTime()),"target",BlockSnapshotReader.position(world.target),"components",BlockSnapshotReader.readComponents(world,world.target,Direction.NORTH,player,LIMITS,ids)));
        }
        public List<Integer> slots(){return List.copyOf(((ObservedBarrel)world.entity).slotsRead);}
        public int seedCount(){return ((ObservedBarrel)world.entity).getItemsForTest().get(5).getCount();}
    }
    public static RangeFixture rangeFixture()throws Exception{return new RangeFixture();}
    @Test void rangeUsesSameTargetGuardAndDoesNotProbeDeniedOrUnloadedOrLootInventory()throws Exception{
        var world=barrel();var target=(ObservedBarrel)world.entity;var player=actor(world);var range=new ReadRange(5,2);
        world.allowed=false;assertError("access_denied",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,range).field("error"));
        world.allowed=true;world.unloaded=world.target;assertError("target_unloaded",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,range).field("error"));
        world.unloaded=null;target.setLootTable(LOOT);assertError("unresolved_loot",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,range).field("error"));
        assertEquals(0,world.capabilities);assertTrue(target.slotsRead.isEmpty());assertEquals(0,target.unpacks);assertEquals(0,world.unloadedReads);
        target.setLootTable(null);world.noCapability=true;assertError("unsupported",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,range).field("error"));
        world.noCapability=false;world.failCapability=true;assertError("provider_failure",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,range).field("error"));
        assertTrue(target.slotsRead.isEmpty());
        assertError("unknown_component",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,"missing:component",range).field("error"));
        assertError("unsupported_range",BlockSnapshotReader.readRange(world,world.target,Direction.NORTH,player,LIMITS,BuiltinReadComponents.BLOCK,range).field("error"));
    }

    private static DataValue inventory(TestWorld world, Direction face) throws Exception {
        return BlockSnapshotReader.readComponents(world, world.target, face, actor(world), LIMITS,
            Set.of(BuiltinReadComponents.INVENTORY)).field(BuiltinReadComponents.INVENTORY);
    }
    private static DataValue.RecordValue selected(TestWorld world, ReadLimits limits) throws Exception {
        return BlockSnapshotReader.readComponents(world, world.target, Direction.NORTH, actor(world), limits,
            Set.of(BuiltinReadComponents.INVENTORY));
    }
    private static DataValue.RecordValue record(DataValue value) { return assertInstanceOf(DataValue.RecordValue.class, value); }
    private static DataValue.ListValue list(DataValue value) { return assertInstanceOf(DataValue.ListValue.class, value); }
    private static void assertError(String code, DataValue value) { assertEquals(code, assertInstanceOf(DataValue.ErrorValue.class, value).code()); }
    private static void assertCount(int count, DataValue value) { assertEquals(new DataValue.IntegerValue(count), record(value).field("count")); }
    private static TestPlayer actor(TestWorld world) throws Exception { var p = allocate(TestPlayer.class); p.world = world; return p; }
    private static TestWorld base() throws Exception {
        var world = allocate(TestWorld.class); world.allowed = true; world.border = new WorldBorder();
        world.target = new BlockPos(15, 16, 0); world.states = new HashMap<>(); world.entities = new HashMap<>();
        return world;
    }
    private static TestWorld barrel() throws Exception {
        var world = base(); world.state = Blocks.BARREL.defaultBlockState();
        world.entity = new ObservedBarrel(world.target, world.state); return world;
    }
    private static TestWorld chest(boolean paired) throws Exception {
        var world = base(); world.state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, paired ? ChestType.LEFT : ChestType.SINGLE);
        world.entity = new ObservedChest(world.target, world.state);
        if (paired) {
            var pos = world.target.east(); var state = world.state.setValue(ChestBlock.TYPE, ChestType.RIGHT);
            world.states.put(pos, state); world.entities.put(pos, new ObservedChest(pos, state));
        }
        return world;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class TestPlayer extends ServerPlayer {
        TestWorld world;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel serverLevel() { return world; }
    }
    private static final class TestWorld extends ServerLevel {
        BlockPos target, unloaded; BlockState state; BlockEntity entity; WorldBorder border;
        Map<BlockPos, BlockState> states; Map<BlockPos, BlockEntity> entities;
        boolean allowed, failCapability, noCapability; int capabilities, unloadedReads; Direction lastFace;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight() { return 32; }
        @Override public boolean isLoaded(BlockPos pos) { return !pos.equals(unloaded); }
        @Override public boolean mayInteract(Player player, BlockPos pos) { return allowed; }
        @Override public WorldBorder getWorldBorder() { return border; }
        private void check(BlockPos pos) {
            assertTrue(allowed, "Access must be denied before reading world");
            if (pos.equals(unloaded)) { unloadedReads++; throw new IllegalStateException("Unloaded query"); }
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (isOutsideBuildHeight(pos)) return Blocks.AIR.defaultBlockState();
            check(pos); return pos.equals(target) ? state : states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { check(pos); return pos.equals(target) ? entity : entities.get(pos); }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
        @SuppressWarnings("unchecked")
        @Override public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos pos, C side) {
            if (capability != Capabilities.ItemHandler.BLOCK) return null;
            check(pos); capabilities++; lastFace = (Direction) side;
            if (failCapability) throw new IllegalStateException("private provider detail");
            if (noCapability) return null;
            // Exact vanilla registrations from CapabilityHooks; combination/wrappers/getItem are real.
            if (state.getBlock() instanceof ChestBlock chest)
                return (T) new InvWrapper(ChestBlock.getContainer(chest, state, this, pos, true));
            if (entity instanceof WorldlyContainer container) return (T) new SidedInvWrapper(container, lastFace);
            if (entity instanceof Container container) return (T) new InvWrapper(container);
            return null;
        }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 120; }
        @Override public long getDayTime() { return 120; }
        @Override public boolean isDay() { return true; }
        @Override public boolean isNight() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public boolean isThundering() { return false; }
        @Override public float getRainLevel(float partial) { return 0; }
        @Override public float getThunderLevel(float partial) { return 0; }
        @Override public Holder<Biome> getBiome(BlockPos pos) {
            return Holder.Reference.createStandAlone(new HolderOwner<Biome>() {}, ResourceKey.create(Registries.BIOME,
                ResourceLocation.withDefaultNamespace("plains")));
        }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 0; }
        @Override public int getMaxLocalRawBrightness(BlockPos pos) { return 0; }
        @Override public boolean canSeeSky(BlockPos pos) { return false; }
        @Override public <T extends net.minecraft.world.entity.Entity> List<T> getEntitiesOfClass(Class<T> type,
                net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super T> predicate) { return List.of(); }
        @Override public <T extends net.minecraft.world.entity.Entity> void getEntities(
                net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, T> type,
                net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super T> predicate,
                List<? super T> output, int maximum) { /* Empty world entity index. */ }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { throw new AssertionError("No writes"); }
    }
    /** Seeds saved contents without starting the furnace's cooking/recipe lifecycle. Read APIs remain vanilla. */
    private static final class SeededFurnace extends FurnaceBlockEntity {
        SeededFurnace(BlockPos pos, BlockState state) { super(pos, state); }
        void seed(int slot, ItemStack stack) { getItems().set(slot, stack); }
    }
    private static final class ObservedChest extends ChestBlockEntity {
        int unpacks;
        ObservedChest(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public void unpackLootTable(Player player) { unpacks++; super.unpackLootTable(player); }
    }
    private static final class ObservedBarrel extends BarrelBlockEntity {
        final List<Integer> slotsRead=new ArrayList<>();
        @Override public ItemStack getItem(int slot){slotsRead.add(slot);return super.getItem(slot);}
        int unpacks;
        ObservedBarrel(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public void unpackLootTable(Player player) { unpacks++; super.unpackLootTable(player); }
        NonNullList<ItemStack> getItemsForTest() { return getItems(); }
    }
}
