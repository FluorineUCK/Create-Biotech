package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.GraphLayouts.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import java.util.*;

/** Versioned document schema; executable facts remain delegated to ProgramArchiveCodec. */
public final class ProgramDocumentCodec {
    private ProgramDocumentCodec() {}
    public static DataValue.RecordValue encode(ProgramDocument document) {
        Objects.requireNonNull(document);
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("version", new DataValue.IntegerValue(document.flow().map(flow -> document.terminal().isPresent() ? 13 : FlowProgram.hasCorrelation(flow.steps()) ? 12 : FlowProgram.hasSerial(flow.steps()) ? 11 : FlowProgram.hasXBusResults(flow.steps()) ? 10 : FlowProgram.hasInputSlots(flow.steps()) ? 9 : FlowProgram.hasRepeats(flow.steps()) ? 8 : FlowProgram.hasLoops(flow.steps()) ? 7 : FlowProgram.hasSignals(flow.steps()) ? 6 : FlowProgram.hasSleep(flow.steps()) ? 5
            : FlowProgram.hasBranches(flow.steps()) ? 4 : 3).orElse(2)));
        if (document.flow().filter(flow -> FlowProgram.hasNativeReceive(flow.steps())).isPresent())
            fields.put("version", new DataValue.IntegerValue(14));
        if (document.flow().filter(flow -> FlowProgram.hasFaultClaim(flow.steps())).isPresent())
            fields.put("version", new DataValue.IntegerValue(15));
        if (document.flow().filter(flow -> FlowProgram.hasCallBindings(flow.steps())).isPresent())
            fields.put("version", new DataValue.IntegerValue(16));
        if (document.flow().filter(flow -> !flow.canvas().equals(FlowBlockLayout.DEFAULT)).isPresent())
            fields.put("version", new DataValue.IntegerValue(17));
        if (document.flow().filter(flow -> !flow.inline().isEmpty()).isPresent())
            fields.put("version", new DataValue.IntegerValue(18));
        boolean controlValues = document.flow().filter(ProgramDocumentCodec::controlValues).isPresent();
        if (controlValues) fields.put("version", new DataValue.IntegerValue(19));
        boolean procedures = document.flow().filter(ProgramDocumentCodec::procedures).isPresent();
        if (procedures) fields.put("version", new DataValue.IntegerValue(20));
        boolean taskTerminal = document.terminal().filter(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition::usesTasks).isPresent();
        if (taskTerminal) {
            if (document.flow().isEmpty()) throw invalid("Task terminal requires a Flow document");
            fields.put("version", new DataValue.IntegerValue(21));
        }
        boolean status = document.flow().filter(ProgramDocumentCodec::xbusStatus).isPresent();
        if (status) fields.put("version", new DataValue.IntegerValue(22));
        boolean assembly = document.flow().filter(flow -> flow.execution() == FlowProgram.Execution.REGISTER_V1).isPresent();
        if (assembly) fields.put("version", new DataValue.IntegerValue(23));
        boolean publicVariables = document.flow().filter(flow -> publicVariables(flow, document.archive())).isPresent();
        if (publicVariables) {
            if (!assembly) throw invalid("Public variables require the assembly target");
            fields.put("version", new DataValue.IntegerValue(24));
        }
        if (document.flow().filter(ProgramDocumentCodec::taskStart).isPresent()) {
            if (!assembly) throw invalid("Task start requires the assembly target");
            fields.put("version", new DataValue.IntegerValue(25));
        }
        if (document.flow().filter(ProgramDocumentCodec::taskJoin).isPresent()) {
            if (!assembly) throw invalid("Task join requires the assembly target");
            fields.put("version", new DataValue.IntegerValue(26));
        }
        if(document.flow().filter(ProgramDocumentCodec::authoredAssembly).isPresent()) {
            if(!assembly)throw invalid("Authored assembly requires the assembly target");
            fields.put("version",new DataValue.IntegerValue(27));
        }
        if (document.flow().filter(body -> requiredValue(body,document.archive())).isPresent()) {
            if (!assembly) throw invalid("Require value requires the assembly target");
            fields.put("version",new DataValue.IntegerValue(28));
        }
        if(assembly && document.flow().filter(body->body.steps().stream().anyMatch(step->step instanceof FlowProgram.SerialSend || step instanceof FlowProgram.SerialStatus)).isPresent())fields.put("version",new DataValue.IntegerValue(29));
        if(assembly && document.flow().filter(body->body.steps().stream().anyMatch(step->step instanceof FlowProgram.SerialReceive)).isPresent())fields.put("version",new DataValue.IntegerValue(30));
        if(document.flow().filter(body->body.program(document.archive()).usesAuthoredV4()).isPresent())fields.put("version",new DataValue.IntegerValue(31));
        boolean specialized=document.flow().filter(body->body.compilation()!=FlowProgram.Compilation.ORIGINAL).isPresent();
        if(specialized)fields.put("version",new DataValue.IntegerValue(document.flow().orElseThrow().compilation()==FlowProgram.Compilation.SOFTWARE_V1?33:32));
        boolean references=document.flow().filter(ProgramDocumentCodec::references).isPresent();
        if(references)fields.put("version",new DataValue.IntegerValue(34));
        boolean sleepValues=document.flow().filter(ProgramDocumentCodec::sleepValues).isPresent();
        if (sleepValues) {
            if (!assembly) throw invalid("Sleep value slots require the assembly target");
            fields.put("version", new DataValue.IntegerValue(35));
        }
        if (document.flow().filter(body -> body.compilation() == FlowProgram.Compilation.COMPACT_V1).isPresent())
            fields.put("version", new DataValue.IntegerValue(36));
        boolean terminalHats = document.flow().filter(body -> !body.canvas().terminalHats().isEmpty()).isPresent();
        if (terminalHats) fields.put("version", new DataValue.IntegerValue(37));
        boolean events = document.flow().filter(ProgramDocumentCodec::events).isPresent();
        if (events) fields.put("version", new DataValue.IntegerValue(38));
        boolean listEdits = document.flow().filter(ProgramDocumentCodec::publicListEdits).isPresent();
        if (listEdits) fields.put("version", new DataValue.IntegerValue(39));
        boolean waitUntil = document.flow().filter(ProgramDocumentCodec::waitUntil).isPresent();
        if (waitUntil) fields.put("version", new DataValue.IntegerValue(41));
        boolean stopScript = document.flow().filter(ProgramDocumentCodec::stopScript).isPresent();
        if (stopScript) fields.put("version", new DataValue.IntegerValue(document.flow().filter(ProgramDocumentCodec::groupStop).isPresent() ? 43 : 42));
        boolean timerReset = document.flow().filter(ProgramDocumentCodec::timerReset).isPresent();
        if (timerReset) fields.put("version", new DataValue.IntegerValue(44));
        boolean ask = document.flow().filter(ProgramDocumentCodec::ask).isPresent();
        if (ask) fields.put("version", new DataValue.IntegerValue(45));
        boolean keys = document.flow().filter(ProgramDocumentCodec::keyHats).isPresent();
        if (keys) fields.put("version",new DataValue.IntegerValue(46));
        boolean heldKeys = document.flow().filter(body -> keyDown(body) || body.program(document.archive()).usesKeyDown()).isPresent();
        if (heldKeys) fields.put("version",new DataValue.IntegerValue(47));
        boolean watch = document.flow().filter(ProgramDocumentCodec::watch).isPresent();
        boolean tryLaunch = document.flow().filter(ProgramDocumentCodec::tryLaunch).isPresent();
        if (tryLaunch) fields.put("version", new DataValue.IntegerValue(48));
        if (watch) fields.put("version", new DataValue.IntegerValue(49));
        boolean waitUnits = document.flow().filter(ProgramDocumentCodec::waitUnits).isPresent();
        if (waitUnits) fields.put("version", new DataValue.IntegerValue(51));
        boolean monitor = document.flow().filter(ProgramDocumentCodec::monitor).isPresent();
        if (monitor) fields.put("version", new DataValue.IntegerValue(52));
        boolean labels = document.flow().filter(flow -> !flow.canvas().procedureLabels().isEmpty()).isPresent();
        if (labels && !assembly) throw invalid("Procedure labels require a Scratch ISA document");
        if (labels) fields.put("version", new DataValue.IntegerValue(53));
        boolean startup = document.flow().filter(ProgramDocumentCodec::startupHats).isPresent();
        if (startup) fields.put("version",new DataValue.IntegerValue(54));
        boolean scratchOperands = document.flow().filter(ProgramDocumentCodec::invokeOperands).isPresent();
        if (scratchOperands) fields.put("version", new DataValue.IntegerValue(55));
        boolean extensions = document.flow().filter(body -> !body.canvas().extensions().isEmpty()).isPresent();
        if (extensions) fields.put("version", new DataValue.IntegerValue(56));
        fields.put("program", ProgramArchiveCodec.encode(document.archive()));
        fields.put("layouts", GraphLayouts.encode(document.positions()));
        fields.put("terminal", document.terminal().<DataValue>map(TerminalCodec::encode).orElse(DataValue.NullValue.INSTANCE));
        document.flow().ifPresent(flow -> {
            var body = new LinkedHashMap<String, DataValue>();
            body.put("control", FlowProgramCodec.control(flow.inputs(), flow.steps())); body.put("initial", flow.initial());
            if (assembly) body.put("execution", new DataValue.TextValue("register_v1"));
            if (labels || waitUnits || monitor || watch || tryLaunch || heldKeys || ask || keys || startup || terminalHats || events || specialized || references || sleepValues || listEdits || waitUntil || stopScript || timerReset || scratchOperands || extensions) body.put("compilation",new DataValue.TextValue(flow.compilation().token()));
            if (assembly || status || taskTerminal || procedures || controlValues || !flow.canvas().equals(FlowBlockLayout.DEFAULT) || !flow.inline().isEmpty()) body.put("canvas", flow.canvas().encode());
            if (assembly || status || taskTerminal || procedures || controlValues || !flow.inline().isEmpty()) {
                var graphs = new LinkedHashMap<String, DataValue>();
                flow.inline().forEach((id, graph) -> graphs.put(id, GraphArchiveCodec.encode(graph)));
                body.put("inline", new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(GraphArchiveCodec.encodingVersion(flow.inline().values())),
                    "graphs", new DataValue.RecordValue(graphs))));
            }
            fields.put("flow", new DataValue.RecordValue(body));
        });
        return new DataValue.RecordValue(fields);
    }
    private static boolean monitor(FlowDocumentBody body) {
        return FlowProgram.hasMonitor(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasMonitor(stack.steps()));
    }
    private static boolean watch(FlowDocumentBody body) {
        return FlowProgram.hasWatch(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasWatch(stack.steps()));
    }
    private static boolean tryLaunch(FlowDocumentBody body) {
        return FlowProgram.hasTryLaunch(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasTryLaunch(stack.steps()));
    }
    private static boolean keyDown(FlowDocumentBody body) {
        return java.util.stream.Stream.concat(body.inline().values().stream(), body.canvas().values().parked().stream().map(ScratchValueLayout.Reporter::graph))
            .flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.KeyDown.class::isInstance);
    }
    private static boolean keyHats(FlowDocumentBody body) {
        return FlowProgram.hasTerminalKeys(body.steps()) || body.canvas().detached().stream().anyMatch(stack->FlowProgram.hasTerminalKeys(stack.steps()));
    }
    private static boolean startupHats(FlowDocumentBody body) {
        return FlowProgram.hasStartupHats(body.steps()) || body.canvas().detached().stream().anyMatch(stack->FlowProgram.hasStartupHats(stack.steps()));
    }
    private static boolean events(FlowDocumentBody body) {
        return FlowProgram.hasEvents(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasEvents(stack.steps()));
    }
    private static boolean publicListEdits(FlowDocumentBody body) {
        return FlowProgram.hasPublicListEdits(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasPublicListEdits(stack.steps()));
    }
    private static boolean references(FlowDocumentBody body){return FlowProgram.hasReferences(body.steps())||body.canvas().detached().stream().anyMatch(stack->FlowProgram.hasReferences(stack.steps()));}
    private static boolean groupStop(FlowDocumentBody body) {
        return FlowProgram.hasGroupStop(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasGroupStop(stack.steps()));
    }
    private static boolean stopScript(FlowDocumentBody body) {
        return FlowProgram.hasStopScript(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasStopScript(stack.steps()));
    }
    private static boolean waitUntil(FlowDocumentBody body) {
        return FlowProgram.hasWaitUntil(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasWaitUntil(stack.steps()));
    }
    private static boolean timerReset(FlowDocumentBody body) {
        return FlowProgram.hasTimerReset(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasTimerReset(stack.steps()));
    }
    private static boolean ask(FlowDocumentBody body) {
        return FlowProgram.hasAsk(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasAsk(stack.steps()));
    }
    private static boolean waitUnits(FlowDocumentBody body) {
        return FlowProgram.hasWaitUnits(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasWaitUnits(stack.steps()));
    }
    private static boolean sleepValues(FlowDocumentBody body) {
        return FlowProgram.hasSleepValues(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasSleepValues(stack.steps()));
    }
    private static boolean requiredValue(FlowDocumentBody body, com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive archive) {
        return FlowProgram.hasRequiredValue(archive) || body.inline().values().stream().anyMatch(FlowProgram::requiredValue)
            || FlowProgram.hasRequiredValue(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasRequiredValue(stack.steps()));
    }
    private static boolean authoredAssembly(FlowDocumentBody body) {
        return FlowProgram.hasAuthoredAssembly(body.steps()) || body.canvas().detached().stream().anyMatch(stack->FlowProgram.hasAuthoredAssembly(stack.steps()));
    }
    private static boolean taskJoin(FlowDocumentBody body) {
        return FlowProgram.hasTaskJoin(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasTaskJoin(stack.steps()));
    }
    private static boolean taskStart(FlowDocumentBody body) {
        return FlowProgram.hasTaskStart(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasTaskStart(stack.steps()));
    }
    private static boolean xbusStatus(FlowDocumentBody body) {
        return FlowProgram.hasXBusStatus(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasXBusStatus(stack.steps()));
    }
    private static boolean controlValues(FlowDocumentBody body) {
        return FlowProgram.hasBoundInputs(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasBoundInputs(stack.steps()));
    }
    private static boolean invokeOperands(FlowDocumentBody body) {
        return FlowProgram.hasInvokeOperands(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasInvokeOperands(stack.steps()));
    }
    private static boolean procedures(FlowDocumentBody body) {
        return FlowProgram.hasProcedures(body.steps()) || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasProcedures(stack.steps()));
    }
    private static boolean publicVariables(FlowDocumentBody body, com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive archive) {
        return FlowProgram.hasShared(body.steps()) || FlowProgram.hasPublicReaders(archive)
            || body.canvas().detached().stream().anyMatch(stack -> FlowProgram.hasShared(stack.steps()))
            || body.inline().values().stream().anyMatch(FlowProgram::publicReader);
    }
    public static ProgramDocument decode(DataValue value) {
        try {
            if (!(value instanceof DataValue.RecordValue record)) throw invalid("Expected document record");
            long version = integer(record.fields().get("version"));
            if (version < 1 || (version > 37 && version != 38 && version != 39 && version != 41 && version != 42 && version != 43 && version != 44 && version != 45 && version != 46 && version != 47 && version != 48 && version != 49 && version != 51 && version != 52 && version != 53 && version != 54 && version != 55 && version != 56))
                throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported document schema version");
            var root = exactRecord(value, version == 1 ? Set.of("version", "program", "layouts") : version == 2
                ? Set.of("version", "program", "layouts", "terminal") : Set.of("version", "program", "layouts", "terminal", "flow"));
            var archive = ProgramArchiveCodec.decode(root.get("program"));
            var layouts = GraphLayouts.decode(root.get("layouts"));
            Optional<FlowDocumentBody> flow = Optional.empty();
            if (version >= 3) {
                if (version < 13 && root.get("terminal") != DataValue.NullValue.INSTANCE)
                    throw invalid("Flow presentation requires document schema 13");
                var body = exactRecord(root.get("flow"), version >= 32 ? Set.of("control","initial","canvas","inline","execution","compilation")
                    : version >= 23 ? Set.of("control", "initial", "canvas", "inline", "execution")
                    : version >= 18 ? Set.of("control", "initial", "canvas", "inline") : version >= 17 ? Set.of("control", "initial", "canvas") : Set.of("control", "initial"));
                if (version >= 23 && !string(body.get("execution")).equals("register_v1")) throw invalid("Unknown assembly target");
                var program = FlowProgramCodec.readControl(body.get("control"), archive);
                if (version < 20 && FlowProgram.hasProcedures(program.steps())) throw invalid("Flow procedures require document schema 20");
                if (version < 16 && FlowProgram.hasCallBindings(program.steps())) throw invalid("Explicit call bindings require document schema 16");
                if (version < 15 && FlowProgram.hasFaultClaim(program.steps())) throw invalid("Receive fault claim requires document schema 15");
                if (version < 14 && FlowProgram.hasNativeReceive(program.steps())) throw invalid("Native NBT receive requires document schema 14");
                if (version < 12 && FlowProgram.hasCorrelation(program.steps())) throw invalid("Correlation requires document schema 12");
                if (version < 11 && FlowProgram.hasSerial(program.steps())) throw invalid("Serial operations require document schema 11");
                if (version < 10 && FlowProgram.hasXBusResults(program.steps())) throw invalid("XBus results require document schema 10");
                if (version < 9 && FlowProgram.hasInputSlots(program.steps())) throw invalid("Input slots and Until require document schema 9");
                if (version < 8 && FlowProgram.hasRepeats(program.steps())) throw invalid("Counted loops require document schema 8");
                if (version < 7 && FlowProgram.hasLoops(program.steps())) throw invalid("Loops require document schema 7");
                if (version == 3 && FlowProgram.hasBranches(program.steps())) throw invalid("Branches require document schema 4");
                if (version < 6 && FlowProgram.hasSignals(program.steps())) throw invalid("Signals require document schema 6");
                if (version < 5 && FlowProgram.hasSleep(program.steps())) throw invalid("Sleep requires document schema 5");
                if (!(body.get("initial") instanceof DataValue.RecordValue initial)) throw invalid("Expected named flow initial values");
                var inline = new LinkedHashMap<String, com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph>();
                if (version >= 18) {
                    var data = exactRecord(body.get("inline"), Set.of("version", "graphs"));
                    if (!(data.get("graphs") instanceof DataValue.RecordValue graphs)) throw invalid("Expected inline graphs");
                    long graphVersion = integer(data.get("version"));
                    graphs.fields().forEach((id, graph) -> inline.put(id, GraphArchiveCodec.decode(graph, graphVersion)));
                }
                flow = Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial,
                    version >= 17 ? FlowBlockLayout.decode(body.get("canvas"), archive) : FlowBlockLayout.DEFAULT, inline,
                    version >= 23 ? FlowProgram.Execution.REGISTER_V1 : FlowProgram.Execution.LEGACY_GRAPH,
                    version>=34&&string(body.get("compilation")).equals("original")?FlowProgram.Compilation.ORIGINAL:version >= 32 ? FlowProgram.Compilation.parse(string(body.get("compilation"))) : FlowProgram.Compilation.ORIGINAL));
                boolean labels = !flow.orElseThrow().canvas().procedureLabels().isEmpty();
                if (version < 54 && (version == 53) != labels) throw invalid("Procedure labels require document schema 53");
                if (version < 53 && (version==52)!=monitor(flow.orElseThrow())) throw invalid("Monitor requires document schema 52");
                if (version < 52 && (version == 51) != waitUnits(flow.orElseThrow())) throw invalid("Wait units require document schema 51");
                if (version < 51 && (version==49)!=watch(flow.orElseThrow())) throw invalid("Watch requires document schema 49");
            if (version < 49 && (version==48)!=tryLaunch(flow.orElseThrow())) throw invalid("Try-launch requires document schema 48");
                if (version < 48 && (version==47)!=(keyDown(flow.orElseThrow()) || flow.orElseThrow().program(archive).usesKeyDown())) throw invalid("Terminal key reporter requires document schema 47");
                if (version < 46 && (version == 45) != ask(flow.orElseThrow())) throw invalid("Terminal ask requires document schema 45");
                if (version < 47 && (version==46)!=keyHats(flow.orElseThrow())) throw invalid("Terminal keys require document schema 46");
                if (version < 55 && (version == 54) != startupHats(flow.orElseThrow())) throw invalid("Startup hats require document schema 54");
                if (version == 37 && flow.orElseThrow().canvas().terminalHats().isEmpty() || version < 37 && !flow.orElseThrow().canvas().terminalHats().isEmpty()) throw invalid("Terminal hats require document schema 37");
                if (version < 39 && (version == 38) != events(flow.orElseThrow())) throw invalid("Broadcast requires document schema 38");
                if (version < 45 && (version == 44) != timerReset(flow.orElseThrow())) throw invalid("Timer reset requires document schema 44");
                if (version < 44 && (version == 43) != groupStop(flow.orElseThrow())) throw invalid("Group stop requires document schema 43");
                if (version < 43 && (version == 42) != stopScript(flow.orElseThrow())) throw invalid("Stop script requires document schema 42");
                if (version < 42 && (version == 41) != waitUntil(flow.orElseThrow())) throw invalid("Wait until requires document schema 41");
                if (version < 40 && (version == 39) != publicListEdits(flow.orElseThrow())) throw invalid("Public list edits require document schema 39");
                if (version < 35 && (version == 34) != references(flow.orElseThrow())) throw invalid("Local references require document schema 34");
                boolean operands = invokeOperands(flow.orElseThrow());
                boolean sleep = sleepValues(flow.orElseThrow());
                if (version < 35 && (operands || sleep)) throw invalid("Inline operands and Sleep values require document schema 35 or newer");
                if (version == 35 && operands == sleep) throw invalid("Historical schema 35 must identify one source format");
                boolean extensions = !flow.orElseThrow().canvas().extensions().isEmpty();
                boolean compact = flow.orElseThrow().compilation() == FlowProgram.Compilation.COMPACT_V1;
                // The old source writer placed numbered waits and startup hats after extensions.
                boolean historicalStartup = version == 54 && waitUnits(flow.orElseThrow()) && startupHats(flow.orElseThrow());
                boolean historicalTimedExtension = version == 51 && waitUnits(flow.orElseThrow());
                boolean historicalConditionalWait = version == 41 && waitUntil(flow.orElseThrow());
                boolean historicalStopScript = (version == 42 || version == 43) && stopScript(flow.orElseThrow());
                boolean historicalTimerReset = version == 44 && timerReset(flow.orElseThrow());
                if (extensions && !historicalConditionalWait && !historicalStopScript && !historicalTimerReset && version != 36 && version != 56 && !(version == 49 && watch(flow.orElseThrow())) && !(version == 48 && tryLaunch(flow.orElseThrow())) && !(labels && (version == 53 || version == 54)) && !historicalStartup && !historicalTimedExtension)
                    throw invalid("Scratch extensions require their declared document schema");
                if (version == 36) {
                    if (extensions == compact || !extensions && operands || extensions && sleep)
                        throw invalid("Historical schema 36 must identify one source format");
                } else if (version < 55 && version != 37 && version != 38 && version != 39 && version != 41 && version != 42 && version != 43 && version != 44 && version != 45 && version != 46 && version != 47 && version != 48 && version != 49 && version != 51 && version != 52 && version != 53 && version != 54 && compact) throw invalid("Compact compilation requires document schema 36");
                if (version<34 && (version==33)!=(flow.orElseThrow().compilation()==FlowProgram.Compilation.SOFTWARE_V1)) throw invalid("Software compilation requires document schema 33");
                boolean serialTx=flow.orElseThrow().execution()==FlowProgram.Execution.REGISTER_V1 && flow.orElseThrow().steps().stream().anyMatch(step->step instanceof FlowProgram.SerialSend || step instanceof FlowProgram.SerialStatus);
                boolean serialRx=flow.orElseThrow().execution()==FlowProgram.Execution.REGISTER_V1 && flow.orElseThrow().steps().stream().anyMatch(step->step instanceof FlowProgram.SerialReceive);
                if (version<31 && (version==30)!=serialRx)throw invalid("Serial RX requires document schema 30");
                if (version<30 && (version==29)!=serialTx)throw invalid("Serial TX requires document schema 29");
                if (version < 29 && (version == 28) != requiredValue(flow.orElseThrow(),archive)) throw invalid("Require value requires document schema 28");
                if(version<=31 && (version==31)!=flow.orElseThrow().program(archive).usesAuthoredV4())throw invalid("Authored v4 requires document schema 31");
                if (version < 28 && (version == 27) != authoredAssembly(flow.orElseThrow())) throw invalid("Authored assembly requires document schema 27");
                if (version < 27 && (version == 26) != taskJoin(flow.orElseThrow())) throw invalid("Task join requires document schema 26");
                if (version < 26 && (version == 25) != taskStart(flow.orElseThrow())) throw invalid("Task start requires document schema 25");
                if (version < 24 && publicVariables(flow.orElseThrow(), archive)) throw invalid("Public variables require document schema 24");
                if (version < 22 && xbusStatus(flow.orElseThrow())) throw invalid("XBus status requires document schema 22");
                if (version < 19 && controlValues(flow.orElseThrow())) throw invalid("Bound control inputs require document schema 19");
                if (version < 20 && procedures(flow.orElseThrow())) throw invalid("Flow procedures require document schema 20");
            }
            var terminal = version == 1 || root.get("terminal") == DataValue.NullValue.INSTANCE
                ? Optional.<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition>empty() : Optional.of(TerminalCodec.decode(root.get("terminal")));
            if (version < 21 && terminal.filter(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition::usesTasks).isPresent())
                throw invalid("Task terminal requires document schema 21");
            return new ProgramDocument(archive, layouts, terminal, flow);
        } catch (ArchiveException error) { throw error; }
        catch (IllegalArgumentException | NullPointerException error) { throw invalid("Invalid document: " + error.getMessage()); }
    }
}
