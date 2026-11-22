package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBranches;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import java.util.*;
import java.util.function.Function;

/** Local bounded undo history. It never holds a host or changes an installed continuation. */
public final class FlowDraft {
    private static final int HISTORY = 64; // Editor history, not hardware or execution capacity.
    private FlowDocumentBody body;
    private Function<FlowDocumentBody, ProgramDocument.Position> displacedTailPosition;

    void placeDisplacedTailWith(Function<FlowDocumentBody, ProgramDocument.Position> position) {
        displacedTailPosition = Objects.requireNonNull(position);
    }
    // Layout is resolved before recording the edit so the cap, tail and position undo together.
    private FlowDocumentBody positionDisplacedTail(FlowDocumentBody next) {
        if (displacedTailPosition == null) return next;
        var layout = next.canvas();
        var tail = layout.detached().getLast();
        var position = displacedTailPosition.apply(next);
        if (position.equals(tail.position())) return next;
        var detached = new ArrayList<>(layout.detached());
        detached.set(detached.size() - 1, new FlowBlockLayout.Stack(position, tail.steps()));
        return next.edited(next.inputs(), next.steps(), next.initial(), layout.arranged(layout.main(), detached), next.inline());
    }
    private record Revision(FlowDocumentBody body, Runnable restore, Runnable reapply) {}
    private final Deque<Revision> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    public FlowDraft(FlowDocumentBody body) { this.body = Objects.requireNonNull(body); }
    public FlowDocumentBody snapshot() { return body; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void undo() {
        if (!canUndo()) return;
        var revision = undo.getLast();
        revision.restore().run();
        undo.removeLast();
        redo.addLast(new Revision(body, revision.restore(), revision.reapply()));
        body = revision.body();
    }
    public void redo() {
        if (!canRedo()) return;
        var revision = redo.getLast();
        revision.reapply().run();
        redo.removeLast();
        undo.addLast(new Revision(body, revision.restore(), revision.reapply()));
        body = revision.body();
    }
    public String nextId() {
        var ids = ids();
        for (int index = 1; ; index++) { String id = "block_" + index; if (!ids.contains(id)) return id; }
    }
    public void append(FlowProgram.Step step) { insert(null, List.of(step)); }
    public void insertAfter(String selected, FlowProgram.Step step) {
        insert(selected, List.of(step));
    }
    public String insertBranch(String selected, String condition) {
        return insertBranch(selected,new FlowInput.Local(condition));
    }
    public String insertBranch(String selected, FlowInput condition) {
        return insertBranch(selected, condition, true);
    }
    String insertBranch(String selected, FlowInput condition, boolean alternative) {
        var ids = ids();
        String id; int suffix = 1;
        do { id = "branch_" + suffix++; } while (ids.contains(id) || ids.contains(id + "_else") || ids.contains(id + "_end"));
        insert(selected, branch(id, condition, alternative)); return id;
    }
    static List<FlowProgram.Step> branch(String id, FlowInput condition, boolean alternative) {
        var header = new FlowProgram.If(id, condition);
        var closing = new FlowProgram.EndIf(id + "_end");
        return alternative ? List.of(header, new FlowProgram.Otherwise(id + "_else"), closing) : List.of(header, closing);
    }
    public String insertForever(String selected) {
        var ids = ids();
        String id; int suffix = 1;
        do { id = "loop_" + suffix++; } while (ids.contains(id) || ids.contains(id + "_end"));
        insert(selected, List.of(new FlowProgram.Forever(id), new FlowProgram.EndForever(id + "_end"))); return id;
    }
    public String insertRepeat(String selected, String countLocal) {
        return insertRepeat(selected, new FlowInput.Local(countLocal));
    }
    public String insertRepeat(String selected, FlowInput count) {
        var ids = ids();
        String id; int suffix = 1;
        do { id = "repeat_" + suffix++; } while (ids.contains(id) || ids.contains(id + "_end"));
        insert(selected, List.of(new FlowProgram.Repeat(id, count), new FlowProgram.EndRepeat(id + "_end"))); return id;
    }
    public String insertUntil(String selected, String condition) {
        return insertUntil(selected, new FlowInput.Local(condition));
    }
    public String insertUntil(String selected, FlowInput condition) {
        var ids = ids();
        String id; int suffix = 1;
        do { id = "until_" + suffix++; } while (ids.contains(id) || ids.contains(id + "_end"));
        insert(selected, List.of(new FlowProgram.Until(id, condition), new FlowProgram.EndUntil(id + "_end"))); return id;
    }
    private void insert(String selected, List<FlowProgram.Step> inserted) {
        var at = selected == null ? new Location(0, body.steps().size() - 1) : locate(selected);
        var steps = new ArrayList<>(blocks(at.stack()));
        int index = at.index() + 1;
        if (cap(inserted.getLast())) {
            var stack = at.stack() == 0 ? null : body.canvas().detached().get(at.stack() - 1);
            place(inserted, new Socket(stack == null ? null : steps.getFirst().sourceId(), index),
                stack == null ? body.canvas().main() : stack.position());
        } else {
            requireOpenSocket(steps, index);
            steps.addAll(index, inserted); steps(at.stack(), steps);
        }
    }
    public String insertAtomic(String selected, String result) {
        var ids = ids(); String id; int suffix = 1;
        do { id = "atomic_" + suffix++; } while (ids.contains(id) || ids.contains(id + "_end"));
        insert(selected, List.of(new FlowProgram.Atomic(id, result), new FlowProgram.EndAtomic(id + "_end"))); return id;
    }
    public void replace(String id, FlowProgram.Step step) {
        replace(id, step, null);
    }
    void replace(String id, FlowProgram.Step step, ProgramDocument.Position atBlock) {
        if (!id.equals(step.sourceId())) throw new IllegalArgumentException("Editing a block must keep its source identity");
        var at = locate(id); var steps = new ArrayList<>(blocks(at.stack()));
        if (cap(step) && !cap(steps.get(at.index()))) {
            steps.remove(at.index()); var tail = splice(steps, at.index(), List.of(step));
            if (!tail.isEmpty()) {
                var detached = new ArrayList<>(body.canvas().detached());
                var origin = at.stack() == 0 ? body.canvas().main() : detached.get(at.stack() - 1).position();
                if (at.stack() > 0) detached.set(at.stack() - 1, new FlowBlockLayout.Stack(origin, steps));
                detached.add(new FlowBlockLayout.Stack(parkedTail(atBlock == null ? origin : atBlock), tail));
                publish(positionDisplacedTail(body.edited(body.inputs(), at.stack() == 0 ? steps : body.steps(), body.initial(),
                    body.canvas().arranged(body.canvas().main(), detached), body.inline())));
                return;
            }
        } else steps.set(at.index(), step);
        steps(at.stack(), steps);
    }
    public void remove(String id) {
        var at = locate(id); var steps = new ArrayList<>(blocks(at.stack()));
        if (steps.get(at.index()) instanceof FlowProgram.Watch) at = new Location(at.stack(), 0);
        int through = groupEnd(steps, at.index()); steps.subList(at.index(), through + 1).clear(); steps(at.stack(), steps);
    }
    void removeAndReleaseUnusedExtensions(String id, ProgramWorkspace workspace) {
        var candidate = new FlowDraft(body);
        candidate.remove(id);
        acceptDeletion(candidate, workspace);
    }
    void acceptDeletion(FlowDraft candidate, ProgramWorkspace workspace) {
        var cleaned = ScratchProcedureReferences.releaseNewlyUnusedTemplates(body, candidate.snapshot(), workspace);
        publish(ScratchProcedureReferences.releaseNewlyDormantReporters(body, cleaned, workspace));
    }
    void acceptReporterEdit(FlowDraft candidate, ProgramWorkspace workspace) {
        publish(ScratchProcedureReferences.releaseNewlyDormantReporters(body, candidate.snapshot(), workspace));
    }
    public void editInline(String id, ComputationGraph graph, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings bindings) {
        editInline(id, graph, bindings, body.canvas().values());
    }
    void editInline(String id, ComputationGraph graph, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings bindings,
                    ScratchValueLayout values) {
        var step=find(id).orElseThrow();
        if (FlowInlineValues.graph(body,step) == null) throw new IllegalArgumentException("Not an editable inline value");
        var replacement=step instanceof FlowProgram.Evaluate call ? new FlowProgram.Evaluate(id,call.expression(),bindings)
            : step instanceof FlowProgram.Invoke call ? FlowInlineValues.operands(call,id,bindings.arguments())
            : FlowInlineValues.withInput(step instanceof FlowProgram.Sleep wait && wait.unit().isEmpty()
                ? new FlowProgram.Sleep(id, wait.ticks(), Optional.of(FlowProgram.WaitUnit.TICKS)) : step,
                new FlowInput.Expression(id, "value", bindings.arguments()));
        editInline(replacement,graph,values);
    }
    void editInline(FlowProgram.Step replacement, ComputationGraph graph, ScratchValueLayout values) {
        String id = replacement.sourceId();
        var at = locate(id); var steps = new ArrayList<>(blocks(at.stack()));
        steps.set(at.index(), replacement);
        var detached = new ArrayList<>(body.canvas().detached());
        if (at.stack() > 0) detached.set(at.stack() - 1, new FlowBlockLayout.Stack(detached.get(at.stack() - 1).position(), steps));
        var graphs = new LinkedHashMap<>(body.inline()); graphs.put(id, graph);
        publish(body.edited(body.inputs(), at.stack() == 0 ? steps : body.steps(), body.initial(), body.canvas().arranged(body.canvas().main(), detached).withValues(values), graphs));
    }
    public void move(String id, int to) {
        var at = locate(id); var steps = new ArrayList<>(blocks(at.stack()));
        if (to < 0 || to >= steps.size()) throw new IllegalArgumentException("Invalid block position");
        int from = at.index(), through = groupEnd(steps, from);
        if (to >= from && to <= through) return;
        var group = new ArrayList<>(steps.subList(from, through + 1));
        steps.subList(from, through + 1).clear();
        var tail = splice(steps, to > through ? to - group.size() + 1 : to, group);
        if (tail.isEmpty()) {
            steps(at.stack(), steps);
        } else {
            var detached = new ArrayList<>(body.canvas().detached());
            var position = at.stack() == 0 ? body.canvas().main() : detached.get(at.stack() - 1).position();
            if (at.stack() > 0) detached.set(at.stack() - 1, new FlowBlockLayout.Stack(position, steps));
            detached.add(new FlowBlockLayout.Stack(parkedTail(position), tail));
            publish(body.edited(body.inputs(), at.stack() == 0 ? steps : body.steps(), body.initial(),
                body.canvas().arranged(body.canvas().main(), detached), body.inline()));
        }
    }
    private static int groupEnd(List<FlowProgram.Step> steps, int index) {
        var step = steps.get(index);
        if (delimiter(step))
            throw new IllegalArgumentException("Select the control header to move or remove its entire group");
        return com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockDeclarations.of(step).header() ? FlowBranches.structure(steps).end(index) : index;
    }
    void editPublicVariable(ProgramWorkspace workspace, String selected, String name, DataValue initial) {
        updateVariableDocument(workspace, before -> ScratchPublicVariables.edit(before, selected, name, initial));
    }
    void removePublicVariable(ProgramWorkspace workspace, String name) {
        updateVariableDocument(workspace, before -> ScratchPublicVariables.remove(before, name));
    }
    void editPrivateVariable(ProgramWorkspace workspace, String selected, String name, DataValue initial) {
        updateVariableDocument(workspace, before -> ScratchPrivateVariables.edit(before, selected, name, initial));
    }
    void removePrivateVariable(ProgramWorkspace workspace, String name) {
        updateVariableDocument(workspace, before -> ScratchPrivateVariables.remove(before, name));
    }
    private void updateVariableDocument(ProgramWorkspace workspace, java.util.function.UnaryOperator<ProgramDocument> edit) {
        var current = workspace.snapshot();
        var before = new ProgramDocument(current.archive(), current.positions(), current.terminal(), Optional.of(body));
        var after = edit.apply(before);
        if (before.equals(after)) return;
        workspace.restoreVariableEdit(after);
        remember(after.flow().orElseThrow(), () -> workspace.restoreVariableEdit(before),
            () -> workspace.restoreVariableEdit(after));
    }
    void declareList(String name, DataType element) {
        if (name.isBlank()) throw new IllegalArgumentException("List name must not be blank");
        if (body.inputs().containsKey(name)) throw new IllegalArgumentException("A private variable already has this name");
        var types = new LinkedHashMap<>(body.inputs());
        var values = new LinkedHashMap<>(body.initial().fields());
        types.put(name, new DataType.ListType(element));
        values.put(name, new DataValue.ListValue(List.of()));
        publish(body.edited(types, body.steps(), new DataValue.RecordValue(values), body.canvas(), body.inline()));
    }
    void declarePublicList(ProgramWorkspace workspace, String name, DataType element) {
        updateVariableDocument(workspace, before -> ScratchPublicVariables.declareList(before, name, element));
    }
    void editValues(ScratchValueLayout values) {
        publish(body.edited(body.inputs(), body.steps(), body.initial(), body.canvas().withValues(values), body.inline()));
    }
    public void initial(DataValue.RecordValue values) {
        var types = new LinkedHashMap<String, DataType>();
        values.fields().forEach((name, value) -> {
            var old = body.inputs().get(name);
            types.put(name, old != null && old.accepts(value) ? old : DataType.typeOf(value));
        });
        publish(body.edited(types, body.steps(), values, body.canvas(), body.inline()));
    }
    /** Replace one declared initial without renaming it or widening its type. Values are immutable snapshots. */
    public void replaceInitial(String name, DataValue value) {
        var type = body.inputs().get(name);
        if (type == null) throw new IllegalArgumentException("Unknown initial: " + name);
        if (!type.accepts(value)) throw new IllegalArgumentException("Snapshot type does not match initial: " + name);
        var values = new LinkedHashMap<>(body.initial().fields());
        values.put(name, value);
        publish(body.edited(body.inputs(), body.steps(), new DataValue.RecordValue(values), body.canvas(), body.inline()));
    }
    private record Location(int stack, int index) {}
    private Location locate(String id) {
        for (int s = 0; s <= body.canvas().detached().size(); s++) {
            var steps = blocks(s);
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).sourceId().equals(id)) return new Location(s, i);
        }
        throw new IllegalArgumentException("Unknown block: " + id);
    }
    public Optional<FlowProgram.Step> find(String id) {
        return allSteps().stream().filter(step -> step.sourceId().equals(id)).findFirst();
    }
    public List<FlowProgram.Step> allSteps() {
        var steps = new ArrayList<>(body.steps()); body.canvas().detached().forEach(stack -> steps.addAll(stack.steps())); return List.copyOf(steps);
    }
    private Set<String> ids() {
        var ids = new HashSet<String>(); allSteps().forEach(step -> ids.add(step.sourceId())); return ids;
    }
    private List<FlowProgram.Step> blocks(int stack) { return stack == 0 ? body.steps() : body.canvas().detached().get(stack - 1).steps(); }
    private void steps(List<FlowProgram.Step> steps) { steps(0, steps); }
    private void steps(int stack, List<FlowProgram.Step> steps) {
        var detached = new ArrayList<>(body.canvas().detached());
        if (stack > 0) {
            if (steps.isEmpty()) detached.remove(stack - 1);
            else detached.set(stack - 1, new FlowBlockLayout.Stack(detached.get(stack - 1).position(), steps));
        }
        publish(body.edited(body.inputs(), stack == 0 ? steps : body.steps(), body.initial(),
            body.canvas().arranged(body.canvas().main(), detached), body.inline()));
    }
    public static boolean delimiter(FlowProgram.Step step) {
        return com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockDeclarations.of(step).delimiter();
    }
    /** Null stack identifies the main hat; other stacks are identified by their first block. */
    public record Socket(String stack, int index, boolean wrap) {
        public Socket(String stack, int index) { this(stack, index, false); }
    }
    /** Place a new prototype in one undo step; no intermediate attachment or execution. */
    public void place(List<FlowProgram.Step> inserted, Socket socket, ProgramDocument.Position position) {
        place(inserted, socket, position, Map.of());
    }
    public void place(List<FlowProgram.Step> inserted, Socket socket, ProgramDocument.Position position, Map<String, ComputationGraph> inline) {
        var prototype = new FlowBlockLayout.Stack(position, inserted);
        var main = new ArrayList<>(body.steps()); var detached = new ArrayList<>(body.canvas().detached());
        boolean displaced = false;
        if (socket == null) detached.add(prototype);
        else {
            int stack = socket.stack() == null ? 0 : locate(socket.stack()).stack();
            var target = stack == 0 ? main : new ArrayList<>(blocks(stack));
            if (socket.index() < 0 || socket.index() > target.size()) throw new IllegalArgumentException("Invalid canvas socket");
            var tail = splice(target, socket.index(), inserted, socket.wrap()); FlowBranches.structure(target);
            if (stack > 0) detached.set(stack - 1, new FlowBlockLayout.Stack(detached.get(stack - 1).position(), target));
            if (!tail.isEmpty()) {
                detached.add(new FlowBlockLayout.Stack(parkedTail(position), tail));
                displaced = true;
            }
        }
        var graphs = new LinkedHashMap<>(body.inline());
        inline.forEach((id, graph) -> { if (graphs.putIfAbsent(id, graph) != null) throw new IllegalArgumentException("Duplicate inline call: " + id); });
        var next = body.edited(body.inputs(), main, body.initial(), body.canvas().arranged(body.canvas().main(), detached), graphs);
        publish(displaced ? positionDisplacedTail(next) : next);
    }
    public List<FlowProgram.Step> suffix(String id) {
        var at = locate(id); var steps = blocks(at.stack());
        int end = groupEnd(steps, at.index()) + 1;
        while (end < steps.size() && !delimiter(steps.get(end))) end = groupEnd(steps, end) + 1;
        return List.copyOf(steps.subList(at.index(), end));
    }
    void clearHistory() { undo.clear(); redo.clear(); }
    void defineWatch(List<FlowProgram.Step> hat, ProgramDocument.Position position, FlowProgram.Watch watch,
            ComputationGraph graph, Map<String, DataValue> shadows) {
        var candidate = new FlowDraft(body);
        if (hat.isEmpty()) {
            candidate.editInline(watch, graph, body.canvas().values().with(watch.sourceId(), shadows, body.canvas().values().parked()));
        } else {
            candidate.place(hat, null, position, Map.of(watch.sourceId(), graph));
            var values = candidate.snapshot().canvas().values();
            candidate.editValues(values.with(watch.sourceId(), shadows, values.parked()));
        }
        publish(candidate.snapshot());
    }
    void setProcedureLabels(String source, List<ScratchProcedureLabel> labels) {
        publish(body.edited(body.inputs(), body.steps(), body.initial(), body.canvas().withProcedureLabels(source, labels), body.inline()));
    }
    private static FlowProgram.Procedure eventReceiver(FlowProgram.Procedure previous, String source, ModuleDefinition.Key key) {
        return new FlowProgram.Procedure(source, key, previous.inputs(), previous.outputs(), previous.returns(),
            previous.rules(), Optional.empty(), previous.event(), previous.terminalKey(), previous.startup());
    }
    void setExtension(String id, Long revision) {
        var enabled = new TreeMap<>(body.canvas().extensions());
        if (revision == null) enabled.remove(id); else enabled.put(id, revision);
        publish(body.edited(body.inputs(), body.steps(), body.initial(), body.canvas().withExtensions(enabled), body.inline()));
    }
    /** Import an ordinary immutable definition and its unbound call with one published undo entry. */
    public String placeTemplate(FlowProgram.Procedure template,Socket socket,ProgramDocument.Position position){
        var candidate=new FlowDraft(body);
        var call=FlowProcedureTools.call(candidate,template);
        var graphs = Map.<String,ComputationGraph>of();
        if (com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(template.key())) {
            var binding = com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(template.key())
                .orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension command"));
            com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.requireEnabled(binding.extension(),body.canvas().extensions());
            if (!binding.template(template.sourceId()).equals(template)) throw new IllegalArgumentException("Fixed extension source differs");
            if (binding.command().shape().bodyCount() > 0 || binding.command().shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.HAT) {
                var mouth = ScratchExtensionMouth.prototype(candidate, binding);
                candidate.place(mouth.steps(), socket, position, mouth.graphs());
                candidate.ensureTemplate(template);
                publish(candidate.snapshot());
                return mouth.steps().getFirst().sourceId();
            }
            var nodes = new LinkedHashMap<String,ComputationGraph.Node>(); var outputs = new LinkedHashMap<String,String>();
            for (var port : new TreeMap<>(binding.command().defaults()).entrySet()) {
                String node = "arg_" + nodes.size(); nodes.put(node,new ComputationGraph.Constant(port.getValue())); outputs.put(port.getKey(),node);
            }
            call = FlowInlineValues.operands(call,call.sourceId(),Map.of());
            graphs = Map.of(call.sourceId(),new ComputationGraph(Map.of(),nodes,outputs));
        }
        candidate.place(List.of(call),socket,position,graphs);
        candidate.ensureTemplate(template);
        publish(candidate.snapshot());return call.sourceId();
    }
    String duplicateStatements(String id, ProgramDocument.Position position) {
        return pasteStatements(copyStatements(id), position);
    }
    ScratchClipboard.Statements copyStatements(String id) {
        var original = find(id).orElseThrow();
        requireOrdinaryStatement(original);
        return copyFragment(suffix(id));
    }
    static boolean clipboardEventHat(FlowProgram.Step step) {
        return step instanceof FlowProgram.Procedure p && p.assembly().isEmpty()
            && (p.event().isPresent() || p.startup() || p.terminalKey().isPresent());
    }
    ScratchClipboard.EventScript copyEventScript(String id) {
        return eventScript(id, false);
    }
    ScratchClipboard.EventScript cutEventScript(String id) {
        var script = eventScript(id, true);
        remove(id); // Like a statement cut, keep dependencies for reinsertion.
        return script;
    }
    private ScratchClipboard.EventScript eventScript(String id, boolean cut) {
        var location = locate(id);
        if (location.stack() == 0 || location.index() != 0)
            throw new IllegalArgumentException("Expected a standalone event hat");
        return new ScratchClipboard.EventScript(copyFragment(blocks(location.stack())), cut);
    }
    private ScratchClipboard.Statements copyFragment(List<FlowProgram.Step> steps) {
        var graphs = new LinkedHashMap<String, ComputationGraph>();
        var shadows = new LinkedHashMap<String, Map<String, DataValue>>();
        for (var step : steps) {
            String source = step.sourceId();
            var graph = body.inline().get(source);
            if (graph != null) graphs.put(source, graph);
            var defaults = body.canvas().values().forBlock(source);
            if (!defaults.isEmpty()) shadows.put(source, defaults);
        }
        return new ScratchClipboard.Statements(steps, graphs, shadows);
    }
    static boolean ordinaryStatement(FlowProgram.Step step) {
        return !(step instanceof FlowProgram.Procedure || step instanceof FlowProgram.Watch || delimiter(step));
    }
    private static void requireOrdinaryStatement(FlowProgram.Step step) {
        if (!ordinaryStatement(step))
            throw new IllegalArgumentException("A definition or event entry is not an ordinary statement stack");
    }
    ScratchClipboard.Statements cutStatements(String id) {
        var fragment = copyStatements(id);
        removeSuffix(id, fragment.steps().size());
        return fragment;
    }
    void discardStatements(String id, ProgramWorkspace workspace) {
        requireOrdinaryStatement(find(id).orElseThrow());
        var candidate = new FlowDraft(body);
        candidate.removeSuffix(id, suffix(id).size());
        acceptDeletion(candidate, workspace);
    }
    private void removeSuffix(String id, int count) {
        var at = locate(id);
        var remaining = new ArrayList<>(blocks(at.stack()));
        remaining.subList(at.index(), at.index() + count).clear();
        steps(at.stack(), remaining);
    }
    String pasteStatements(ScratchClipboard.Statements fragment, ProgramDocument.Position position) {
        return pasteFragment(fragment, position, Optional.empty());
    }
    private record EventPlacement(ModuleDefinition.Key key, boolean restoreBlockIds) {}
    String pasteEventScript(ScratchClipboard.EventScript fragment, ProgramDocument.Position position) {
        var original = (FlowProgram.Procedure)fragment.script().steps().getFirst();
        boolean restoreKey = fragment.cut() && allSteps().stream().noneMatch(step ->
            step instanceof FlowProgram.Procedure p && p.key().equals(original.key()));
        var key = restoreKey ? original.key() : new ModuleDefinition.Key("visual_event_" + UUID.randomUUID(), 1);
        var occupied = ids();
        boolean restoreBlockIds = restoreKey && fragment.script().steps().stream().noneMatch(step -> occupied.contains(step.sourceId()));
        return pasteFragment(fragment.script(), position, Optional.of(new EventPlacement(key, restoreBlockIds)));
    }
    private String pasteFragment(ScratchClipboard.Statements fragment, ProgramDocument.Position position, Optional<EventPlacement> event) {
        var original = fragment.steps();
        var occupied = ids();
        original.forEach(step -> occupied.add(step.sourceId()));
        var copies = new ArrayList<FlowProgram.Step>(original.size());
        var graphs = new LinkedHashMap<>(body.inline());
        var shadows = new LinkedHashMap<>(body.canvas().values().shadows());
        int ordinal = 1;
        for (var step : original) {
            String source;
            if (event.filter(EventPlacement::restoreBlockIds).isPresent()) source = step.sourceId();
            else do { source = "block_" + ordinal++; } while (!occupied.add(source));
            var copy = source.equals(step.sourceId()) ? step
                : event.isPresent() && copies.isEmpty() ? eventReceiver((FlowProgram.Procedure)step, source, event.orElseThrow().key())
                : com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec.copyStep(step, source);
            var graph = fragment.graphs().get(step.sourceId());
            if (graph != null) {
                graphs.put(source, graph);
                // Inline graphs are owned by source ID. Preserve editor semantics (e.g. named lists);
                // FlowDocumentBody.program assigns unique executable entry names at compilation.
                var defaults = fragment.shadows().getOrDefault(step.sourceId(), Map.of());
                if (!defaults.isEmpty()) shadows.put(source, defaults);
            }
            copies.add(copy);
        }
        var stacks = new ArrayList<>(body.canvas().detached());
        stacks.add(new FlowBlockLayout.Stack(position, copies));
        var values = new ScratchValueLayout(shadows, body.canvas().values().parked());
        publish(body.edited(body.inputs(), body.steps(), body.initial(), body.canvas().arranged(body.canvas().main(), stacks).withValues(values), graphs));
        return copies.getFirst().sourceId();
    }
    void accept(FlowDraft candidate) { publish(candidate.snapshot()); }
    void ensureTemplate(FlowProgram.Procedure template) {
        var matches=allSteps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast)
            .filter(p->p.key().equals(template.key())).toList();
        if(matches.size()>1)throw new IllegalArgumentException("Conflicting fixed procedure version: "+template.key());
        if(!matches.isEmpty()){
            var old=matches.getFirst();
            var expected=new FlowProgram.Procedure(old.sourceId(),template.key(),template.inputs(),template.outputs(),template.returns(),template.rules(),template.assembly(),template.event(),template.terminalKey(),template.startup());
            var stack=body.canvas().detached().stream().filter(s->s.steps().getFirst().sourceId().equals(old.sourceId())).findFirst();
            if(!old.equals(expected)||stack.isEmpty()||stack.orElseThrow().steps().size()!=2||!(stack.orElseThrow().steps().getLast() instanceof FlowProgram.EndProcedure))
                throw new IllegalArgumentException("Conflicting fixed procedure version: "+template.key());
        }
        if(matches.isEmpty()){
            var used=ids();String id;int n=1;do{id="template_"+n++;}while(used.contains(id)||used.contains(id+"_end"));
            var definition=new FlowProgram.Procedure(id,template.key(),template.inputs(),template.outputs(),template.returns(),template.rules(),template.assembly(),template.event(),template.terminalKey(),template.startup());
            place(List.of(definition,new FlowProgram.EndProcedure(id+"_end")),null,
                new ProgramDocument.Position(280,16+snapshot().canvas().detached().size()*80));
        }
    }
    /** A drag is one atomic edit, committed only on release. Null socket parks an inert draft stack. */
    public void drop(String id, Socket socket, ProgramDocument.Position position) {
        Objects.requireNonNull(position);
        var from = locate(id); var moved = suffix(id);
        if (moved.getFirst() instanceof FlowProgram.Watch) throw new IllegalArgumentException("Move the complete monitor hat");
        int target = socket == null ? -1 : socket.stack() == null ? 0 : locate(socket.stack()).stack();
        int insertion = socket == null ? 0 : socket.index();
        if (target >= 0 && (insertion < 0 || insertion > blocks(target).size())) throw new IllegalArgumentException("Invalid canvas socket");
        if (target == from.stack() && insertion >= from.index() && insertion <= from.index() + moved.size()) return;
        var stacks = new ArrayList<List<FlowProgram.Step>>(); var positions = new ArrayList<ProgramDocument.Position>();
        stacks.add(new ArrayList<>(body.steps())); positions.add(body.canvas().main());
        body.canvas().detached().forEach(stack -> { stacks.add(new ArrayList<>(stack.steps())); positions.add(stack.position()); });
        stacks.get(from.stack()).subList(from.index(), from.index() + moved.size()).clear();
        boolean displaced = false;
        if (target < 0) { stacks.add(new ArrayList<>(moved)); positions.add(position); }
        else {
            if (target == from.stack() && insertion > from.index()) insertion -= moved.size();
            var tail = splice(stacks.get(target), insertion, moved, socket.wrap());
            if (!tail.isEmpty()) {
                stacks.add(tail);
                positions.add(parkedTail(position));
                displaced = true;
            }
        }
        FlowBranches.structure(stacks.getFirst());
        var detached = new ArrayList<FlowBlockLayout.Stack>();
        for (int i = 1; i < stacks.size(); i++) if (!stacks.get(i).isEmpty()) detached.add(new FlowBlockLayout.Stack(positions.get(i), stacks.get(i)));
        var next = body.edited(body.inputs(), stacks.getFirst(), body.initial(), body.canvas().arranged(positions.getFirst(), detached), body.inline());
        publish(displaced ? positionDisplacedTail(next) : next);
    }
    public void moveMain(ProgramDocument.Position position) {
        rearrange(body.canvas().arranged(position, body.canvas().detached()));
    }
    void rearrange(FlowBlockLayout layout) {
        publish(body.edited(body.inputs(), body.steps(), body.initial(), layout, body.inline()));
    }
    static boolean openSocket(List<FlowProgram.Step> steps, int index) {
        return index == 0 || !cap(steps.get(index - 1));
    }
    private static boolean cap(FlowProgram.Step step) { return step instanceof FlowProgram.StopScript stop && stop.cap(); }
    private static void requireOpenSocket(List<FlowProgram.Step> steps, int index) {
        if (!openSocket(steps, index)) throw new IllegalArgumentException("Stop script has no lower connector");
    }

    /** A cap has no next connector; an existing tail in this branch becomes a parked stack, not lost code. */
    private static List<FlowProgram.Step> splice(List<FlowProgram.Step> target, int index, List<FlowProgram.Step> inserted) {
        return splice(target, index, inserted, false);
    }

    private static List<FlowProgram.Step> splice(List<FlowProgram.Step> target, int index, List<FlowProgram.Step> inserted, boolean wrap) {
        requireOpenSocket(target, index);
        if (wrap) {
            if (!emptyWrapMouth(inserted) || index >= target.size() || !ordinaryStatement(target.get(index)))
                throw new IllegalArgumentException("An empty first opening and an existing statement are required");
            int end = branchTailEnd(target, index);
            var wrapped = new ArrayList<>(inserted);
            wrapped.addAll(1, target.subList(index, end));
            target.subList(index, end).clear();
            target.addAll(index, wrapped);
            return List.of();
        }
        List<FlowProgram.Step> tail = List.of();
        if (cap(inserted.getLast())) {
            int end = branchTailEnd(target, index);
            if (end > index) {
                tail = new ArrayList<>(target.subList(index, end));
                target.subList(index, end).clear();
            }
        }
        target.addAll(index, inserted);
        return tail;
    }
    static boolean emptyWrapMouth(List<FlowProgram.Step> steps) {
        if (steps.size() < 2) return false;
        var header = steps.getFirst();
        if (!(header instanceof FlowProgram.Repeat || header instanceof FlowProgram.Forever
                || header instanceof FlowProgram.Until || header instanceof FlowProgram.If)) return false;
        return delimiter(steps.get(1));
    }

    private static int branchTailEnd(List<FlowProgram.Step> steps, int index) {
        var branches = FlowBranches.structure(steps);
        while (index < steps.size() && !delimiter(steps.get(index))) {
            index = Math.max(index, branches.end(index)) + 1;
        }
        return index;
    }

    private static ProgramDocument.Position parkedTail(ProgramDocument.Position position) {
        // Clear the cap's body, snap tolerance and the parked-stack caption.
        return new ProgramDocument.Position(position.x() + 36, position.y() + 56);
    }
    private void remember(FlowDocumentBody next, Runnable restore, Runnable reapply) {
        if (undo.size() == HISTORY) undo.removeFirst();
        undo.addLast(new Revision(body, restore, reapply)); redo.clear(); body = next;
    }
    private void publish(FlowDocumentBody next) {
        if (next.equals(body)) return;
        remember(next, () -> {}, () -> {});
    }
}
