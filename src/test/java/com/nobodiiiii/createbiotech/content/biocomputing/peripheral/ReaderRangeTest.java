package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class ReaderRangeTest {
 static final ReadLimits LIMITS=new ReadLimits(4096,32768,65536);
 static final UUID DEVICE=UUID.randomUUID(),SESSION=UUID.randomUUID();
 static final String ID=BuiltinReadComponents.INVENTORY_ITEM_TAGS;
 static final DataValue.RecordValue ADDRESS=record(Map.of("x",integer(2),"y",integer(0),"z",integer(0)));
 @BeforeAll static void bootstrap()throws Exception{CapabilitySnapshotValuesTest.bootstrap();}
 static DataValue.RecordValue record(Map<String,DataValue> fields){return new DataValue.RecordValue(fields);}
 static DataValue.TextValue text(String s){return new DataValue.TextValue(s);}
 static DataValue.IntegerValue integer(long n){return new DataValue.IntegerValue(n);}
 static DataValue.RecordValue args(){return record(Map.of("device",text(DEVICE.toString()),"session",text(SESSION.toString()),"component",text(ID),"offset",integer(0),"count",integer(1)));}
 static DataValue.RecordValue command(DataValue.RecordValue args){return record(Map.of("kind",text("reader_command"),"version",integer(1),"target",ADDRESS,"request",text("r"),"operation",text("sample_range"),"arguments",args));}
 static DataValue.RecordValue change(DataValue.RecordValue record,String key,DataValue value){var fields=new LinkedHashMap<>(record.fields());fields.put(key,value);return record(fields);}
 static void error(String code,DataValue value){assertEquals(code,assertInstanceOf(DataValue.ErrorValue.class,value).code());}
 static String code(boolean authorized,DataValue.RecordValue args,ReadLimits limits){var protocol=new ReaderSamplingProtocol(DEVICE,SESSION,ADDRESS,Set.of(ID));return ((DataValue.TextValue)protocol.respond(authorized,command(args),limits,ids->{fail("No whole read");return null;},(id,range)->{fail("Rejected request cannot capture");return null;}).orElseThrow().field("code")).value();}
 @Test void malformedUnauthorizedAndOverBudgetRequestsNeverCapture(){
  assertEquals("denied",code(false,args(),LIMITS));assertEquals("device_changed",code(true,change(args(),"device",text(UUID.randomUUID().toString())),LIMITS));assertEquals("stale_session",code(true,change(args(),"session",text(UUID.randomUUID().toString())),LIMITS));assertEquals("component_denied",code(true,change(args(),"component",text("test:private")),LIMITS));
  for(String key:List.of("offset","count")){assertEquals("invalid_command",code(true,change(args(),key,text("1")),LIMITS));assertEquals("invalid_range",code(true,change(args(),key,integer(-1)),LIMITS));assertEquals("invalid_range",code(true,change(args(),key,integer(Long.MAX_VALUE)),LIMITS));}
  assertEquals("invalid_range",code(true,change(args(),"count",integer(0)),LIMITS));assertEquals("invalid_range",code(true,change(args(),"offset",integer(Integer.MAX_VALUE)),LIMITS));assertEquals("snapshot_limit",code(true,change(args(),"count",integer(4097)),LIMITS));assertEquals("invalid_command",code(true,change(args(),"extra",integer(0)),LIMITS));
 }
 @Test void onlyRequestedSlotsAreReadAndEndClippingIsExplicit(){
  var visited=new ArrayList<Integer>();var handler=new ItemStackHandler(8){@Override public ItemStack getStackInSlot(int slot){visited.add(slot);return super.getStackInSlot(slot);}};
  handler.setStackInSlot(3,new ItemStack(Items.PAPER,7));
  for(var range:List.of(new ReadRange(0,1),new ReadRange(3,2),new ReadRange(7,1),new ReadRange(7,4),new ReadRange(8,1))){
   visited.clear();var page=assertInstanceOf(DataValue.RecordValue.class,CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,range));assertTrue(range.accepts(page));var expected=new ArrayList<Integer>();for(int i=range.offset();i<Math.min(8,range.offset()+range.count());i++)expected.add(i);assertEquals(expected,visited);assertEquals(integer(expected.size()),page.field("count"));assertEquals(integer(8),page.field("total"));
   var items=(DataValue.ListValue)page.field("items");for(int i=0;i<items.values().size();i++)assertEquals(integer(range.offset()+i),((DataValue.RecordValue)items.values().get(i)).field("slot"));
  }
  visited.clear();error("invalid_range",CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,new ReadRange(9,1)));assertTrue(visited.isEmpty());error("snapshot_limit",CapabilitySnapshotValues.inventoryItemTags(handler,new ReadLimits(1,1000,1000),new ReadRange(0,2)));assertTrue(visited.isEmpty());
 }
 @Test void pagesAreIndependentCopiesNotAStableInventoryLease(){
  var handler=new ItemStackHandler(4);handler.setStackInSlot(0,new ItemStack(Items.PAPER,7));var first=(DataValue.RecordValue)CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,new ReadRange(0,1));handler.setStackInSlot(0,new ItemStack(Items.STONE));handler.setSize(1);
  var second=(DataValue.RecordValue)CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,new ReadRange(0,1));assertEquals(integer(4),first.field("total"));assertEquals(integer(1),second.field("total"));assertEquals(text("minecraft:paper"),((DataValue.RecordValue)((DataValue.ListValue)first.field("items")).values().getFirst()).field("item"));error("invalid_range",CapabilitySnapshotValues.inventoryItemTags(handler,LIMITS,new ReadRange(2,1)));
 }
 static ReadComponentRegistry<Void> registry(java.util.function.Function<ReadRange,DataValue> read){return ReadComponentRegistry.<Void>builder().register(new ReadComponentProvider<Void>(){
  public ReadComponentDescriptor descriptor(){return new ReadComponentDescriptor("test:list","label",Set.of("block"),new DataType.ListType(DataType.Scalar.INTEGER),ReadComponentDescriptor.UpdateHint.TICK,false);}
  public boolean supports(Void c){return true;}public DataValue read(Void c){return new DataValue.ListValue(List.of(integer(99)));}public DataValue readRange(Void c,ReadRange range){return read.apply(range);}
 }).build();}
 @Test void genericRangeRegistryRejectsBadMetadataWrongTypesAndSilentShrinking(){
  var range=new ReadRange(1,2);var good=range.page(4,new DataValue.ListValue(List.of(integer(1),integer(2))));assertEquals(good,registry(r->good).session(null,LIMITS).readRange("test:list",range));
  for(var invalid:List.of(change(good,"offset",integer(0)),change(good,"count",integer(1)),change(good,"total",integer(0)),change(good,"next",integer(4)),change(good,"extra",integer(0)),range.page(4,new DataValue.ListValue(List.of(integer(1)))),range.page(4,new DataValue.ListValue(List.of(text("wrong"),text("type"))))))error("invalid_component_type",registry(r->invalid).session(null,LIMITS).readRange("test:list",range));
  error("snapshot_limit",registry(r->good).session(null,new ReadLimits(4096,1,65536)).readRange("test:list",range));error("provider_failure",registry(r->{throw new IllegalStateException("secret");}).session(null,LIMITS).readRange("test:list",range));error("unknown_component",registry(r->good).session(null,LIMITS).readRange("test:missing",range));
 }
 @Test void defaultRangeIsExplicitlyUnsupportedAndNullOrFailedProviderCannotBecomeEmptyPage(){
  var provider=new ReadComponentProvider<Void>(){
   public ReadComponentDescriptor descriptor(){return new ReadComponentDescriptor("test:list","label",Set.of("block"),new DataType.ListType(DataType.Scalar.INTEGER),ReadComponentDescriptor.UpdateHint.TICK,false);}
   public boolean supports(Void c){return true;}public DataValue read(Void c){fail("No fallback to full read");return null;}
  };
  error("unsupported_range",ReadComponentRegistry.<Void>builder().register(provider).build().session(null,LIMITS).readRange("test:list",new ReadRange(0,1)));
  error("provider_failure",registry(r->null).session(null,LIMITS).readRange("test:list",new ReadRange(0,1)));
  var one=new ItemStackHandler(1);var range=new ReadRange(0,1);var page=CapabilitySnapshotValues.inventoryItemTags(one,LIMITS,range);
  assertEquals(page,CapabilitySnapshotValues.inventoryItemTags(one,new ReadLimits(4096,32768,page.storageUnits()),range));
  error("snapshot_limit",CapabilitySnapshotValues.inventoryItemTags(one,new ReadLimits(4096,32768,page.storageUnits()-1),range));
 }
 @Test void rangeRequestsCannotAccidentallyUseIdOnlyFullSnapshotCache(){var calls=new AtomicInteger();var session=registry(range->range.page(100,new DataValue.ListValue(List.of(integer(calls.incrementAndGet()))))).session(null,LIMITS);assertEquals(new DataValue.ListValue(List.of(integer(99))),session.read("test:list"));var first=session.readRange("test:list",new ReadRange(0,1));var second=session.readRange("test:list",new ReadRange(1,1));assertNotEquals(first,second);assertEquals(2,calls.get());}
}
