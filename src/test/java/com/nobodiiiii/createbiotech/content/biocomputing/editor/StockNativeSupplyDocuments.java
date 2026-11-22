package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
public class StockNativeSupplyDocuments {
 public static final List<String> ROLES=List.of("plain_order","prepare","identity_selected","handoff","packet","packet_scoped","supply");
 public static ProgramDocument build(String role) throws Exception {
  var inputs=switch(role){case "supply"->Map.<String,DataType>of("target",SerialExchangeDriverDocuments.TARGET);case "identity_selected"->Map.<String,DataType>of("capacity",INTEGER);default->Map.<String,DataType>of();};
  var outputs=role.equals("supply")?Map.<String,DataType>of("reply",ANY,"job",TEXT):Map.<String,DataType>of();
  var in=new TreeMap<String,String>();inputs.keySet().forEach(k->in.put(k,k));var out=new TreeMap<String,String>();outputs.keySet().forEach(k->out.put(k,k));
  var key=new ModuleDefinition.Key("run",1);var p=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(in,out)),
   new FlowProgram.Procedure("implementation",key,inputs,outputs,out,FlowBlockRules.NONE,Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/stock_native_"+role+".asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
  var initial=switch(role){case "supply"->record("target",record("x",0,"y",0,"z",0));case "identity_selected"->record("capacity",8);default->record();};
  var d=new ProgramWorkspace();d.setFlow(Optional.of(FlowDocumentBody.fromProgram(p,initial)));if(!outputs.isEmpty())d.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("job","reply")));return d.snapshot();
 }
 @Test void completeSoftwareStagesFitAndPublishOnExistingHardware() throws Exception {
  var limits=StockApplicationDriverDocuments.hardware();
  for(var role:ROLES){var d=build(role);System.out.println("NATIVE_SUPPLY_"+role+" "+d.flow().orElseThrow().program(d.archive()).usage(new ResourceLimits(4096,65536,8)));PreparedProgram.prepare(d,limits);
   var name="stock_native_"+role+".bcp";var doc=Path.of("examples/biocomputing",name);var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
   if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_SUPPLY"))){var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(d,limits.archive()))+"\n";Files.writeString(doc,encoded);Files.writeString(asset,encoded);}else{assertEquals(Files.readString(doc),Files.readString(asset));assertEquals(d,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));}
  }
 }
 @Test void craftingOrderIsRejectedBeforeAnyItemOrSupplyOperation() throws Exception {
  StockNativeMaterialDocuments.bootstrap();
  var row=new com.simibubi.create.content.logistics.BigItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),1);
  var pattern=new com.simibubi.create.content.logistics.stockTicker.PackageOrder(List.of(row));
  var order=new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts(pattern,List.of(
   new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CraftingEntry(pattern,2)));
  var nbt=com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CODEC.encodeStart(
   net.minecraft.core.RegistryAccess.EMPTY.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),order).getOrThrow();
  var limits=StockApplicationDriverDocuments.hardware();
  var worker=new ComputerBlockHost(()->{});var caller=new ComputerBlockHost(()->{});
  worker.installProgram(PreparedProgram.prepare(build("plain_order"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
  caller.installFlow(new FlowProgram(Map.of("source",new DataType.RecordType(Map.of("n",NBT))),List.of(
   new FlowProgram.Write("send","x0","source"),new FlowProgram.Read("result","x0","result",StockNativeMaterialDocuments.ORDER_RESULT))).withAssembly(),
   record("source",record("n",StockNativeMaterialDocuments.snapshot(nbt))),limits,true);
  worker.setPaused(false,limits,true);caller.setPaused(false,limits,true);
  for(int tick=1;tick<=10000;tick++){
   // No item worker exists: attempting to delegate crafts cannot accidentally pass this test.
   worker.tick(tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(caller,"x0")));
   caller.tick(tick,limits,true,Map.of());worker.exchange("x0",limits,true,caller,"x0",limits,true);
   var state=worker.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
   if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
  }
  var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
  assertEquals(record("c","unsupported_crafts","i",DataValue.NullValue.INSTANCE),done.locals().field("result"));
 }
}
