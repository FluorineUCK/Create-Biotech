package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.CreateReadComponents;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSamplingProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockSnapshotReader;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Maintainer authoring: the four documents are ordinary player-editable register ISA. */
public final class StockApplicationDriverDocuments {
    public static final String[] ROLES = {"a","b","publish","read"};
    private static final DataType.RecordType TARGET = new DataType.RecordType(Map.of("x",INTEGER,"y",INTEGER,"z",INTEGER));
    private static final DataType.RecordType CALL = new DataType.RecordType(Map.of("k",BOOLEAN,"t",TARGET,"o",TEXT,"a",ANY));
    private static final DataType.RecordType RESULT = new DataType.RecordType(Map.of("r",ANY));
    private static final DataType.ListType ENTRIES = new DataType.ListType(ANY);
    private static final DataValue.NbtValue COMPONENTS = new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of(
        "minecraft:custom_name", new DataValue.NbtValue(8,new DataValue.TextValue("{\"text\":\"Demo paper\"}")))));
    public static ComputerHostLimits hardware() {
        return new BiocomputingConfig(new ModConfigSpec.Builder()).select(
            new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),ResourceLocation.parse("minecraft:cleric"),5),false).limits();
    }
    private static String stem(String role) { return "stock_application_" + role; }
    public static ProgramDocument load(String role) throws Exception {
        try (var stream = StockApplicationDriverDocuments.class.getClassLoader().getResourceAsStream(
                "assets/create_biotech/biocomputing/examples/" + stem(role) + ".bcp")) {
            assertNotNull(stream,"public editable sample is missing: " + role);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()),hardware().archive());
        }
    }
    /** Same editable initial-values operation used by the player's Draft inputs page. */
    public static ProgramDocument at(ProgramDocument source, BlockPos output, BlockPos reader) {
        var draft = new ProgramWorkspace(source);
        var body = draft.flow().orElseThrow();
        var fields = new LinkedHashMap<>(body.initial().fields());
        if (fields.containsKey("output")) fields.put("output",BlockSnapshotReader.position(output));
        if (fields.containsKey("reader")) fields.put("reader",BlockSnapshotReader.position(reader));
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(fields),
            body.canvas(),body.inline(),body.execution(),body.compilation())));
        return draft.snapshot();
    }
    static ProgramDocument build(String role) throws Exception {
        var source = Files.readString(Path.of("examples/biocomputing", stem(role)+".asm")).stripTrailing()+"\n";
        Map<String,DataType> args = switch(role) {
            case "publish" -> Map.of("output",TARGET,"entries",ENTRIES);
            case "recipe_catalog" -> Map.of("output",TARGET,"pages",INTEGER);
            case "recipe_materials" -> Map.of("choices",new DataType.ListType(new DataType.OptionalType(MaterialSelectionDocuments.CHOICE)));
            case "recipe_submit" -> Map.of("target",TARGET,"approved_scope",ANY);
            case "recipe_row" -> Map.of("unit",TEXT,"poll_ticks",INTEGER);
            case "recipe_sample" -> Map.of("reader",TARGET);
            case "read" -> Map.of("output",TARGET,"reader",TARGET);
            default -> Map.of();
        };
        Map<String,DataType> returns = switch(role) {
            case "publish","recipe_catalog","recipe_order","recipe_selection","recipe_dispatch","recipe_materials","recipe_route","recipe_submit" -> Map.of("reply",ANY);
            case "read" -> Map.of("input",ANY,"reply",ANY);
            default -> Map.of();
        };
        var key = new ModuleDefinition.Key("i",1);
        var code = new ArrayList<FlowProgram.Step>();
        code.add(new FlowProgram.Invoke("run",key,new FlowCallBindings(same(args),same(returns))));
        var relay = new ModuleDefinition.Key("r",1);
        if (role.equals("publish")) code.add(new FlowProgram.TaskStart("start_relay",relay,Map.of(),"relay_task"));
        code.add(new FlowProgram.Procedure("implementation",key,args,returns,same(returns),FlowBlockRules.NONE,
            Optional.of(new AssemblySource(role.startsWith("recipe_") ? 13 : 4,source))));
        code.add(new FlowProgram.EndProcedure("implementation_end"));
        if (role.equals("publish")) {
            var relaySource = Files.readString(Path.of("examples/biocomputing","stock_application_relay.asm")).stripTrailing()+"\n";
            code.add(new FlowProgram.Procedure("relay_implementation",relay,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,
                Optional.of(new AssemblySource(relaySource))));
            code.add(new FlowProgram.EndProcedure("relay_implementation_end"));
        }
        var origin = BlockSnapshotReader.position(BlockPos.ZERO);
        var initial = switch(role) {
            case "publish" -> record("output",origin,"entries",new DataValue.ListValue(List.of(
                record("key","demo-paper","title","Demo paper (echo only)","unit","sheet","icon",
                    record("item","minecraft:paper","count",1,"components",COMPONENTS),
                    "data",record("sample",true,"note","Echo input only; not stock or manufacturing.")))));
            case "read" -> record("output",origin,"reader",origin);
            case "recipe_sample" -> record("reader",origin);
            case "recipe_catalog" -> record("output",origin,"pages",1);
            case "recipe_materials" -> record("choices",new DataValue.ListValue(List.of()));
            case "recipe_submit" -> record("target",origin,"approved_scope",DataValue.NullValue.INSTANCE);
            case "recipe_row" -> record("unit","batch","poll_ticks",20);
            default -> record();
        };
        var draft = new ProgramWorkspace();
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(args,code).withAssembly(),initial)));
        if (returns.keySet().equals(Set.of("reply"))) draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        if (role.equals("read")) draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("input","reply")));
        return draft.snapshot();
    }
    private static Map<String,String> same(Map<String,DataType> values) {
        var result = new LinkedHashMap<String,String>(); values.keySet().forEach(name -> result.put(name,name)); return result;
    }

    @Test void publishedReaderRoleCompletesEmptySampleWithoutSendingAnAck() throws Exception {
        var limits = hardware();
        var reader = new ComputerBlockHost(() -> {});
        reader.installProgram(PreparedProgram.prepare(load("read"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        var target = BlockSnapshotReader.position(BlockPos.ZERO);
        var protocol = new ReaderSamplingProtocol(UUID.randomUUID(),UUID.randomUUID(),target,Set.of(CreateReadComponents.STOCK_APPLICATION));
        var common = record("kind","reader_command","version",1,"target",target,"request","test");
        var statusFields = new LinkedHashMap<>(common.fields()); statusFields.putAll(record("operation","status","arguments",record()).fields());
        var sampledFields = new LinkedHashMap<>(common.fields()); sampledFields.putAll(record("operation","sample","arguments",record(
            "device",protocol.device().toString(),"session",protocol.session().toString(),
            "components",new DataValue.ListValue(List.of(new DataValue.TextValue(CreateReadComponents.STOCK_APPLICATION))))).fields());
        var status = protocol.respond(true,new DataValue.RecordValue(statusFields),ids -> {throw new AssertionError("status is not a sample");}).orElseThrow();
        var sampled = protocol.respond(true,new DataValue.RecordValue(sampledFields),ids ->
            record("components",record(CreateReadComponents.STOCK_APPLICATION,record("state",record("inputs",0))),"target",target,"game_time",0)).orElseThrow();
        var peer = new ComputerBlockHost(() -> {});
        peer.installFlow(new FlowProgram(Map.of("status",RESULT,"sample",RESULT),List.of(
            new FlowProgram.Read("status_call","x0","request1",CALL),new FlowProgram.Write("status_reply","x0","status"),
            new FlowProgram.Read("sample_call","x0","request2",CALL),new FlowProgram.Write("sample_reply","x0","sample"),
            new FlowProgram.Read("forbidden_ack","x0","unexpected",CALL))),record("status",record("r",status),"sample",record("r",sampled)),limits,true);
        reader.setPaused(false,limits,true); peer.setPaused(false,limits,true);
        for (int tick=1; tick<=100; tick++) {
            reader.tick(tick,limits,true,Map.of()); peer.tick(tick,limits,true,Map.of());
            reader.exchange("x0",limits,true,peer,"x0",limits,true);
        }
        var result = reader.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,result.status(),result.diagnostic().toString());
        assertSame(DataValue.NullValue.INSTANCE,result.locals().field("input"));
        assertEquals(sampled,result.locals().field("reply"));
        var calls = peer.flowSnapshot().orElseThrow().state().locals();
        assertEquals(new DataValue.TextValue("status"),((DataValue.RecordValue)calls.field("request1")).field("o"));
        assertEquals(new DataValue.TextValue("sample"),((DataValue.RecordValue)calls.field("request2")).field("o"));
        assertFalse(calls.fields().containsKey("unexpected"),"No ACK was sent for empty input");
    }

    @Test void publicDocumentsAreExactlyTheEditableInstalledIsaAndFitAnExistingResident() throws Exception {
        for (var role : ROLES) {
            var hardware = role.equals("a") ? SerialExchangeDriverDocuments.legacyEquippedParser() : hardware();
            var document = build(role);
            var program = document.flow().orElseThrow().program(document.archive());
            System.out.println("STOCK_APPLICATION_DRIVER " + role + " " + program.usage(new ResourceLimits(4096,65536,8)));
            PreparedProgram.prepare(document,hardware);
            if ("1".equals(System.getenv("GENERATE_STOCK_APPLICATION"))) {
                var bytes = ProgramDocumentWire.encode(document,hardware.archive());
                for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing"))
                    Files.writeString(Path.of(directory,stem(role)+".bcp"),Base64.getEncoder().encodeToString(bytes)+"\n");
            } else assertEquals(document,load(role));
        }
    }
}
