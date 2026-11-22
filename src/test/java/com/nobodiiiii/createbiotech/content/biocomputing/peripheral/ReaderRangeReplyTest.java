package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderRangeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.*;

class ReaderRangeReplyTest {
    @BeforeAll static void bootstrap()throws Exception{CapabilitySnapshotValuesTest.bootstrap();}
    @Test void finitePreparedReplyPublishesWholePageOrShortFailureNeverShrinksAndPreservesFollower()throws Exception{
        var fixture=InventoryReadSafetyTest.rangeFixture();
        var protocol=new ReaderSamplingProtocol(DEVICE,SESSION,ADDRESS,Set.of(ID));
        for(int count:List.of(2,27)){
            var request=command(change(args(),"count",integer(count)));
            var reply=protocol.respond(true,request,LIMITS,ids->{fail("No full capture");return null;},fixture::capture).orElseThrow();
            assertEquals(integer(count),((DataValue.RecordValue)((DataValue.RecordValue)reply.field("value")).field("range")).field("count"));
            exchange(request,reply,count==2?"ok":"payload_too_large");
        }
        var oldArgs=record(Map.of("device",text(DEVICE.toString()),"session",text(SESSION.toString()),"components",new DataValue.ListValue(List.of(text(ID)))));
        var oldRequest=change(command(oldArgs),"operation",text("sample"));
        exchange(oldRequest,protocol.respond(true,oldRequest,fixture::full).orElseThrow(),"payload_too_large");
        assertEquals(7,fixture.seedCount());
    }
    @Test void oneLargeTaggedSlotIsNotGuaranteedToFitAndTagEnumerationStillHonorsBudget(){
        var registry=BuiltInRegistries.ITEM;var before=new LinkedHashMap<TagKey<Item>,List<Holder<Item>>>();
        registry.getTags().forEach(pair->before.put(pair.getFirst(),pair.getSecond().stream().toList()));
        var previousPaperTags=Items.PAPER.builtInRegistryHolder().tags().collect(java.util.stream.Collectors.toSet());
        try{
            var tags=new LinkedHashMap<>(before);
            for(int i=0;i<120;i++)tags.put(TagKey.create(Registries.ITEM,ResourceLocation.parse("range_test:tag_"+i)),List.of(Items.PAPER.builtInRegistryHolder()));
            registry.bindTags(tags);var handler=new ItemStackHandler(1);handler.setStackInSlot(0,new ItemStack(Items.PAPER,7));var range=new ReadRange(0,1);
            error("snapshot_limit",CapabilitySnapshotValues.inventoryItemTags(handler,new ReadLimits(8,32768,65536),range));
            var page=(DataValue.RecordValue)CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,range);
            var row=(DataValue.RecordValue)((DataValue.ListValue)page.field("items")).values().getFirst();
            assertEquals(Items.PAPER.builtInRegistryHolder().tags().map(t->t.location().toString()).sorted().map(DataValue.TextValue::new).toList(),((DataValue.ListValue)row.field("tags")).values());
            var protocol=new ReaderSamplingProtocol(DEVICE,SESSION,ADDRESS,Set.of(ID));var request=command(args());
            var reply=protocol.respond(true,request,LIMITS,ids->{fail();return null;},(id,r)->record(Map.of("game_time",integer(1),"target",ADDRESS,"components",record(Map.of(ID,page.field("items"))),"range",ReadRange.metadata(page)))).orElseThrow();
            exchange(request,reply,"payload_too_large");assertEquals(7,handler.getStackInSlot(0).getCount());
        }finally{registry.resetTags();registry.bindTags(before);}
        assertEquals(previousPaperTags,Items.PAPER.builtInRegistryHolder().tags().collect(java.util.stream.Collectors.toSet()),"Test must not leak synthetic tag membership");
    }
    private static void exchange(DataValue.RecordValue request,DataValue.RecordValue reply,String expected){
        var config=new BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        var reader=new SerialDevice(()->config.readerSerial(false),()->true,()->config.serialCodec(false),()->{});
        var peer=new SerialDevice(()->config.readerSerial(false),()->true,()->config.serialCodec(false),()->{});
        reader.configure("s0",Direction.EAST,true);peer.configure("s0",Direction.WEST,true);
        var receiver=reader.attachments().getFirst().receiver();var bytes=SerialRecordCodec.encode(request,config.serialCodec(false));
        assertEquals(SerialReceiveBuffer.Admission.ACCEPTED,receiver.offer(bytes));assertEquals(SerialReceiveBuffer.Admission.ACCEPTED,receiver.offer(bytes));
        var prepared=reader.prepareReceive("s0");int full=SerialRecordCodec.encode(reply,config.serialCodec(false)).length;
        assertEquals(expected.equals("ok"),full<=4096);
        assertEquals(Optional.of(expected),ReaderSerialBehaviour.publishReply(prepared,reply));
        assertEquals(1,reader.status("s0").receive().frames());assertEquals(request,reader.prepareReceive("s0").result().field("value"));assertFalse(prepared.publish());
        var devices=Map.of(BlockPos.ZERO,reader,new BlockPos(2,0,0),peer);var network=new SerialNetwork();
        var world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0))?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
        int ticks=0;while(peer.status("s0").receive().frames()==0&&ticks++<1000)network.tick(world,devices,config.communication(false),config.serialMedium(false));
        assertEquals(1,peer.status("s0").receive().frames());
        assertEquals(expected.equals("ok")?reply:ReaderSamplingProtocol.failure(reply,expected),peer.prepareReceive("s0").result().field("value"));
        System.out.println("RANGE_PREPARED fullBytes="+full+" frame=4096 result="+expected+" wireTicks="+ticks);
    }
}
