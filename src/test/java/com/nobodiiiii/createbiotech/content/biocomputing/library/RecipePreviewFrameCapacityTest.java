package com.nobodiiiii.createbiotech.content.biocomputing.library;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingRecipePreviewGameTests;
import net.minecraft.nbt.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RecipePreviewFrameCapacityTest {
 static final BiocomputingConfig CONFIG=new BiocomputingConfig(new ModConfigSpec.Builder());
 static final LibraryDriveLimits DRIVE=CONFIG.libraryDrive(false);
 static final SerialRecordCodec.Limits CODEC=CONFIG.serialCodec(false);
 static final int FRAME=CONFIG.librarySerial(false).ports().get("s0").maxFrameBytes();
 static final SerialRecordCodec.Limits MEASURE=new SerialRecordCodec.Limits(1000000,10000000,DRIVE.archive());
 static final Map<net.minecraft.tags.TagKey<Item>,List<Holder<Item>>> TAGS=new HashMap<>();
 @BeforeAll static void bootstrap()throws Exception{LibraryBookMediaTest.bootstrap();net.minecraft.core.registries.BuiltInRegistries.ITEM.getTags().forEach(p->TAGS.put(p.getFirst(),p.getSecond().stream().toList()));var map=new HashMap<>(TAGS);map.put(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,net.minecraft.resources.ResourceLocation.parse("c:stones")),List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(Items.STONE)));for(var pair:Map.of("c:ingots/iron",Items.IRON_INGOT,"c:rods/wooden",Items.STICK).entrySet())
   map.put(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,net.minecraft.resources.ResourceLocation.parse(pair.getKey())),List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(pair.getValue())));
  net.minecraft.core.registries.BuiltInRegistries.ITEM.bindTags(map);}
 @AfterAll static void restore(){net.minecraft.core.registries.BuiltInRegistries.ITEM.bindTags(TAGS);}
 static DataValue.RecordValue reply(DataValue.RecordValue value,String request){return new LibraryDriveCommand(new LibraryTrackAddress(14059855,-58,-7558512).value(),new DataValue.TextValue(request),"result",3,DataValue.NullValue.INSTANCE).reply(3,"recipe_page",value);}
 static LibraryReadHead.Sample projection(String raw){
  var book=new ItemStack(Items.WRITABLE_BOOK);book.set(DataComponents.WRITABLE_BOOK_CONTENT,new WritableBookContent(List.of(Filterable.passThrough(raw))));var pos=new BlockPos(14059855,-58,-7558513);var location=new RecipeSourceLocation(Level.OVERWORLD,null,pos);var key=new PatternPageKey(location,0,0);var index=new RecipePageIndex();index.rebuildPageOrder(List.of(key));for(int i=0;i<4&&!index.indexPassComplete();i++)index.tick(ignored->raw,new PatternJsonParser(RegistryAccess.EMPTY),1);assertEquals("valid",index.pageSnapshot(key).status(),index.pageErrors().toString());return new RecipePageRomAdapter(index,ignored->location,RegistryAccess.EMPTY,DRIVE).read(pos,0,0,book);
 }
 static int size(DataValue.RecordValue v){return SerialRecordCodec.encode(v,MEASURE).length;}
 @Test void capturedNative1ReplyWhenEvidenceAvailable()throws Exception{
  Path dir=Path.of(".superpowers/cache/recipe-preview/fix1");
  Assumptions.assumeTrue(Files.isRegularFile(dir.resolve("r.27460.-14763-chunk276.nbt")),"Optional read-only native-1 chunk evidence not present; portable frame regressions remain independent");
  try(var paths=Files.list(dir)){
   DataValue.RecordValue value=null,request=null;for(var p:paths.filter(x->x.toString().endsWith(".nbt")).toList())try(var in=new DataInputStream(Files.newInputStream(p))){var root=NbtIo.read(in,NbtAccounter.unlimitedHeap());for(var t:root.getList("block_entities",10)){var be=(CompoundTag)t;if(be.getString("id").equals("create_biotech:pattern_storage_core")){var drive=LibraryReadHeadCodec.decode(be.getCompound("BiocomputingLibraryDrive"),DRIVE);if(drive.result().isPresent()&&drive.result().orElseThrow().code().equals("recipe_page"))value=(DataValue.RecordValue)drive.result().orElseThrow().value();}if(be.getString("id").equals("create_biotech:computer")&&be.getInt("x")==14059846&&be.getInt("z")==-7558513){var saved=FlowBlockNbtCodec.decode(be.getCompound("Biocomputing"),DRIVE.archive());request=(DataValue.RecordValue)saved.state().locals().field("result");}}}
   assertNotNull(value);assertNotNull(request);var requestId=((DataValue.TextValue)request.field("request")).value();var full=reply(value,requestId);System.out.println("NATIVE1_EXACT page_value="+size(value)+" full_reply="+size(full)+" request="+requestId+" FRAME="+FRAME+" CODEC="+CODEC.maxEncodedBytes()+" DRIVE_BUFFER="+DRIVE.bufferBytes()+" observed="+request.field("code"));assertEquals(SerialRecordCodec.Code.PAYLOAD_TOO_LARGE,assertThrows(SerialRecordCodec.Failure.class,()->SerialRecordCodec.encode(full,new SerialRecordCodec.Limits(FRAME,CODEC.maxDecodeHeapBytes(),CODEC.values()))).code());
  }
 }
 @Test void originalOversizedAndCompactMultipleStagesRespectBothBufferAndFrame() {
  assertEquals(4096,FRAME);assertEquals(65536,CODEC.maxEncodedBytes());assertEquals(131072,DRIVE.bufferBytes());
  String request="00000000-0000-0000-0000-000000000000:999";
  for(boolean seq:List.of(false,true)){
   String raw=BiocomputingRecipePreviewGameTests.originalPage(seq);var sample=projection(raw);var value=sample.value();var full=reply(value,request);var book=(DataValue.NbtValue)sample.fence().field("book");var both=new DataValue.RecordValue(Map.of("fence",sample.fence(),"value",value));
   int bookBytes=NativeNbtCodec.encode(book,new NativeNbtCodec.Limits((int)DRIVE.bufferBytes(),DRIVE.bufferBytes(),DRIVE.archive())).length;
   System.out.println("ROM_MEASURE original_seq="+seq+" raw_chars="+raw.length()+" page_value="+size(value)+" reply="+size(full)+" native_book="+bookBytes+" fence_plus_value="+size(both));assertTrue(size(both)<DRIVE.bufferBytes());
   if(seq){assertEquals(3543,size(value));assertEquals(4231,size(full));assertEquals(SerialRecordCodec.Code.PAYLOAD_TOO_LARGE,assertThrows(SerialRecordCodec.Failure.class,()->SerialRecordCodec.encode(full,new SerialRecordCodec.Limits(FRAME,CODEC.maxDecodeHeapBytes(),CODEC.values()))).code());String minified=com.google.gson.JsonParser.parseString(raw).toString();var mini=projection(minified);assertEquals(size(full),size(reply(mini.value(),request)),"Whitespace affects book fence, not the encoded result shape/frame size");}
   else assertEquals(3532,SerialRecordCodec.encode(full,new SerialRecordCodec.Limits(FRAME,CODEC.maxDecodeHeapBytes(),CODEC.values())).length);
  }
  var sample=projection(BiocomputingRecipePreviewGameTests.compactPage());var value=sample.value();var full=reply(value,request);assertEquals(3292,size(value));assertEquals(3980,size(full));assertTrue(size(reply(value,"00000000-0000-0000-0000-000000000000:"+(Long.MAX_VALUE-1)))<=FRAME);
  assertEquals(2,((DataValue.ListValue)value.field("inputs")).values().size());var common=((DataValue.ListValue)value.field("inputs")).values();assertEquals(common.get(0),common.get(1));var stages=((DataValue.ListValue)((DataValue.RecordValue)value.field("body")).field("stages")).values();assertEquals(2,stages.size());assertEquals(1,((DataValue.ListValue)((DataValue.RecordValue)stages.get(0)).field("inputs")).values().size());assertTrue(((DataValue.ListValue)((DataValue.RecordValue)stages.get(1)).field("inputs")).values().isEmpty());
  System.out.println("COMPACT_POSITIVE common_duplicates=2 stages=tag/empty value="+size(value)+" reply="+size(full)+" worst_correlation_reply="+size(reply(value,"00000000-0000-0000-0000-000000000000:"+(Long.MAX_VALUE-1))));
 }
 static DataValue.RecordValue boundary(int bytes) {
  // ValueNbtCodec field names are UTF-16 too. INTEGER/BOOLEAN payloads supply opposite byte parity.
  for(DataValue unit:List.of(new DataValue.IntegerValue(1),new DataValue.BooleanValue(true))){int base=size(new DataValue.RecordValue(Map.of("x",unit)));int extra=bytes-base;if(extra>=0&&extra%2==0)return new DataValue.RecordValue(Map.of("x".repeat(1+extra/2),unit));}throw new AssertionError("No boundary fixture parity");
 }
 @Test void savedJeiPickaxeCanReachTheTerminalThroughTheLibraryPort()throws IOException {
  String raw;
  try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/biocomputing/jei-iron-pickaxe-v3.json"))) {
   raw=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
  }
  var sample=projection(raw);var value=sample.value();
  var wire=reply(value,"00000000-0000-0000-0000-000000000000:4");
  int bytes=size(wire);System.out.println("PICKAXE result reply: "+bytes+" bytes; port: "+FRAME);
  assertTrue(bytes>FRAME,"This physical page exercises the oversized single-frame case");
  var track=new LibraryTrackAddress(0,0,1);
  var source=new LibraryReadHead.Source(){
   public void validateTrack(LibraryTrackAddress a){assertEquals(track,a);}
   public LibraryReadHead.Sample read(LibraryTrackAddress a,int slot,String entry){throw new AssertionError("Not a named entry");}
   public void verify(LibraryTrackAddress a,int slot,String entry,DataValue.RecordValue fence){throw new AssertionError("Not an entry fence");}
   public LibraryReadHead.Sample recipePage(LibraryTrackAddress a,int slot,int page){assertEquals(0,slot);assertEquals(0,page);return sample;}
   public void verifyRecipePage(LibraryTrackAddress a,int slot,int page,DataValue.RecordValue fence){assertEquals(0,page);assertEquals(sample.fence(),fence);}
  };
  var state=new LibraryReadHead.State(2,Optional.of(track),0,Optional.empty(),Optional.empty());
  var request=new DataValue.TextValue("00000000-0000-0000-0000-000000000000:4");
  var zero=new DataValue.IntegerValue(0);
  var start=new LibraryDriveCommand(track.value(),request,"recipe_page_begin",2,zero).apply(state,source,DRIVE,null,FRAME);
  assertEquals(new DataValue.TextValue("accepted"),start.reply().field("code"));state=start.state();
  boolean restored=false;
  for(int i=0;i<20000 && state.reading().isPresent() && !state.reading().orElseThrow().ready();i++){
   state=LibraryReadHead.prepareTick(state,source,DRIVE).next();
   if(!restored && state.reading().isPresent() && state.reading().get().steps()>2){
    state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,DRIVE),DRIVE);restored=true;
   }
  }
  assertTrue(state.reading().isPresent(),state.result().toString());assertTrue(restored);
  var size=(DataValue.IntegerValue)LibraryReadHead.readInfo(state,source).field("bytes");
  var data=new ByteArrayOutputStream();var limits=new SerialRecordCodec.Limits(FRAME,CODEC.maxDecodeHeapBytes(),CODEC.values());
  while(data.size()<size.value()){
   var range=new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(data.size()),"count",new DataValue.IntegerValue(Math.min(80,size.value()-data.size()))));
   var response=new LibraryDriveCommand(track.value(),request,"read_chunk",state.sequence(),range).apply(state,source,DRIVE,null,FRAME);
   var frame=SerialRecordCodec.decode(SerialRecordCodec.encode(response.reply(),limits),limits);
   var chunk=(DataValue.RecordValue)frame.field("value");assertEquals(new DataValue.IntegerValue(data.size()),chunk.field("offset"));
   var array=(DataValue.ListValue)((DataValue.NbtValue)chunk.field("bytes")).value();
   for(var b:array.values())data.write((int)((DataValue.IntegerValue)b).value());
  }
  var decoded=SerialRecordCodec.decode(data.toByteArray(),CODEC);
  assertEquals(value,decoded.field("value"));assertEquals(value,new DataValue.RecipeValue("recipe",2,(DataValue.RecordValue)decoded.field("value")).projection());
 }
 @Test void actual4096ByteBoundaryDoesNotQueueTruncateOrFragmentOneExtraByte() {
  var exact=boundary(4096);var over=boundary(4097);var bounds=new SerialRecordCodec.Limits(FRAME,CODEC.maxDecodeHeapBytes(),CODEC.values());assertEquals(FRAME,SerialRecordCodec.encode(exact,bounds).length);assertEquals(4097,size(over));assertEquals(SerialRecordCodec.Code.PAYLOAD_TOO_LARGE,assertThrows(SerialRecordCodec.Failure.class,()->SerialRecordCodec.encode(over,bounds)).code());
  var device=new SerialDevice(()->CONFIG.librarySerial(false),()->true,()->CODEC,()->{});device.configure("s0",net.minecraft.core.Direction.EAST,true);var before=device.write();assertEquals(SerialResult.send("queued"),device.prepareSend("s0",exact).result());assertEquals(before,device.write(),"Prepare does not publish");assertEquals(SerialResult.send("payload_too_large"),device.prepareSend("s0",over).result());assertEquals(before,device.write(),"One extra byte neither queues nor fragments a frame");
 }
}
