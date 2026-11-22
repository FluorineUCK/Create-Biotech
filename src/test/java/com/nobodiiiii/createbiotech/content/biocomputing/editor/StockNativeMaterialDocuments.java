package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Maintainer packaging only. Item interpretation is entirely in the exported ISA source. */
public class StockNativeMaterialDocuments {
    static final DataType.RecordType ITEM=new DataType.RecordType(Map.of("item",TEXT,"count",INTEGER,"components",NBT));
    static final DataType.RecordType ROW_RESULT=new DataType.RecordType(Map.of("c",TEXT,"i",new DataType.OptionalType(ITEM)));
    static final DataType.RecordType ORDER_RESULT=new DataType.RecordType(Map.of("c",TEXT,"i",new DataType.OptionalType(new DataType.ListType(ITEM))));
    public static ProgramDocument build(String role) throws Exception {
        boolean preview=role.equals("preview");
        var inputs=preview?Map.<String,DataType>of("order",NBT):Map.<String,DataType>of();
        var outputs=preview?Map.<String,DataType>of("result",ORDER_RESULT):Map.<String,DataType>of();
        var inputBindings=preview?Map.of("order","order"):Map.<String,String>of();
        var outputBindings=preview?Map.of("result","result"):Map.<String,String>of();
        var key=new ModuleDefinition.Key("convert",1);
        var source=Files.readString(Path.of("examples/biocomputing/stock_native_"+role+".asm"));
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("serve",key,new FlowCallBindings(inputBindings,outputBindings)),
            new FlowProgram.Procedure("convert_definition",key,inputs,outputs,outputBindings,FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("convert_end"))).withAssembly();
        var initial=preview?record("order",snapshot(TagParser.parseTag(Files.readString(Path.of("examples/biocomputing/stock_native_sample.snbt"))))):record();
        var draft=new ProgramWorkspace(); draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));
        if(preview)draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("result")));
        return draft.snapshot();
    }
    @Test void publishedExamplesAreOrdinaryEditableIsaAndFitTheExistingHardware() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        for(var role:List.of("item","order","preview")) {
            var document=build(role); PreparedProgram.prepare(document,limits);
            System.out.println("NATIVE_"+role+" "+document.flow().orElseThrow().program(document.archive()).usage(limits.hardware()));
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/stock_native_"+role+".bcp");
            var doc=Path.of("examples/biocomputing/stock_native_"+role+".bcp");
            if("1".equals(System.getenv("GENERATE_STOCK_NATIVE"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
                Files.writeString(asset,encoded); Files.writeString(doc,encoded);
            } else {
                assertEquals(Files.readString(doc),Files.readString(asset));
                assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> mods=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(mods.getMethod("get").invoke(null)==null)
            mods.getMethod("of",List.class,List.class,List.class,List.class,Map.class).invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); flag.setAccessible(true); flag.setBoolean(null,true);
    }
    static DataValue.NbtValue snapshot(Tag tag) {
        return NativeNbtCodec.snapshot(tag,new NativeNbtCodec.Limits(65536,1048576,StockApplicationDriverDocuments.hardware().archive()));
    }
    static CompoundTag nativeOrder() {
        var paper=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER,2);
        paper.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,16);
        assertTrue(paper.has(net.minecraft.core.component.DataComponents.RARITY));
        paper.remove(net.minecraft.core.component.DataComponents.RARITY);
        var rows=List.of(new com.simibubi.create.content.logistics.BigItemStack(paper,7),
            new com.simibubi.create.content.logistics.BigItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE),3));
        var order=new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts(
            new com.simibubi.create.content.logistics.stockTicker.PackageOrder(rows),List.of());
        return (CompoundTag)com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CODEC.encodeStart(
            net.minecraft.core.RegistryAccess.EMPTY.createSerializationContext(NbtOps.INSTANCE),order).getOrThrow();
    }
    @Test void rowConversionUsesRequestedCountAndPreservesComponentPatchesWithoutJavaPackaging() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware(); var document=build("item");
        System.out.println("NATIVE_ITEM "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
        var worker=new ComputerBlockHost(()->{});
        worker.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        worker.setPaused(false,limits,true);
        var rows=nativeOrder().getCompound("ordered_stacks").getList("entries",10);
        var source=rows.copy(); long tick=0;
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i); var stack=row.getCompound("item_stack");
            var caller=new ComputerBlockHost(()->{});
            caller.installFlow(new FlowProgram(Map.of("row",new DataType.RecordType(Map.of("n",NBT))),List.of(new FlowProgram.Write("send","x0","row"),
                new FlowProgram.Read("read","x0","result",ROW_RESULT))).withAssembly(),record("row",record("n",snapshot(row))),limits,true);
            caller.setPaused(false,limits,true);
            for(int n=0;n<10000;n++) {
                worker.tick(++tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(caller,"x0"))); caller.tick(tick,limits,true,Map.of());
                worker.exchange("x0",limits,true,caller,"x0",limits,true);
                assertNotEquals(FlowRuntime.Status.FAILED,worker.flowSnapshot().orElseThrow().state().status(),worker.flowSnapshot().orElseThrow().state().diagnostic().toString());
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            var state=caller.flowSnapshot().orElseThrow().state(); assertEquals(FlowRuntime.Status.COMPLETE,state.status());
            assertEquals(record("c","ok","i",record("item",stack.getString("id"),"count",row.getInt("count"),
                "components",snapshot(stack.getCompound("components")))),state.locals().field("result"));
        }
        assertEquals(source,rows);
    }
    @Test void completeMaterialListSurvivesColdResumeAndALaterInvalidRowNeverPublishesAPartialList() throws Exception {
        var order=nativeOrder(); var original=order.copy();
        var result=convert(order,true);
        assertEquals(new DataValue.TextValue("ok"),result.field("c"));
        var items=(DataValue.ListValue)result.field("i"); assertEquals(2,items.values().size());
        var decoded=com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionMaterials.decode(
            record("items",items),net.minecraft.core.RegistryAccess.EMPTY,
            new NativeNbtCodec.Limits(65536,1048576,StockApplicationDriverDocuments.hardware().archive()),8);
        assertEquals(List.of(7L,3L),new ArrayList<>(decoded.values()));
        var paper=decoded.keySet().iterator().next().stack();
        assertEquals(16,paper.get(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE));
        assertFalse(paper.has(net.minecraft.core.component.DataComponents.RARITY));
        var patch=(DataValue.RecordValue)((DataValue.NbtValue)((DataValue.RecordValue)items.values().getFirst()).field("components")).value();
        assertTrue(patch.fields().containsKey("!minecraft:rarity"));
        assertEquals(original,order);
        order.getCompound("ordered_stacks").getList("entries",10).getCompound(1).putInt("count",0);
        assertEquals(record("c","invalid_material","i",DataValue.NullValue.INSTANCE),convert(order,false));
    }
    private static DataValue.RecordValue convert(CompoundTag order,boolean restore) throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();
        var document=build("order");
        System.out.println("NATIVE_ORDER "+document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
        var coordinator=new ComputerBlockHost(()->{}); var worker=new ComputerBlockHost(()->{}); var caller=new ComputerBlockHost(()->{});
        coordinator.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        worker.installProgram(PreparedProgram.prepare(build("item"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        var preview=new ProgramWorkspace(build("preview")); var body=preview.flow().orElseThrow();
        preview.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),record("order",snapshot(order)),
            body.canvas(),body.inline(),body.execution(),body.compilation())));
        caller.installProgram(PreparedProgram.prepare(preview.snapshot(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        coordinator.setPaused(false,limits,true); worker.setPaused(false,limits,true); caller.setPaused(false,limits,true);
        int exchanged=0; boolean restored=false;
        for(int tick=1;tick<=20000;tick++) {
            var coordinatorSignals=Map.of("x0",SerialExchangeDriverDocuments.signal(caller,"x0"),"x1",SerialExchangeDriverDocuments.signal(worker,"x0"));
            var workerSignals=Map.of("x0",SerialExchangeDriverDocuments.signal(coordinator,"x1"));
            coordinator.tick(tick,limits,true,coordinatorSignals); worker.tick(tick,limits,true,workerSignals); caller.tick(tick,limits,true,Map.of());
            coordinator.exchange("x0",limits,true,caller,"x0",limits,true);
            if(coordinator.exchange("x1",limits,true,worker,"x0",limits,true)==XBusExchange.Status.EXCHANGED)exchanged++;
            for(var host:List.of(coordinator,worker,caller)) {
                var state=host.flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
            }
            if(restore && !restored && exchanged>=2) {
                assertFalse(caller.flowSnapshot().orElseThrow().state().locals().fields().containsKey("result"));
                var saved=coordinator.write(); coordinator=new ComputerBlockHost(()->{}); coordinator.read(saved); coordinator.setPaused(false,limits,true);
                saved=worker.write(); worker=new ComputerBlockHost(()->{}); worker.read(saved); worker.setPaused(false,limits,true);
                saved=caller.write(); caller=new ComputerBlockHost(()->{}); caller.read(saved); caller.setPaused(false,limits,true);
                restored=true;
            }
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
        }
        assertEquals(restore,restored);
        var state=caller.flowSnapshot().orElseThrow().state(); assertEquals(FlowRuntime.Status.COMPLETE,state.status());
        return (DataValue.RecordValue)state.locals().field("result");
    }
}
