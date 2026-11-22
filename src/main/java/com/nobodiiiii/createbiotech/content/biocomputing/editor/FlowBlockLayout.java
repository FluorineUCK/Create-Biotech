package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.GraphLayouts.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import java.util.*;

/** Ordinary parked stacks are inert. Separate hats contribute definitions; only explicitly marked startup hats schedule initial tasks. */
public record FlowBlockLayout(ProgramDocument.Position main, List<Stack> detached, ScratchValueLayout values, Map<String, Long> extensions,
                              Map<String, String> terminalHats, Map<String, List<ScratchProcedureLabel>> procedureLabels) {
    public FlowBlockLayout(ProgramDocument.Position main, List<Stack> detached, ScratchValueLayout values, Map<String,Long> extensions, Map<String,List<ScratchProcedureLabel>> procedureLabels) {
        this(main, detached, values, extensions, Map.of(), procedureLabels);
    }
    public FlowBlockLayout(ProgramDocument.Position main, List<Stack> detached, ScratchValueLayout values, Map<String, Long> extensions) {
        this(main, detached, values, extensions, Map.of());
    }
    public FlowBlockLayout(ProgramDocument.Position main, List<Stack> detached, ScratchValueLayout values) {
        this(main, detached, values, Map.of());
    }
    public FlowBlockLayout(ProgramDocument.Position main, List<Stack> detached) {
        this(main, detached, ScratchValueLayout.EMPTY);
    }
    FlowBlockLayout arranged(ProgramDocument.Position main, List<Stack> detached) {
        var remaining = new LinkedHashMap<String, String>();
        var labels = new LinkedHashMap<String, List<ScratchProcedureLabel>>();
        for (var stack : detached) {
            String source = stack.steps().getFirst().sourceId();
            if (terminalHats.containsKey(source)) remaining.put(source, terminalHats.get(source));
            if (stack.steps().getFirst() instanceof FlowProgram.Procedure && procedureLabels.containsKey(source))
                labels.put(source, procedureLabels.get(source));
        }
        return new FlowBlockLayout(main, detached, values, extensions, remaining, labels);
    }
    FlowBlockLayout withValues(ScratchValueLayout values) { return new FlowBlockLayout(main, detached, values, extensions, terminalHats, procedureLabels); }
    FlowBlockLayout withExtensions(Map<String, Long> enabled) { return new FlowBlockLayout(main, detached, values, enabled, terminalHats, procedureLabels); }
    FlowBlockLayout withTerminalHat(String source, String label) {
        var hats = new LinkedHashMap<>(terminalHats);
        hats.put(source, label.strip());
        return new FlowBlockLayout(main, detached, values, extensions, hats, procedureLabels);
    }
    FlowBlockLayout withProcedureLabels(String source, List<ScratchProcedureLabel> labels) {
        var next = new LinkedHashMap<>(procedureLabels);
        if (labels.isEmpty()) next.remove(source); else next.put(source, labels);
        return new FlowBlockLayout(main, detached, values, extensions, terminalHats, next);
    }

    public static final FlowBlockLayout DEFAULT = new FlowBlockLayout(new ProgramDocument.Position(16, 16), List.of());
    public record Stack(ProgramDocument.Position position, List<FlowProgram.Step> steps) {
        public Stack {
            Objects.requireNonNull(position); steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("Empty detached block stack");
            new FlowProgram(Map.of(), steps);
            var structure = FlowBranches.structure(steps);
            if(steps.getFirst() instanceof FlowProgram.Procedure p && p.assembly().isPresent() && steps.size()!=2)
                throw new IllegalArgumentException("Assembly procedure cannot contain visual blocks; keep or remove the existing body explicitly");
            if (steps.stream().anyMatch(FlowProgram.Procedure.class::isInstance)
                && (!(steps.getFirst() instanceof FlowProgram.Procedure) || structure.end(0) != steps.size() - 1))
                throw new IllegalArgumentException("A procedure definition must occupy its own complete stack");
            for (int i = 0; i < steps.size(); i++) if (steps.get(i) instanceof FlowProgram.Watch watch
                    && (!(steps.getFirst() instanceof FlowProgram.Procedure) || i != 1
                        || watch.handler().isPresent() && steps.size() != 3))
                throw new IllegalArgumentException("An edge hat must own its attached script or name a separate handler, not both");
        }
    }
    public FlowBlockLayout { Objects.requireNonNull(main); detached = List.copyOf(detached); Objects.requireNonNull(values);
        terminalHats = DataValue.immutableMap(terminalHats);
        extensions = DataValue.immutableMap(extensions);
        var labels = new LinkedHashMap<String, List<ScratchProcedureLabel>>();
        if (!procedureLabels.isEmpty()) {
            var definitions = new HashMap<String, FlowProgram.Procedure>();
            for (var stack : detached)
                if (stack.steps().getFirst() instanceof FlowProgram.Procedure p && !p.startup() && p.event().isEmpty() && p.terminalKey().isEmpty()
                        && !terminalHats.containsKey(p.sourceId()) && !com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(p.key())
                        && (stack.steps().size() < 2 || !(stack.steps().get(1) instanceof FlowProgram.Watch)))
                    definitions.put(p.sourceId(), p);
            for (var entry : procedureLabels.entrySet()) {
                var definition = definitions.get(entry.getKey());
                var parts = List.copyOf(entry.getValue());
                if (definition == null || parts.isEmpty() || parts.size() > ScratchProcedureLabel.MAX_PARTS
                        || parts.stream().anyMatch(part -> part.before() > definition.inputs().size()))
                    throw new IllegalArgumentException("Labels must belong to the input layout of a custom definition");
                labels.put(entry.getKey(), parts);
            }
        }
        procedureLabels = DataValue.immutableMap(labels);
        extensions.forEach((name, revision) -> {
            var id = net.minecraft.resources.ResourceLocation.tryParse(name);
            if (id == null || !id.toString().equals(name) || revision < 1)
                throw new IllegalArgumentException("Invalid Scratch extension revision: " + name);
        });
        if (!terminalHats.isEmpty()) {
            var roots = new HashSet<String>();
            for (var stack : detached) if (stack.steps().getFirst() instanceof FlowProgram.Procedure procedure
                    && procedure.inputs().isEmpty() && procedure.outputs().isEmpty() && procedure.assembly().isEmpty()
                    && !procedure.startup() && procedure.event().isEmpty() && procedure.terminalKey().isEmpty())
                roots.add(procedure.sourceId());
            if (!roots.containsAll(terminalHats.keySet()))
                throw new IllegalArgumentException("Terminal event requires a visual procedure root without parameters or results");
            if (terminalHats.values().stream().anyMatch(label -> label.isBlank() || label.length() > 64 || label.codePoints().anyMatch(Character::isISOControl))
                    || new HashSet<>(terminalHats.values()).size() != terminalHats.size())
                throw new IllegalArgumentException("Terminal event names must be unique, nonempty and at most 64 characters");
        }
    }
    void validate(List<FlowProgram.Step> active) {
        var ids = new HashSet<String>();
        for (var step : active) ids.add(step.sourceId());
        for (var stack : detached) for (var step : stack.steps())
            if (!ids.add(step.sourceId())) throw new IllegalArgumentException("Duplicate canvas block: " + step.sourceId());
    }
    DataValue encode() {
        var stacks = new ArrayList<DataValue>();
        detached.forEach(stack -> stacks.add(new DataValue.RecordValue(Map.of("position", position(stack.position()),
            "control", FlowProgramCodec.control(Map.of(), stack.steps())))));
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("main", position(main)); fields.put("detached", new DataValue.ListValue(stacks));
        if (!values.equals(ScratchValueLayout.EMPTY)) fields.put("values", values.encode());
        if (!extensions.isEmpty()) {
            var enabled = new LinkedHashMap<String, DataValue>();
            extensions.forEach((id, version) -> enabled.put(id, new DataValue.IntegerValue(version)));
            fields.put("extensions", new DataValue.RecordValue(enabled));
        }
        if (!terminalHats.isEmpty()) {
            var names = new LinkedHashMap<String, DataValue>();
            terminalHats.forEach((id, label) -> names.put(id, new DataValue.TextValue(label)));
            fields.put("terminal_hats", new DataValue.RecordValue(names));
        }
        if (!procedureLabels.isEmpty()) {
            var labels = new LinkedHashMap<String, DataValue>();
            procedureLabels.forEach((id, parts) -> labels.put(id, ScratchProcedureLabel.encode(parts)));
            fields.put("procedure_labels", new DataValue.RecordValue(labels));
        }
        return new DataValue.RecordValue(fields);
    }
    static FlowBlockLayout decode(DataValue value, ProgramArchive archive) {
        boolean hasValues = value instanceof DataValue.RecordValue record && record.fields().containsKey("values");
        var keys = new HashSet<>(Set.of("main", "detached"));
        if (hasValues) keys.add("values");
        if (value instanceof DataValue.RecordValue record && record.fields().containsKey("extensions")) keys.add("extensions");
        if (value instanceof DataValue.RecordValue record && record.fields().containsKey("terminal_hats")) keys.add("terminal_hats");
        if (value instanceof DataValue.RecordValue record && record.fields().containsKey("procedure_labels")) keys.add("procedure_labels");
        var fields = exactRecord(value, keys);
        var stacks = new ArrayList<Stack>();
        for (var item : list(fields.get("detached"))) {
            var stack = exactRecord(item, Set.of("position", "control"));
            var program = FlowProgramCodec.readControl(stack.get("control"), archive);
            if (!program.inputs().isEmpty()) throw invalid("Detached blocks do not declare independent inputs");
            stacks.add(new Stack(readPosition(stack.get("position")), program.steps()));
        }
        var enabled = new LinkedHashMap<String,Long>();
        if (fields.containsKey("extensions")) {
            if (!(fields.get("extensions") instanceof DataValue.RecordValue versions)) throw invalid("Invalid Scratch extension revisions");
            versions.fields().forEach((id, revision) -> enabled.put(id, integer(revision)));
        }
        var hats = new LinkedHashMap<String, String>();
        if (fields.containsKey("terminal_hats")) {
            if (!(fields.get("terminal_hats") instanceof DataValue.RecordValue names)) throw invalid("Invalid terminal event names");
            names.fields().forEach((id, label) -> hats.put(id, string(label)));
        }
        var labels = new LinkedHashMap<String, List<ScratchProcedureLabel>>();
        if (fields.containsKey("procedure_labels")) {
            if (!(fields.get("procedure_labels") instanceof DataValue.RecordValue layouts) || layouts.fields().isEmpty())
                throw invalid("Invalid procedure label layouts");
            layouts.fields().forEach((id, parts) -> labels.put(id, ScratchProcedureLabel.decode(parts)));
        }
        return new FlowBlockLayout(readPosition(fields.get("main")), stacks, hasValues ? ScratchValueLayout.decode(fields.get("values")) : ScratchValueLayout.EMPTY, enabled, hats, labels);
    }
    static DataValue position(ProgramDocument.Position p) {
        return new DataValue.RecordValue(Map.of("x", new DataValue.DecimalValue(p.x()), "y", new DataValue.DecimalValue(p.y())));
    }
    static ProgramDocument.Position readPosition(DataValue value) {
        var fields = exactRecord(value, Set.of("x", "y"));
        return new ProgramDocument.Position(decimal(fields.get("x")), decimal(fields.get("y")));
    }
}
