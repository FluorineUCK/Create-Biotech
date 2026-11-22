package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockSkin.Shape;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.function.Consumer;
import java.util.function.Function;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Spatial block view over the existing Flow draft. Gestures never touch an installed program. */
final class FlowBlockCanvas {
    private static final int PROTOTYPE_Y = 10;
    private static final int FRAME_MARGIN = 6;
    private static final double MIN_AUTO_SCALE = 0.5;
    static final int WIDTH = 224, ROW = 28, INDENT = 16, HAT = 24;
    record Block(FlowProgram.Step step, String stack, int index, int depth, double x, double y, int height, int width, int opening, boolean extensionColor) {
        Block withWidth(int value) { return new Block(step, stack, index, depth, x, y, height, value, opening, extensionColor); }
    }
    private record BroadcastChip(Component caption, String label, Component suffix, int x, int width) {}
    private static final int CHANNEL_HEIGHT = 18;
    private record WaitLayout(InlineGraphView.Tile value, int valueX, Component unit, int unitX, int unitWidth, int height) {
        int width() { return unitX + unitWidth + 8; }
        int valueY() { return (height - value.height()) / 2; }
        int unitY() { return (height - CHANNEL_HEIGHT) / 2; }
    }
    record Joint(FlowDraft.Socket socket, double x, double y, int opening, int wrapHeight) {
        Joint(FlowDraft.Socket socket, double x, double y, int opening) { this(socket, x, y, opening, 0); }
        Joint(FlowDraft.Socket socket, double x, double y) { this(socket, x, y, 0); }
    }
    private final FlowDraft draft;
    private final CanvasViewport viewport = new CanvasViewport();
    private final Runnable changed;
    private final java.util.function.Supplier<Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue>> readerCapture;
    private int x, y, width, height;
    private Font font;
    private String selected, pressed;
    private List<FlowProgram.Step> prototype = List.of();
    private Map<String, ComputationGraph> prototypeGraphs = Map.of();
    private FlowProgram.Procedure prototypeTemplate;
    private boolean panning, mainPressed, moved;
    private double pressX, pressY, pointerX, pointerY, offsetX, offsetY;
    FlowBlockCanvas(FlowDraft draft, Runnable changed) { this(draft, changed, Optional::empty); }
    FlowBlockCanvas(FlowDraft draft, Runnable changed,
            java.util.function.Supplier<Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue>> readerCapture) {
        this.draft = draft; this.changed = changed; this.readerCapture = readerCapture;
        draft.placeDisplacedTailWith(this::displacedTailPosition);
    }
    void bounds(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    void font(Font font) { this.font = font; }
    boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    String selected() { return selected; }
    void selected(String id) { selected = id; }
    private String conditionSource;
    void conditionOnly(String source) { conditionSource = Objects.requireNonNull(source); }
    ProgramDocument.Position newHatPosition(int hatWidth) {
        var body = draft.snapshot();
        var main = body.canvas().main();
        double left = main.x(), right = left + hatWidth;
        double bottom = main.y() + HAT;
        for (var block : blocks()) {
            if (block.height() == 0) continue;
            // C mouths reserve their empty cavity and the nested rail at the parent's left.
            if (block.x() - block.depth() * INDENT < right && block.x() + block.width() > left)
                bottom = Math.max(bottom, block.y() + block.height());
        }
        for (var reporter : body.canvas().values().parked()) {
            var tile = tile(reporter);
            if (reporter.position().x() < right && reporter.position().x() + tile.width() > left)
                bottom = Math.max(bottom, reporter.position().y() + tile.height());
        }
        return new ProgramDocument.Position(left, bottom + INDENT);
    }
    ProgramDocument.Position blockPosition(String source) {
        return blocks().stream().filter(block -> block.step().sourceId().equals(source)).findFirst()
            .map(block -> new ProgramDocument.Position(block.x(), block.y())).orElse(null);
    }
    void revealCondition(String node) {
        if (conditionSource == null || width <= 2 * FRAME_MARGIN || height <= 2 * FRAME_MARGIN) return;
        inlineSlots().stream().filter(slot -> conditionSource.equals(slot.call()) && node.equals(slot.node()))
            .findFirst().ifPresent(slot -> {
                if (contains(slot.x() - 1, slot.y() - 1) &&
                    contains(slot.x() + slot.width() + 1, slot.y() + slot.height() + 1)) return;
                double left = slot.x() - x - 1, top = slot.y() - y - 1;
                double right = left + slot.width() + 2, bottom = top + slot.height() + 2;
                if (right - left <= width && bottom - top <= height) {
                    viewport.pan(revealOffset(left, right, width), revealOffset(top, bottom, height));
                } else {
                    frame(viewport.worldX(left), viewport.worldY(top), viewport.worldX(right), viewport.worldY(bottom));
                }
            });
    }
    private static double revealOffset(double start, double end, int extent) {
        // Narrow forms can fit the operand without the ordinary whole-group frame margin.
        double margin = Math.min(FRAME_MARGIN, (extent - (end - start)) / 2);
        return start < margin ? margin - start : Math.min(0, extent - margin - end);
    }
    void resetView() { viewport.reset(); }
    void zoomAtCenter(int steps) {
        scroll(x + width / 2.0, y + height / 2.0, 0, steps, true);
    }
    void resetWithSelection() {
        resetView();
        // A definition editor already frames its hat; Reset is the route back to the startup stack.
        if (selected != null && draft.find(selected).map(step -> shape(step) == Shape.MOUTH).orElse(false))
            revealGroup(selected);
    }
    /** Frame a definition hat or a C-shaped group without moving any draft blocks. */
    private void revealGroup(String id) {
        if (id == null || width <= 2 * FRAME_MARGIN || height <= 2 * FRAME_MARGIN) return;
        var rows = blocks();
        for (int i = 0; i < rows.size(); i++) {
            var head = rows.get(i);
            if (!id.equals(head.step().sourceId())) continue;
            if (frameDefinition(head)) return;
            if (shape(head.step()) != Shape.MOUTH) continue;
            double right = head.x() + head.width();
            for (int j = i + 1; j < rows.size(); j++) {
                var row = rows.get(j);
                if (!Objects.equals(head.stack(),row.stack()) || row.depth() < head.depth()) return;
                right = Math.max(right,row.x() + row.width());
                if (row.depth() == head.depth() && shape(row.step()) == Shape.FOOT) {
                    // The Scratch atlas draws the outline and connector outside the logical row.
                    frame(head.x()-1,head.y()-1,right+1,row.y()+row.height()+5);
                    return;
                }
            }
            return;
        }
    }
    private void frame(double left, double top, double right, double bottom) {
        double availableWidth = width - 2 * FRAME_MARGIN, availableHeight = height - 2 * FRAME_MARGIN;
        double fit = Math.min(availableWidth / (right-left),availableHeight / (bottom-top));
        double target = Math.max(fit,MIN_AUTO_SCALE);
        double screenLeft = viewport.screenX(left), screenTop = viewport.screenY(top);
        while (viewport.scale() > target) viewport.zoomAt(screenLeft,screenTop,-1);
        viewport.pan(frameOffset(screenLeft,viewport.screenX(right),width),
            frameOffset(screenTop,viewport.screenY(bottom),height));
    }
    private static double frameOffset(double start, double end, int extent) {
        if (end - start > extent - 2 * FRAME_MARGIN || start < FRAME_MARGIN) return FRAME_MARGIN - start;
        return Math.min(0,extent - FRAME_MARGIN - end);
    }
    void focus(String id) {
        blocks().stream().filter(block -> block.step().sourceId().equals(id)).findFirst().ifPresent(block -> {
            focus(new ProgramDocument.Position(block.x(), block.y()));
            frameDefinition(block);
        });
    }
    private boolean frameDefinition(Block block) {
        if (!(block.step() instanceof FlowProgram.Procedure) || block.height() == 0) return false;
        if (width > 2 * FRAME_MARGIN && height > 2 * FRAME_MARGIN)
            frame(block.x()-1, block.y()-1, block.x()+block.width()+1, block.y()+block.height()+5);
        return true;
    }
    void focus(ProgramDocument.Position position) {
        viewport.reset(); viewport.pan(16-position.x(),24-position.y());
    }
    void focusParked(int index, String node) {
        var parked = draft.snapshot().canvas().values().parked();
        if (index < 0 || index >= parked.size()) return;
        var reporter = parked.get(index);
        var position = reporter.position();
        focus(position);
        var occurrences = new ArrayList<Slot>();
        slots(occurrences, null, tile(reporter), position.x(), position.y());
        occurrences.stream().filter(slot -> Objects.equals(node, slot.node())).findFirst().ifPresent(slot -> {
            if (width <= 2 * FRAME_MARGIN || height <= 2 * FRAME_MARGIN) return;
            if (contains(slot.x(), slot.y()) && contains(slot.x() + slot.width(), slot.y() + slot.height())) return;
            var start = reporterPosition(slot.x(), slot.y());
            var end = reporterPosition(slot.x() + slot.width(), slot.y() + slot.height());
            frame(start.x(), start.y(), end.x(), end.y());
        });
    }
    void focus(String id, String node) {
        focus(id);
        // A wide expression may extend beyond the owning statement's visible bounds.
        inlineSlots().stream().filter(slot -> id.equals(slot.call()) && node.equals(slot.node())).findFirst()
            .filter(slot -> !contains(slot.x() + slot.width() / 2, slot.y() + slot.height() / 2))
            .ifPresent(slot -> focus(reporterPosition(slot.x(), slot.y())));
    }
    private static final int STACK_CLEARANCE = 8;
    private record StackArea(double left, double top, double right, double bottom) {
        StackArea include(StackArea other) {
            return new StackArea(Math.min(left, other.left), Math.min(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
        }
    }
    /** Bounds include the actual nested rows, outlines, connectors and root captions. */
    private Map<String, StackArea> stackAreas() {
        return stackAreas(true);
    }
    private Map<String, StackArea> stackAreas(boolean includeWorkspaceRoots) {
        var body = draft.snapshot();
        var origin = body.canvas().main();
        var areas = new HashMap<String, StackArea>();
        if (includeWorkspaceRoots) areas.put(null, new StackArea(origin.x() - 1, origin.y() - 1, origin.x() + WIDTH + 1, origin.y() + HAT + 5));
        for (var block : blocks()) if (block.height() > 0) {
            // The outline and lower connector extend outside the logical row.
            var area = new StackArea(block.x() - 1, block.y() - 1, block.x() + block.width() + 1, block.y() + block.height() + 5);
            areas.merge(block.stack(), area, StackArea::include);
        }
        var parkedLabel = Component.translatable("create_biotech.biocomputing.flow_editor.parked_stack");
        for (var stack : includeWorkspaceRoots ? visibleStacks(body) : List.<FlowBlockLayout.Stack>of()) {
            var label = stackAnnotation(stack, parkedLabel);
            if (label == null) continue;
            var at = stack.position();
            areas.merge(stack.steps().getFirst().sourceId(),
                new StackArea(at.x(), at.y() - 12, at.x() + measure(label.getString()), at.y()), StackArea::include);
        }

        return areas;
    }
    private StackArea reporterArea(ScratchValueLayout.Reporter reporter) {
        var at = reporter.position();
        var tile = tile(reporter);
        return new StackArea(at.x() - 1, at.y() - 1, at.x() + tile.width() + 1, at.y() + tile.height() + 1);
    }
    private record LayoutPiece(ProgramDocument.Position at, StackArea area, Consumer<ProgramDocument.Position> move) {}

    /** An explicit editor action; keep the startup script as the workspace anchor. */
    boolean cleanUpBlocks() {
        var canvas = draft.snapshot().canvas();
        var areas = stackAreas();
        var detached = new ArrayList<>(canvas.detached());
        var reporters = new ArrayList<>(canvas.values().parked());
        var pieces = new ArrayList<LayoutPiece>();
        for (int i = 0; i < detached.size(); i++) {
            int index = i;
            var stack = detached.get(index);
            var area = areas.get(stack.steps().getFirst().sourceId());
            if (area != null) pieces.add(new LayoutPiece(stack.position(), area, position ->
                detached.set(index, new FlowBlockLayout.Stack(position, stack.steps()))));
        }
        for (int i = 0; i < reporters.size(); i++) {
            int index = i;
            var reporter = reporters.get(index);
            pieces.add(new LayoutPiece(reporter.position(), reporterArea(reporter),
                position -> reporters.set(index, reporter.at(position))));
        }
        // Sort the visual order only; the original definition and reporter lists keep their identities and order.
        pieces.sort(Comparator.comparingDouble((LayoutPiece piece) -> piece.at().y()).thenComparingDouble(piece -> piece.at().x()));
        var main = canvas.main();
        double top = areas.get(null).bottom() + STACK_CLEARANCE;
        for (var piece : pieces) {
            piece.move().accept(new ProgramDocument.Position(main.x(), top + (piece.at().y() - piece.area().top())));
            top += (piece.area().bottom() - piece.area().top()) + STACK_CLEARANCE;
        }
        if (detached.equals(canvas.detached()) && reporters.equals(canvas.values().parked())) return false;
        draft.rearrange(canvas.arranged(main, detached).withValues(canvas.values().withParked(reporters)));
        if (!contains(viewport.screenX(main.x()) + x, viewport.screenY(main.y()) + y))
            frame(main.x(), main.y(), main.x() + WIDTH, main.y() + HAT);
        return true;
    }

    /** Find a vacancy for the newly ejected value without moving the script or the camera. */
    void placeLastDisplacedReporter(FlowDraft candidate) {
        var proposed = candidate.snapshot();
        var values = proposed.canvas().values();
        var parked = new ArrayList<>(values.parked());
        var reporter = parked.removeLast();
        var preview = new FlowBlockCanvas(new FlowDraft(proposed), () -> {}, readerCapture);
        preview.font(font);
        if (conditionSource != null) preview.conditionOnly(conditionSource);
        var occupied = new ArrayList<>(preview.stackAreas(conditionSource == null).values());
        for (var other : parked) occupied.add(preview.reporterArea(other));
        occupied.sort(Comparator.comparingDouble(StackArea::top).thenComparingDouble(StackArea::bottom));

        var at = reporter.position();
        var area = preview.reporterArea(reporter);
        double dx = at.x() - area.left(), dy = at.y() - area.top();
        double blockWidth = area.right() - area.left(), blockHeight = area.bottom() - area.top();
        double viewLeft = viewport.worldX(FRAME_MARGIN), viewTop = viewport.worldY(FRAME_MARGIN);
        double maxLeft = Math.max(viewLeft, viewport.worldX(width - FRAME_MARGIN) - blockWidth);
        double maxTop = viewport.worldY(height - FRAME_MARGIN) - blockHeight;
        double left = Math.clamp(area.left(), viewLeft, maxLeft);
        double start = Math.max(area.top(), viewTop);
        double top = freeTop(occupied, left, left + blockWidth, start, blockHeight);
        if (top > maxTop) {
            // A tall stack may block the drop lane. Try the visible sides before parking below it.
            double bestDistance = Double.POSITIVE_INFINITY;
            double fallbackLeft = left, fallbackTop = top;
            for (double lane : new double[] {left, viewLeft, maxLeft}) {
                double fit = freeTop(occupied, lane, lane + blockWidth, viewTop, blockHeight);
                double distance = Math.abs(lane - left) + Math.abs(fit - start);
                if (fit <= maxTop && distance < bestDistance) {
                    fallbackLeft = lane;
                    fallbackTop = fit;
                    bestDistance = distance;
                }
            }
            left = fallbackLeft;
            top = fallbackTop;
        }
        parked.add(reporter.at(new ProgramDocument.Position(left + dx, top + dy)));
        candidate.editValues(values.withParked(parked));
    }

    private static double freeTop(List<StackArea> occupied, double left, double right, double top, double height) {
        for (var other : occupied) {
            if (right + STACK_CLEARANCE <= other.left() || left >= other.right() + STACK_CLEARANCE) continue;
            if (top + height + STACK_CLEARANCE <= other.top()) break;
            top = Math.max(top, other.bottom() + STACK_CLEARANCE);
        }
        return top;
    }

    /** Measure the proposed layout with the same font, open mouths and nested values as the canvas. */
    private ProgramDocument.Position displacedTailPosition(FlowDocumentBody proposed) {
        var preview = new FlowBlockCanvas(new FlowDraft(proposed), () -> {});
        preview.font(font);
        var areas = preview.stackAreas();
        var tail = proposed.canvas().detached().getLast();
        var area = areas.remove(tail.steps().getFirst().sourceId());
        if (area == null) return tail.position();
        var occupied = new ArrayList<>(areas.values());
        for (var reporter : proposed.canvas().values().parked()) occupied.add(preview.reporterArea(reporter));
        // Only objects in this horizontal lane can block the drop; existing stacks never move.
        occupied.sort(Comparator.comparingDouble(StackArea::top).thenComparingDouble(StackArea::bottom));
        double top = freeTop(occupied, area.left(), area.right(), area.top(), area.bottom() - area.top());
        return new ProgramDocument.Position(tail.position().x(), tail.position().y() + (top - area.top()));
    }
    List<Block> blocks() {
        var result = new ArrayList<Block>(); var body = draft.snapshot();
        if (conditionSource != null) {
            draft.find(conditionSource).ifPresent(step -> {
                var tile = tile(visualGraph(step), step);
                result.add(new Block(step, null, 0, 0, 8, 5, tile.height(), tile.width(), 0, false));
            });
            return result;
        }
        append(result, body.steps(), null, body.canvas().main().x(), body.canvas().main().y() + HAT);
        for (var stack : visibleStacks(body)) append(result, stack.steps(), stack.steps().getFirst().sourceId(), stack.position().x(), stack.position().y());
        return result;
    }
    private static List<FlowBlockLayout.Stack> visibleStacks(FlowDocumentBody body) {
        var detached = body.canvas().detached();
        if (detached.stream().noneMatch(stack -> fixedExtensionStack(stack.steps()))) return detached;
        var commands = new HashSet<ModuleDefinition.Key>();
        java.util.stream.Stream.concat(body.steps().stream(), detached.stream().flatMap(stack -> stack.steps().stream()))
            .filter(FlowProgram.Invoke.class::isInstance).map(FlowProgram.Invoke.class::cast)
            .map(FlowProgram.Invoke::procedure).filter(ScratchExtensions::reserved).forEach(commands::add);
        body.inline().values().forEach(graph -> graph.nodes().values().forEach(node -> {
            if (node instanceof ComputationGraph.ProcedureValue call && ScratchExtensions.reserved(call.procedure())) commands.add(call.procedure());
        }));
        // A bound command is edited at its call site. Expose orphaned/malformed bindings so an
        // imported draft can still be repaired instead of leaving an invisible installation error.
        return detached.stream().filter(stack -> !fixedExtensionStack(stack.steps())
            || !commands.contains(((FlowProgram.Procedure) stack.steps().getFirst()).key())).toList();
    }
    private static boolean fixedExtensionStack(List<FlowProgram.Step> steps) {
        return steps.size() == 2 && steps.getFirst() instanceof FlowProgram.Procedure procedure
            && ScratchExtensions.reserved(procedure.key()) && steps.getLast() instanceof FlowProgram.EndProcedure;
    }
    private void append(List<Block> output, List<FlowProgram.Step> steps, String stack, double x, double y) {
        int first = output.size();
        FlowBranches branches = null;
        try { branches = FlowBranches.structure(steps); } catch (RuntimeException ignored) { /* Imported invalid drafts remain inspectable. */ }
        var extensionBorders = new HashSet<Integer>();
        boolean procedureStack = !steps.isEmpty() && steps.getFirst() instanceof FlowProgram.Procedure;
        boolean monitor = steps.size() > 1 && steps.get(1) instanceof FlowProgram.Watch;
        for (int i = 0; i < steps.size(); i++) {
            // A logical procedure scope is not a visual C-shaped block.
            int depth = branches == null ? 0 : Math.max(0, branches.depth(i) - (procedureStack ? 1 : 0));
            var step = steps.get(i); int height = FlowDraft.delimiter(step) && !(step instanceof FlowProgram.Otherwise) ? 12 : ROW;
            var graph = step instanceof FlowProgram.Watch ? null : visualGraph(step);
            int width = WIDTH;
            var fixedHat = fixedHatTile(step, visualGraph(step));
            if (fixedHat != null) {
                height = Math.max(HAT, fixedHat.height() + 12);
                width = Math.max(68, fixedHat.width() + 16);
                extensionBorders.add(i);
            } else if (step instanceof FlowProgram.Watch watch) { height = HAT; width = Math.max(WIDTH, measure(watchTitle(watch).getString()) + 16); }
            else if (monitor && i == 0) height = 0;
            else if (eventHat(step)) height = HAT;
            else if (step instanceof FlowProgram.Procedure definition) {
                width = definitionInset() + definitionPreview(definition).width() + 5;
                height = 42;
            } else if (step instanceof FlowProgram.EndProcedure) height = 0;
            var broadcast = selectorChip(step);
            if (broadcast != null) width = Math.max(92, broadcast.x() + broadcast.width() + measure(broadcast.suffix().getString()) + 18);
            var layout = callLayout(step, graph);
            if (layout != null) {
                if (branches != null && !(step instanceof FlowProgram.Invoke)) {
                    extensionBorders.add(i);
                    extensionBorders.add(branches.end(i));
                }
                height = layout.height();
                width = layout.width();
            } else if (step instanceof FlowProgram.Sleep sleep && editableWait()) {
                var wait = waitLayout(sleep, graph);
                height = wait.height();
                width = wait.width();
            } else if (graph != null) {
                var tile = tile(graph, step); height = wrapped(step) ? 30 + tile.height() : Math.max(ROW, tile.height() + 10);
                width = Math.max(WIDTH, Math.max(inlineX(step), slotX(step) + tile.width() + 8));
            }
            int opening = branches != null && i > 0 && FlowDraft.delimiter(step) && opensBody(shape(steps.get(i - 1))) ? ROW : 0;
            y += opening;
            output.add(new Block(step, stack, i, depth, x + depth * INDENT, y, height, width, opening, extensionBorders.contains(i)
                || step instanceof FlowProgram.Otherwise && branches != null && extensionBorders.contains(branches.end(i)))); y += height;
        }
        if (branches != null) enclose(output, first, steps, branches);
    }
    /** A linear bottom-up extent pass keeps nested headers, else bars and closing bars aligned. */
    private static void enclose(List<Block> rows, int first, List<FlowProgram.Step> steps, FlowBranches branches) {
        int[] parent = new int[steps.size()]; double[] right = new double[steps.size()];
        var open = new ArrayDeque<Integer>();
        for (int i = 0; i < steps.size(); i++) {
            parent[i] = open.isEmpty() ? -1 : open.peek();
            var row = rows.get(first + i); right[i] = row.x() + row.width();
            if (branches.start(i) >= 0 && cavityStart(steps, branches, branches.start(i))) open.pop();
            else if (cavityStart(steps, branches, i)) open.push(i);
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            var row = rows.get(first + i);
            int width = (int)Math.ceil(right[i] - row.x());
            if (cavityStart(steps, branches, i)) {
                rows.set(first + i, row.withWidth(width));
                int end = first + branches.end(i); rows.set(end, rows.get(end).withWidth(width));
            }
            if (parent[i] >= 0) {
                var owner = rows.get(first + parent[i]);
                right[parent[i]] = Math.max(right[parent[i]], right[i] + (row.depth() > owner.depth() ? 8 : 0));
            }
        }
        for (int i = 0; i < steps.size(); i++) if (steps.get(i) instanceof FlowProgram.Otherwise && parent[i] >= 0)
            rows.set(first + i, rows.get(first + i).withWidth(rows.get(first + parent[i]).width()));
    }
    private static boolean cavityStart(List<FlowProgram.Step> steps, FlowBranches branches, int index) {
        return branches.end(index) > index && shape(steps.get(index)) == Shape.MOUTH;
    }

    List<Joint> joints() {
        var result = new ArrayList<Joint>(); var body = draft.snapshot();
        joints(result, body.steps(), null, body.canvas().main().x(), body.canvas().main().y() + HAT);
        for (var stack : visibleStacks(body)) joints(result, stack.steps(), stack.steps().getFirst().sourceId(), stack.position().x(), stack.position().y());
        return result;
    }
    private void joints(List<Joint> result, List<FlowProgram.Step> steps, String key, double x, double y) {
        var rows = new ArrayList<Block>(); append(rows, steps, key, x, y);
        for (var block : rows) if (FlowDraft.openSocket(steps, block.index())) result.add(new Joint(new FlowDraft.Socket(key, block.index()),
            block.x() + (FlowDraft.delimiter(block.step()) && !(block.step() instanceof FlowProgram.EndProcedure) ? INDENT : 0),
            block.y() - block.opening(), block.opening()));
        if (FlowDraft.openSocket(steps, steps.size())) result.add(new Joint(new FlowDraft.Socket(key, steps.size()), x, rows.isEmpty() ? y : rows.getLast().y() + rows.getLast().height()));
    }
    private Block hit(double wx, double wy) {
        var blocks = blocks();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            var b = blocks.get(i);
            if (wx >= b.x() && wx < b.x() + b.width() && wy >= b.y() && wy < b.y() + b.height()) return b;
        }
        return null;
    }
    FlowProgram.Sleep waitMenuAt(double mx, double my) {
        var block = blockAt(mx, my);
        if (!editableWait() || block == null || !(block.step() instanceof FlowProgram.Sleep sleep) || parkedHit(mx,my) >= 0) return null;
        var layout = waitLayout(sleep, visualGraph(sleep));
        double dx = viewport.worldX(mx - x) - block.x(), dy = viewport.worldY(my - y) - block.y();
        return dx >= layout.unitX() && dx < layout.unitX() + layout.unitWidth()
            && dy >= layout.unitY() && dy < layout.unitY() + CHANNEL_HEIGHT ? sleep : null;
    }
    boolean mainHatAt(double mx, double my) {
        if (!contains(mx, my) || conditionSource != null) return false;
        var main = draft.snapshot().canvas().main();
        var point = reporterPosition(mx, my);
        return point.x() >= main.x() && point.x() < main.x() + WIDTH
            && point.y() >= main.y() && point.y() < main.y() + HAT;
    }
    FlowProgram.StopScript stopMenuAt(double mx, double my) {
        if (!contains(mx, my) || parkedHit(mx, my)>=0) return null;
        double wx = viewport.worldX(mx - x), wy = viewport.worldY(my - y);
        var block = hit(wx, wy);
        return block != null && block.step() instanceof FlowProgram.StopScript stop && wx >= block.x() + block.width() - 22 ? stop : null;
    }
    FlowProgram.Procedure keyMenuAt(double mx,double my) {
        var block=blockAt(mx,my);
        if(block==null || parkedHit(mx,my)>=0 || !(block.step() instanceof FlowProgram.Procedure hat) || hat.terminalKey().isEmpty())return null;
        var chip=selectorChip(hat);double wx=viewport.worldX(mx-x)-block.x(),wy=viewport.worldY(my-y)-block.y();
        int top=(block.height()-CHANNEL_HEIGHT)/2;
        return wx>=chip.x() && wx<chip.x()+chip.width() && wy>=top && wy<top+CHANNEL_HEIGHT?hat:null;
    }
    FlowProgram.Monitor monitorMenuAt(double mx, double my) {
        var block = blockAt(mx, my);
        if (block == null || !(block.step() instanceof FlowProgram.Monitor monitor) || parkedHit(mx, my) >= 0) return null;
        var chip = monitorChip(monitor);
        double dx = viewport.worldX(mx - x) - block.x(), dy = viewport.worldY(my - y) - block.y();
        int top = (block.height() - CHANNEL_HEIGHT) / 2;
        return dx >= chip.x() && dx < chip.x() + chip.width() && dy >= top && dy < top + CHANNEL_HEIGHT ? monitor : null;
    }
    FlowProgram.Step broadcastMenuAt(double mx, double my) {
        var block = blockAt(mx, my);
        if (block == null || parkedHit(mx, my) >= 0) return null;
        var chip = broadcastChip(block.step());
        if (chip == null) return null;
        double wx = viewport.worldX(mx - x) - block.x(), wy = viewport.worldY(my - y) - block.y();
        int top = (block.height() - CHANNEL_HEIGHT) / 2;
        return wx >= chip.x() && wx < chip.x() + chip.width() && wy >= top && wy < top + CHANNEL_HEIGHT ? block.step() : null;
    }
    boolean click(double mx, double my, int button) {
        if (!contains(mx, my) || button != 0) return false;
        cancel(); pressX = pointerX = mx; pressY = pointerY = my;
        double wx = viewport.worldX(mx - x), wy = viewport.worldY(my - y);
        var block = hit(wx, wy);
        if (block != null) {
            if (conditionSource != null) return false;
            selected = block.step() instanceof FlowProgram.Watch ? block.stack() : block.step().sourceId();
            if (!FlowDraft.delimiter(block.step())) { draft.suffix(selected); pressed = selected; offsetX = wx - block.x(); offsetY = wy - block.y(); }
        } else {
            selected = null;
            var main = draft.snapshot().canvas().main();
            mainPressed = wx >= main.x() && wx < main.x() + WIDTH && wy >= main.y() && wy < main.y() + HAT;
            if (mainPressed) { offsetX = wx - main.x(); offsetY = wy - main.y(); } else panning = true;
        }
        return true;
    }
    boolean drag(double mx, double my, int button, double dx, double dy) {
        if (button != 0 || !active()) return false;
        if (panning) viewport.pan(mx - pointerX, my - pointerY);
        pointerX = mx; pointerY = my; moved |= Math.hypot(mx - pressX, my - pressY) > 4;
        return true;
    }
    void takePrototype(List<FlowProgram.Step> steps, double mx, double my) {
        takePrototype(steps, Map.of(), mx, my);
    }
    void takePrototype(List<FlowProgram.Step> steps, Map<String, ComputationGraph> graphs, double mx, double my) {
        cancel(); prototype = List.copyOf(steps); prototypeGraphs = Map.copyOf(graphs); pressX = pointerX = mx; pressY = pointerY = my;
        offsetX = 8; offsetY = 10;
    }
    boolean prototypeMoved(double mx, double my) { return !prototype.isEmpty() && (moved || Math.hypot(mx - pressX, my - pressY) > 4); }
    void takeTemplate(FlowProgram.Procedure template,double mx,double my){
        var fixed = ScratchExtensions.binding(template.key()).filter(b -> b.command().shape().bodyCount() > 0 || b.command().shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.HAT);
        if (fixed.isPresent()) {
            var block = ScratchExtensionMouth.prototype(draft, fixed.get());
            takePrototype(block.steps(), block.graphs(), mx, my);
        } else takePrototype(List.of(FlowProcedureTools.call(draft,template)),mx,my);
        prototypeTemplate=template;
    }
    boolean discardDraggedStatements(double mx, double my, int button, ProgramWorkspace workspace) {
        if (button != 0 || pressed == null || (!moved && Math.hypot(mx - pressX, my - pressY) <= 4)) return false;
        String id = pressed;
        var step = draft.find(id).orElse(null);
        if (step == null) return false;
        boolean event = FlowDraft.clipboardEventHat(step);
        if (!event && !FlowDraft.ordinaryStatement(step)) return false;
        cancel();
        if (event) draft.removeAndReleaseUnusedExtensions(id, workspace);
        else draft.discardStatements(id, workspace);
        selected = null;
        changed.run();
        return true;
    }
    boolean release(double mx, double my, int button) {
        if (button != 0 || !active()) return false;
        // Mouse motion may be coalesced between press and release. Consume only the remainder.
        if (panning) viewport.pan(mx - pointerX, my - pointerY);
        pointerX = mx; pointerY = my; moved |= Math.hypot(mx - pressX, my - pressY) > 4;
        String id = pressed; var creating = prototype; var graphs = prototypeGraphs;var template=prototypeTemplate; boolean main = mainPressed, commit = moved && contains(mx, my);
        var position = position(); var joint = !creating.isEmpty() ? nearest(creating) : id == null ? null : nearest(draft.suffix(id));
        cancel();
        if (commit && (id != null || main || !creating.isEmpty())) {
            if(template!=null)selected=draft.placeTemplate(template,joint==null?null:joint.socket(),position);
            else if (!creating.isEmpty()) { draft.place(creating, joint == null ? null : joint.socket(), position, graphs); selected = creating.getFirst().sourceId(); }
            else if (main) draft.moveMain(position); else draft.drop(id, joint == null ? null : joint.socket(), position);
            if (!main) revealGroup(selected);
            changed.run();
        }
        return true;
    }
    boolean active() { return pressed != null || panning || mainPressed || !prototype.isEmpty(); }
    boolean inlineLabelHit(Slot slot, double mx, double my) {
        double scale = viewport.scale();
        return mx >= slot.x() + 3 * scale && mx < slot.x() + (measure(slot.label()) + 9) * scale
            && Math.abs(my - (slot.y() + slot.height() / 2)) < 8 * scale;
    }
    boolean cancel() { boolean active = active(); pressed = null; prototype = List.of(); prototypeGraphs = Map.of();prototypeTemplate=null; panning = mainPressed = moved = false; return active; }
    boolean scroll(double mx, double my, double sx, double sy, boolean zoom) {
        if (!contains(mx, my)) return false;
        cancel();
        if (zoom) viewport.zoomAt(mx - x, my - y, (int)Math.signum(sy)); else viewport.pan(-sx * ROW, sy * ROW);
        return true;
    }
    private ProgramDocument.Position position() {
        return new ProgramDocument.Position(viewport.worldX(pointerX - x) - offsetX, viewport.worldY(pointerY - y) - offsetY);
    }
    private Joint nearest(List<FlowProgram.Step> moved) {
        // Definition hats are roots, never an executable suffix to snap into another stack.
        if (!moved.isEmpty() && moved.getFirst() instanceof FlowProgram.Procedure) return null;
        var point = position(); var ids = new HashSet<String>(); moved.forEach(step -> ids.add(step.sourceId()));
        int wrapHeight = 0;
        if (FlowDraft.emptyWrapMouth(moved)) {
            var preview = new ArrayList<Block>();
            append(preview, moved, null, 0, 0);
            wrapHeight = preview.getFirst().height();
        }
        Joint best = null; double distance = Double.POSITIVE_INFINITY;
        for (var joint : joints()) {
            // A socket inside the carried suffix is not an insertion target.
            var steps = joint.socket().stack() == null ? draft.snapshot().steps() : draft.snapshot().canvas().detached().stream()
                .filter(stack -> stack.steps().getFirst().sourceId().equals(joint.socket().stack())).findFirst().orElseThrow().steps();
            if (steps.size() > 1 && steps.get(1) instanceof FlowProgram.Watch watch && (joint.socket().index() < 2 || watch.handler().isPresent())) continue;
            if (!steps.isEmpty() && steps.getFirst() instanceof FlowProgram.Procedure
                    && (joint.socket().index() == 0 || joint.socket().index() == steps.size())) continue;
            if (joint.socket().index() < steps.size() && ids.contains(steps.get(joint.socket().index()).sourceId())) continue;
            double dx = Math.abs(joint.x() - point.x()), dy = Math.abs(point.y() - Math.clamp(point.y(), joint.y(), joint.y() + joint.opening()));
            if (dx <= 28 && dy <= 14 && dx + dy < distance) { best = joint; distance = dx + dy; }
            int index = joint.socket().index();
            if (wrapHeight > 0 && index < steps.size() && FlowDraft.ordinaryStatement(steps.get(index))) {
                // The C's inner connector, not its top connector, aligns with the first captured statement.
                double left = joint.x() - INDENT;
                double top = joint.y() + joint.opening() - wrapHeight;
                double wx = Math.abs(point.x() - left), wy = Math.abs(point.y() - top);
                if (wx <= 12 && wy <= 8 && wx + wy < distance) {
                    best = new Joint(new FlowDraft.Socket(joint.socket().stack(), index, true), left, top, 0, wrapHeight);
                    distance = wx + wy;
                }
            }
        }
        return best;
    }
    void render(GuiGraphics g, Font font, Function<FlowProgram.Step, Component> label, Component mainLabel, Component parkedLabel) {
        ScratchArt.draw(g, ScratchArt.Part.CANVAS, x, y, width, height);
        g.enableScissor(x, y, x + width, y + height);
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale((float)viewport.scale(), (float)viewport.scale(), 1);
        g.pose().translate(viewport.worldX(0) * -1, viewport.worldY(0) * -1, 0);
        var origin = draft.snapshot().canvas().main();
        var main = moved && mainPressed ? position() : origin;
        if (conditionSource == null) FlowBlockSkin.block(g, (int)main.x(), (int)main.y(), WIDTH, HAT, Category.EVENTS, Shape.HAT, mainPressed, false);
        if (conditionSource == null) g.drawString(font, mainLabel, (int)main.x() + 8, (int)main.y() + 8, FlowBlockSkin.INK, false);
        if (conditionSource == null) for (var stack : visibleStacks(draft.snapshot())) {
            var annotation = stackAnnotation(stack, parkedLabel);
            if (annotation != null) g.drawString(font, annotation, (int)stack.position().x(),
                (int)stack.position().y() - 12, 0xffb5b8b3, false);
        }
        var blocks = blocks(); var carried = new HashSet<String>();
        if (moved && pressed != null) draft.suffix(pressed).forEach(step -> carried.add(step.sourceId()));
        var rails = new ArrayList<Category>();
        for (var block : blocks) {
            int bx = (int)block.x(), by = (int)block.y();
            if (block.stack() == null) { bx += (int)(main.x() - origin.x()); by += (int)(main.y() - origin.y()); }
            drawBlock(g, font, block, bx, by, label, carried.contains(block.step().sourceId()), rails);
        }
        if (moved && pressed != null) {
            var first = blocks.stream().filter(block -> block.step().sourceId().equals(pressed)).findFirst().orElseThrow();
            var joint = nearest(draft.suffix(pressed));
            var point = joint != null && joint.socket().wrap() ? new ProgramDocument.Position(joint.x(), joint.y()) : position();
            drawDropMarker(g, joint);
            rails.clear();
            for (var block : blocks) if (carried.contains(block.step().sourceId())) drawBlock(g, font, block,
                (int)(point.x() + block.x() - first.x()), (int)(point.y() + block.y() - first.y()), label, false, rails);
        }
        if (moved && !prototype.isEmpty()) {
            var joint = nearest(prototype);
            var point = joint != null && joint.socket().wrap() ? new ProgramDocument.Position(joint.x(), joint.y()) : position();
            drawDropMarker(g, joint);
            var preview = new ArrayList<Block>(); append(preview, prototype, null, point.x(), point.y());
            rails.clear();
            for (var block : preview) drawBlock(g, font, block, (int)block.x(), (int)block.y(), label, false, rails);
        }
        for (var reporter : draft.snapshot().canvas().values().parked())
            drawTile(g, font, tile(reporter), (int)reporter.position().x(), (int)reporter.position().y());
        g.pose().popPose(); g.disableScissor();
    }
    private static void drawDropMarker(GuiGraphics g, Joint joint) {
        if (joint == null) return;
        boolean wrap = joint.socket().wrap();
        int x = (int)joint.x() + (wrap ? INDENT : 0);
        int y = (int)joint.y() + joint.wrapHeight();
        g.fill(x, y - 2, x + WIDTH, y + 2, 0xff4c97ff);
        if (wrap) g.fill(x, y + 2, x + 3, y + 11, 0xff4c97ff);
    }

    Component stackAnnotation(FlowBlockLayout.Stack stack, Component parkedLabel) {
        var root = stack.steps().getFirst();
        // Event hats already name their trigger; only callable definitions use the procedure hint.
        if (eventHat(root) || draft.snapshot().canvas().terminalHats().containsKey(root.sourceId())
            || stack.steps().size() > 1 && stack.steps().get(1) instanceof FlowProgram.Watch) return null;
        return root instanceof FlowProgram.Procedure
            ? Component.translatable("create_biotech.biocomputing.flow_editor.procedure_definition_hint") : parkedLabel;
    }
    record ParkedSlot(int index, Slot slot) {}
    FlowProgram.Step statementAt(double mx, double my) {
        if (!contains(mx, my) || parkedHit(mx,my)>=0) return null;
        var block = hit(viewport.worldX(mx - x), viewport.worldY(my - y));
        if (block == null || block.step() instanceof FlowProgram.Procedure
                || FlowDraft.delimiter(block.step())) return null;
        return block.step();
    }
    ProgramDocument.Position pastePosition(double mx, double my) {
        if (!contains(mx, my)) return reporterPosition(x + width / 2d, y + height / 2d);
        return reporterPosition(Math.min(mx + 8, x + width - 24), Math.min(my + 8, y + height - 18));
    }
    ProgramDocument.Position reporterPosition(double mx, double my) {
        return new ProgramDocument.Position(viewport.worldX(mx - x), viewport.worldY(my - y));
    }
    ProgramDocument.Position displacedPosition(double mx, double my) {
        return reporterPosition(Math.clamp(mx + 12, x, x + width - 24), Math.clamp(my + 40, y, y + height - 18));
    }
    int parkedHit(double mx, double my) {
        if (!contains(mx, my)) return -1;
        var point = reporterPosition(mx, my);
        var parked = draft.snapshot().canvas().values().parked();
        for (int index = parked.size() - 1; index >= 0; index--) {
            var reporter = parked.get(index);
            var tile = tile(reporter);
            double dx = point.x() - reporter.position().x(), dy = point.y() - reporter.position().y();
            if (dx >= 0 && dy >= 0 && dx < tile.width() && dy < tile.height()) return index;
        }
        return -1;
    }
    ParkedSlot parkedSlotHit(double mx, double my) {
        int index = parkedHit(mx, my);
        if (index < 0) return null;
        var reporter = draft.snapshot().canvas().values().parked().get(index);
        var slots = new ArrayList<Slot>();
        slots(slots, null, tile(reporter), reporter.position().x(), reporter.position().y());
        for (int i = slots.size() - 1; i >= 0; i--)
            if (slots.get(i).contains(mx, my)) return new ParkedSlot(index, slots.get(i));
        return null;
    }
    void previewReporter(GuiGraphics g, Font font, ScratchValueLayout.Reporter reporter, double mx, double my) {
        g.enableScissor(x, y, x + width, y + height);
        g.pose().pushPose();
        g.pose().translate(mx, my, 0);
        g.pose().scale((float)viewport.scale(), (float)viewport.scale(), 1);
        drawTile(g, font, tile(reporter), 0, 0);
        g.pose().popPose();
        g.disableScissor();
    }
    private InlineGraphView.Tile tile(ScratchValueLayout.Reporter reporter) {
        var parameters = reporter.procedureScope().flatMap(scope -> FlowProcedureTools.owner(draft.snapshot(), scope.source())
            .filter(p -> p.key().equals(scope.key()))).map(p -> p.inputs().keySet()).orElse(Set.of());
        return InlineGraphView.node(reporter.graph(), reporter.graph().outputs().get("value"), this::measure,
            FlowProcedureTools.definitions(draft.snapshot()).stream().collect(java.util.stream.Collectors.toMap(FlowProgram.Procedure::key,p->p,(a,c)->a)),
            reporter.arguments(), parameters, procedureLayouts());
    }

    private void drawBlock(GuiGraphics g, Font font, Block block, int x, int y, Function<FlowProgram.Step, Component> label, boolean ghost, List<Category> rails) {
        if (block.height() == 0) return;
        if (block.step().sourceId().equals(conditionSource)) {
            drawTile(g, font, tile(visualGraph(block.step()), block.step()), x, y, Category.EVENTS);
            return;
        }
        var category = block.extensionColor() ? Category.EXTENSIONS : category(block.step());
        var shape = shape(block.step());
        while (rails.size() > block.depth()) rails.removeLast();
        while (rails.size() < block.depth()) rails.add(Category.CONTROL);
        for (int depth = 0; depth < rails.size(); depth++)
            FlowBlockSkin.rail(g, x - (block.depth() - depth) * INDENT, y - block.opening(), block.height() + block.opening(), rails.get(depth));
        if (block.opening() > 0) FlowBlockSkin.rail(g, x, y - block.opening(), block.opening(), category);
        if (opensBody(shape)) rails.add(category);
        FlowBlockSkin.block(g, x, y, block.width(), block.height(), category, shape, block.step().sourceId().equals(selected), ghost);
        int ink = FlowBlockSkin.ink(category, shape);
        if (block.step() instanceof FlowProgram.Procedure definition && shape == Shape.DEFINITION) {
            g.drawString(font, defineCaption(), x + 4, y + 18, ink, false);
            definitionPreview(definition).draw(g, font, x + definitionInset(), y + PROTOTYPE_Y);
            return;
        }
        if (block.step() instanceof FlowProgram.StopScript) {
            int mid = y + (block.height() - 8) / 2;
            g.drawString(font, font.plainSubstrByWidth(label.apply(block.step()).getString(), block.width() - 32), x + 8, mid, ink, false);
            g.drawString(font, "▾", x + block.width() - 17, mid, ink, false);
            return;
        }
        var broadcast = selectorChip(block.step());
        if (broadcast != null) {
            int mid = y + (block.height() - 8) / 2;
            g.drawString(font, broadcast.caption(), x + 8, mid, ink, false);
            FlowBlockSkin.block(g, x + broadcast.x(), y + (block.height() - CHANNEL_HEIGHT) / 2,
                broadcast.width(), CHANNEL_HEIGHT, category, Shape.LITERAL, false, ghost);
            g.drawString(font, broadcast.label(), x + broadcast.x() + 6, mid, 0xff172018, false);
            g.drawString(font, "▾", x + broadcast.x() + broadcast.width() - 11, mid, 0xff172018, false);
            g.drawString(font, broadcast.suffix(), x + broadcast.x() + broadcast.width() + 5, mid, ink, false);
            return;
        }
        if (block.step() instanceof FlowProgram.Watch watch) {
            var root = fixedHatTile(watch, visualGraph(watch));
            if (root == null) g.drawString(font, watchTitle(watch), x + 8, y + 9, ink, false);
            else {
                int top = y + fixedHatTop(block, root), left = x + 8;
                g.drawString(font, root.label(), left + root.labelX(), top + (root.height() - 8) / 2, ink, false);
                for (var child : root.children()) drawTile(g, font, child.tile(), left + child.x(), top + child.y(), category);
            }
            return;
        }
        var graph = visualGraph(block.step());
        var layout = callLayout(block.step(), graph);
        if (layout != null) {
            g.drawString(font, layout.title(), x + 8, y + (ROW - 8) / 2, ink, false);
            for (var phrase : layout.phrases())
                g.drawString(font, phrase.text(), x + phrase.x(), y + (ROW - 8) / 2, ink, false);
            for (var input : layout.arguments()) {
                g.drawString(font, input.label(), x + input.captionX(), y + input.y() + (input.tile().height() - 8) / 2, ink, false);
                drawTile(g, font, input.tile(), x + input.valueX(), y + input.y(), category);
            }
            return;
        }
        if (block.step() instanceof FlowProgram.Sleep sleep && editableWait()) {
            drawWaitInputs(g, font, waitLayout(sleep, graph), x, y, ink, ghost);
            return;
        }
        if (graph != null) {
            String target = assignmentTarget(block.step());
            int textY = y + (wrapped(block.step()) ? 7 : (block.height() - 8) / 2);
            if (target != null) {
                g.drawString(font, setPart("prefix"), x + 8, textY, ink, false);
                int tx = x + targetX(), ty = y + targetY(block), tw = targetWidth(target);
                FlowBlockSkin.block(g, tx, ty, tw, 18, category, Shape.LITERAL, false, ghost);
                g.drawString(font, target, tx + 6, textY, 0xff172018, false);
                g.drawString(font, setPart("suffix"), tx + tw + 6, textY, ink, false);
            } else g.drawString(font, inlineTitle(block.step()), x + 8, textY, ink, false);
            var tile = tile(graph, block.step());
            drawTile(g,font,tile,x+slotX(block.step()),y+valueY(block,tile),category); return;
        }
        if (block.height() >= ROW || shape == Shape.HAT)
            g.drawString(font, font.plainSubstrByWidth(label.apply(block.step()).getString(), block.width() - 18),
                x + 8, y + (shape == Shape.HAT ? 9 : 10), ink, false);
    }
    private record Argument(String label, int captionX, int valueX, int y, InlineGraphView.Tile tile) {}
    private record CallLayout(String title, int width, int height, List<Argument> arguments,
                              List<ScratchSignaturePreview.Phrase> phrases) {}
    private FlowDocumentBody labelSnapshot;
    private Map<ModuleDefinition.Key, ScratchProcedureLabel.Layout> layouts = Map.of();
    private Map<ModuleDefinition.Key, ScratchProcedureLabel.Layout> procedureLayouts() {
        var current = draft.snapshot();
        if (labelSnapshot != current) {
            var next = new HashMap<ModuleDefinition.Key, ScratchProcedureLabel.Layout>();
            for (var definition : FlowProcedureTools.definitions(current)) {
                if (ScratchExtensions.reserved(definition.key()) || eventHat(definition)) continue;
                var parts = current.canvas().procedureLabels().getOrDefault(definition.sourceId(), List.of());
                next.put(definition.key(), new ScratchProcedureLabel.Layout(List.copyOf(definition.inputs().keySet()), parts));
            }
            layouts = Map.copyOf(next); labelSnapshot = current;
        }
        return layouts;
    }

    /** The same rows drive painting, hitting and the connection beneath a call. */
    private ComputationGraph visualGraph(FlowProgram.Step step) {
        var preview = prototypeGraphs.get(step.sourceId());
        return preview != null ? preview : FlowInlineValues.graph(draft.snapshot(), step);
    }
    private CallLayout callLayout(FlowProgram.Step step, ComputationGraph graph) {
        if (graph == null) return null;
        Map<String, String> ports;
        ModuleDefinition.Key key;
        if (step instanceof FlowProgram.Invoke call && call.operands().isPresent()) {
            key = call.procedure(); ports = graph.outputs();
        } else {
            String root = ScratchExtensionMouth.predicate(step, graph);
            if (root == null) return null;
            var predicate = (ComputationGraph.ProcedureValue)graph.nodes().get(root);
            key = predicate.procedure(); ports = predicate.arguments();
        }
        String title = key.id() + " @ " + key.revision();
        if (ScratchExtensions.reserved(key)) {
            title = ScratchExtensions.binding(key).map(binding -> Component.translatable(binding.command().labelKey()).getString())
                .orElseGet(() -> Component.translatableWithFallback("create_biotech.biocomputing.flow_editor.extension_unavailable", "Missing extension").getString());
        }
        if (font != null) title = font.plainSubstrByWidth(title, WIDTH - 20);
        var definitions = FlowProcedureTools.definitions(draft.snapshot()).stream().collect(
            java.util.stream.Collectors.toMap(FlowProgram.Procedure::key, procedure -> procedure, (first, ignored) -> first));
        var arguments = new ArrayList<Argument>();
        int x = 8, y = ROW, rowHeight = 0, right = WIDTH;
        var parameters = parameterNames(step);
        var bindings = FlowInlineValues.bindings(step).arguments();
        var authored = procedureLayouts().get(key);
        var parts = authored == null ? List.<ScratchProcedureLabel>of() : authored.matching(ports.keySet());
        var order = authored == null ? List.copyOf(new TreeSet<>(ports.keySet())) : authored.order(ports.keySet());
        var phrases = new ArrayList<ScratchSignaturePreview.Phrase>();
        if (!parts.isEmpty()) { x = 14 + measure(title); y = 0; }
        for (int index = 0; index <= order.size(); index++) {
            for (var part : parts) if (part.before() == index) {
                phrases.add(new ScratchSignaturePreview.Phrase(part.text(), x)); x += measure(part.text()) + 6;
                right = Math.max(right, x + 2);
            }
            if (index == order.size()) break;
            var output = order.get(index);
            var tile = InlineGraphView.node(graph, ports.get(output), this::measure, definitions, bindings, parameters, procedureLayouts());
            if (tile.literal() && ScratchExtensions.reserved(key))
                tile = ScratchExtensionMenus.decorateReader(step, graph, tile, this::measure, readerCapture);
            String caption = font == null ? output : font.plainSubstrByWidth(output, 72);
            int label = measure(caption) + 5, span = label + tile.width() + 6;
            if (parts.isEmpty() && x > 8 && x + span > WIDTH) {
                y += rowHeight; x = 8; rowHeight = 0;
            }
            arguments.add(new Argument(caption, x, x + label, y + 2, tile));
            x += span;
            rowHeight = Math.max(rowHeight, tile.height() + 5);
            right = Math.max(right, x + 2);
        }
        return new CallLayout(title, right, Math.max(ROW, y + rowHeight + 4), List.copyOf(arguments), List.copyOf(phrases));
    }

    static Component watchTitle(FlowProgram.Watch watch) {
        return watch.handler().<Component>map(key ->
            Component.translatable("create_biotech.biocomputing.flow_editor.watch_condition", key.id()))
            .orElseGet(() -> Component.translatable("create_biotech.biocomputing.flow_editor.watch_hat"));
    }
    private boolean editableWait() { return draft.snapshot().execution() == FlowProgram.Execution.REGISTER_V1; }
    private static Component waitCaption() {
        return Component.translatableWithFallback("create_biotech.biocomputing.flow_editor.inline_sleep", "Wait");
    }
    private WaitLayout waitLayout(FlowProgram.Sleep sleep, ComputationGraph graph) {
        InlineGraphView.Tile value;
        if (graph != null) value = tile(graph, sleep);
        else {
            // Compatibility calls keep their explicit name; they are not copied into an anonymous graph.
            var expr = (FlowInput.Expression)sleep.ticks();
            String label = expr.entry() + "." + expr.output();
            if (font != null) label = font.plainSubstrByWidth(label, 112);
            value = new InlineGraphView.Tile(null, label, false, measure(label) + 16, 18, 8, List.of(), false, false, Category.OPERATORS);
        }
        return waitLayout(value, sleep.displayedUnit(), Math.max(ROW, value.height() + 8), this::measure);
    }
    private static WaitLayout waitLayout(InlineGraphView.Tile value, FlowProgram.WaitUnit selected, int height,
                                         java.util.function.ToIntFunction<String> measure) {
        var unit = FlowInlineValues.waitUnitLabel(selected);
        int start = 14 + measure.applyAsInt(waitCaption().getString());
        return new WaitLayout(value, start, unit, start + value.width() + 6, measure.applyAsInt(unit.getString()) + 22, height);
    }
    void styleWaitPrototype(CreateTextButton button, Font font) {
        var sleep = FlowBlockPalette.waitPrototype("wait_preview");
        var graph = FlowInlineValues.graph(draft.snapshot(), sleep);
        var layout = waitLayout(InlineGraphView.layout(graph, font::width), sleep.displayedUnit(), button.getHeight(), font::width);
        FlowBlockSkin.prototype(button, font, Category.CONTROL, Shape.STACK);
        button.setIcon((g, ignoredX, ignoredY) -> {
            float scale = Math.min(1f, (float)button.getWidth() / layout.width());
            g.pose().pushPose();
            try {
                g.pose().translate(button.getX(), button.getY() + (button.getHeight() - 3 - layout.height() * scale) / 2, 0);
                g.pose().scale(scale, scale, 1);
                drawWaitInputs(g, font, layout, 0, 0, FlowBlockSkin.INK, false);
            } finally {
                g.pose().popPose();
            }
        });
    }
    private static void drawWaitInputs(GuiGraphics g, Font font, WaitLayout layout, int x, int y, int ink, boolean ghost) {
        int mid = y + (layout.height() - 8) / 2;
        g.drawString(font, waitCaption(), x + 8, mid, ink, false);
        drawTile(g, font, layout.value(), x + layout.valueX(), y + layout.valueY(), Category.CONTROL);
        FlowBlockSkin.block(g, x + layout.unitX(), y + layout.unitY(), layout.unitWidth(), CHANNEL_HEIGHT,
            Category.CONTROL, Shape.LITERAL, false, ghost);
        g.drawString(font, layout.unit(), x + layout.unitX() + 6, mid, FlowBlockSkin.INK, false);
        g.drawString(font, "▾", x + layout.unitX() + layout.unitWidth() - 11, mid, FlowBlockSkin.INK, false);
    }

    private static Component inlineTitle(FlowProgram.Step step) {
        if (step instanceof FlowProgram.Watch watch) return watchTitle(watch);
        if (step instanceof FlowProgram.Sleep sleep && sleep.unit().isPresent())
            return Component.translatable("create_biotech.biocomputing.flow_editor.inline_wait_duration",
                FlowInlineValues.waitUnitLabel(sleep.displayedUnit()));
        return step instanceof FlowProgram.Evaluate call
                ? evaluationTitle(call)
                : step instanceof FlowProgram.Shared shared
                ? Component.translatable("create_biotech.biocomputing.flow_editor.inline_public_" + (shared.add() ? "add" : "set"), shared.cell())
                : step instanceof FlowProgram.Reference ref && ref.kind()==FlowProgram.ReferenceKind.WRITE
                ? Component.translatable("create_biotech.biocomputing.flow_editor.inline_ref_write",ref.target())
                : Component.translatable("create_biotech.biocomputing.flow_editor.inline_" + (step instanceof FlowProgram.Ask ? "ask" : step instanceof FlowProgram.Repeat ? "repeat" : step instanceof FlowProgram.WaitUntil ? "wait_until" : step instanceof FlowProgram.Until ? "until" : step instanceof FlowProgram.Sleep ? "sleep" : "if"));
    }
    private static Component evaluationTitle(FlowProgram.Evaluate call) {
        var target = call.bindings().orElseThrow().returns().get("value");
        return target == null ? Component.translatable("create_biotech.biocomputing.flow_editor.inline_discard")
            : Component.translatable("create_biotech.biocomputing.flow_editor.inline_set", target);
    }
    record Slot(String call, String node, String label, double x, double y, double width, double height, boolean target) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    }
    List<Slot> inlineSlots() {
        var slots = new ArrayList<Slot>();
        for (var block : blocks()) {
            var step=block.step(); var graph=FlowInlineValues.graph(draft.snapshot(),step); if(graph==null) continue;
            if (step instanceof FlowProgram.Sleep sleep && editableWait()) {
                var wait = waitLayout(sleep, graph);
                slots(slots, sleep.sourceId(), wait.value(), block.x() + wait.valueX(), block.y() + wait.valueY());
                continue;
            }
            if (step.sourceId().equals(conditionSource)) {
                slots(slots, step.sourceId(), tile(graph, step), block.x(), block.y()); continue;
            }
            if (step instanceof FlowProgram.Watch watch) {
                var root = fixedHatTile(step, graph);
                if (root != null && ScratchExtensionMouth.missingWatch(watch, graph).isEmpty()) {
                    double top = block.y() + fixedHatTop(block, root);
                    for (var child : root.children()) slots(slots, step.sourceId(), child.tile(), block.x()+8+child.x(), top+child.y());
                }
                continue;
            }
            var layout = callLayout(step, graph);
            if (layout != null) {
                for (var input : layout.arguments())
                    slots(slots, step.sourceId(), input.tile(), block.x() + input.valueX(), block.y() + input.y());
                continue;
            }
            if (step instanceof FlowProgram.Evaluate call) {
                var target = call.bindings().orElseThrow().returns().get("value");
                if (target != null) slots.add(slot(call.sourceId(),null,target,block.x()+targetX(),block.y()+targetY(block),targetWidth(target),18,true));
            }
            var tile = tile(graph, step);
            slots(slots,step.sourceId(),tile,block.x()+slotX(step),block.y()+valueY(block,tile));
        }
        return slots;
    }
    Block blockAt(double mx,double my) {
        return contains(mx,my)?hit(viewport.worldX(mx-x),viewport.worldY(my-y)):null;
    }
    Slot inlineHit(double mx, double my) {
        if (!contains(mx, my) || parkedHit(mx,my) >= 0) return null;
        // Empty body space is opaque too: only the topmost visible block can own a slot here.
        var visible = hit(viewport.worldX(mx - x), viewport.worldY(my - y));
        if (visible == null) return null;
        var slots = inlineSlots();
        for (int i = slots.size() - 1; i >= 0; i--) {
            var slot = slots.get(i);
            if (slot.call().equals(visible.step().sourceId()) && slot.contains(mx, my)) return slot;
        }
        return null;
    }
    private Slot slot(String call, String node, String label, double wx, double wy, int width, int height, boolean target) {
        return new Slot(call, node, label, x + viewport.screenX(wx), y + viewport.screenY(wy), width * viewport.scale(), height * viewport.scale(), target);
    }
    private void slots(List<Slot> slots, String call, InlineGraphView.Tile tile, double x, double y) {
        if (tile.node() != null) slots.add(slot(call, tile.node(), tile.label(), x, y, tile.width(), tile.height(), false));
        for (var child : tile.children()) slots(slots, call, child.tile(), x + child.x(), y + child.y());
    }
    private Set<String> parameterNames(FlowProgram.Step step) {
        return FlowProcedureTools.owner(draft.snapshot(), step.sourceId()).map(p -> p.inputs().keySet()).orElse(Set.of());
    }
    private InlineGraphView.Tile fixedHatTile(FlowProgram.Step step, ComputationGraph graph) {
        if (!(step instanceof FlowProgram.Watch) || conditionSource != null) return null;
        var predicate = ScratchExtensionMouth.predicate(step, graph);
        return predicate == null ? null : tile(graph, step, predicate);
    }
    private static int fixedHatTop(Block block, InlineGraphView.Tile root) { return (block.height() - root.height()) / 2 + 3; }
    private InlineGraphView.Tile tile(ComputationGraph graph, FlowProgram.Step step) {
        return tile(graph, step, graph.outputs().get("value"));
    }
    private InlineGraphView.Tile tile(ComputationGraph graph, FlowProgram.Step step, String root) {
        return InlineGraphView.node(graph, root, this::measure, FlowProcedureTools.definitions(draft.snapshot()).stream()
            .collect(java.util.stream.Collectors.toMap(FlowProgram.Procedure::key, p -> p, (a, b) -> a)),
            FlowInlineValues.bindings(step).arguments(), parameterNames(step), procedureLayouts());
    }
    private BroadcastChip selectorChip(FlowProgram.Step step) {
        if (step instanceof FlowProgram.Monitor monitor) return monitorChip(monitor);
        if(step instanceof FlowProgram.Procedure p && p.terminalKey().isPresent()) {
            var caption=Component.translatableWithFallback("create_biotech.biocomputing.flow_editor.terminal_key_caption","When key");
            String label=keyLabel(p.terminalKey().orElseThrow()).getString();
            return new BroadcastChip(caption,label,Component.empty(),14+measure(caption.getString()),Math.max(26,measure(label)+20));
        }
        return broadcastChip(step);
    }
    static Component keyLabel(String key) {
        return key.length()==1?Component.literal(key):Component.translatableWithFallback("create_biotech.biocomputing.terminal.key_"+key,key);
    }
    private BroadcastChip monitorChip(FlowProgram.Monitor monitor) {
        var caption = Component.translatable("create_biotech.biocomputing.flow_editor.add_monitor_" + (monitor.visible() ? "show" : "hide"));
        String label = Component.translatable("create_biotech.biocomputing.flow_editor."
            + (monitor.privateLocal() ? "monitor_private_target" : "monitor_target"), monitor.cell()).getString();
        if (measure(label) > 100 && font != null) label = font.plainSubstrByWidth(label, 92) + "…";
        return new BroadcastChip(caption, label, Component.empty(), 14 + measure(caption.getString()), measure(label) + 20);
    }
    private BroadcastChip broadcastChip(FlowProgram.Step step) {
        String channel, mode;
        if (step instanceof FlowProgram.Broadcast send) {
            channel = send.channel(); mode = send.await() ? "wait" : "send";
        } else if (step instanceof FlowProgram.Procedure receiver && receiver.event().isPresent()) {
            channel = receiver.event().orElseThrow(); mode = "receive";
        } else return null;
        String prefix = "create_biotech.biocomputing.flow_editor.broadcast_caption_";
        var caption = Component.translatable(prefix + mode);
        var suffix = mode.equals("wait") ? Component.translatable(prefix + "suffix") : Component.empty();
        String label = measure(channel) > 100 && font != null ? font.plainSubstrByWidth(channel, 92) + "…" : channel;
        return new BroadcastChip(caption, label, suffix, 14 + measure(caption.getString()), Math.max(26, measure(label) + 20));
    }
    InlineGraphEdits.ProcedureParameter parameterAt(double mx, double my) {
        var block = blockAt(mx, my);
        if (block == null || parkedHit(mx, my) >= 0 || !(block.step() instanceof FlowProgram.Procedure definition)
                || shape(definition) != Shape.DEFINITION) return null;
        double dx = viewport.worldX(mx - x) - block.x() - definitionInset();
        double dy = viewport.worldY(my - y) - block.y() - PROTOTYPE_Y;
        for (var chip : definitionPreview(definition).chips()) if (chip.contains(dx, dy)) {
            var name = chip.parameter().name();
            return new InlineGraphEdits.ProcedureParameter(new ScratchValueLayout.ProcedureScope(definition.sourceId(), definition.key()),
                name, definition.inputs().get(name));
        }
        return null;
    }
    private static Component defineCaption() { return Component.translatable("create_biotech.biocomputing.flow_editor.procedure_define_caption"); }
    private int definitionInset() { return 10 + measure(defineCaption().getString()); }
    private ScratchSignaturePreview definitionPreview(FlowProgram.Procedure definition) {
        var parameters = definition.inputs().entrySet().stream().map(port ->
            new ScratchSignaturePreview.Parameter(port.getKey(), port.getValue() == DataType.Scalar.BOOLEAN)).toList();
        return ScratchSignaturePreview.layout(font, definition.key().id() + " @ " + definition.key().revision(), parameters,
            draft.snapshot().canvas().procedureLabels().getOrDefault(definition.sourceId(), List.of()));
    }
    private int measure(String text) { return font == null ? text.length() * 6 : font.width(text); }
    private static Component setPart(String part) { return Component.translatable("create_biotech.biocomputing.flow_editor.inline_set_" + part); }
    private static String assignmentTarget(FlowProgram.Step step) {
        return step instanceof FlowProgram.Evaluate call ? call.bindings().orElseThrow().returns().get("value") : null;
    }
    private int targetX() { return 8 + measure(setPart("prefix").getString()) + 6; }
    private int targetWidth(String target) { return measure(target) + 12; }
    private int inlineX(FlowProgram.Step step) {
        String target = assignmentTarget(step);
        return target == null ? 16 + measure(inlineTitle(step).getString())
            : targetX() + targetWidth(target) + 6 + measure(setPart("suffix").getString()) + 8;
    }
    // On narrow screens keep at least the first value slot reachable without horizontal panning.
    // Zoom does not trigger reflow: the same world-space gesture must retain its target.
    private boolean wrapped(FlowProgram.Step step) { return inlineX(step) + 24 + 2 * INDENT > width; }
    private int slotX(FlowProgram.Step step) { return wrapped(step) ? 8 : inlineX(step); }
    private int targetY(Block block) { return wrapped(block.step()) ? 3 : (block.height() - 18) / 2; }
    private int valueY(Block block, InlineGraphView.Tile tile) { return wrapped(block.step()) ? 24 : (block.height() - tile.height()) / 2; }
    static void drawTile(GuiGraphics g, Font font, InlineGraphView.Tile tile, int x, int y) {
        drawTile(g, font, tile, x, y, tile.category());
    }
    private static void drawTile(GuiGraphics g, Font font, InlineGraphView.Tile tile, int x, int y, Category parent) {
        var category = tile.literal() ? parent : tile.category();
        var shape = tile.literal() ? (tile.bool() ? Shape.BOOLEAN_LITERAL : Shape.LITERAL)
            : tile.bool() ? Shape.BOOLEAN : Shape.REPORTER;
        FlowBlockSkin.block(g, x, y, tile.width(), tile.height(), category, shape, false, false);
        int ink = FlowBlockSkin.ink(category, shape);
        for (var phrase : tile.phrases()) g.drawString(font, phrase.text(), x + phrase.x(), y + (tile.height() - 8) / 2, ink, false);
        g.drawString(font, tile.label(), x + tile.labelX(), y + (tile.height() - 8) / 2, ink, false);
        if (tile.positionMenu()) g.drawString(font, "▾", x + tile.width() - tile.height() + 4, y + (tile.height() - 8) / 2, ink, false);
        for (var child : tile.children()) drawTile(g, font, child.tile(), x + child.x(), y + child.y(), category);
    }
    private static boolean eventHat(FlowProgram.Step step) {
        return step instanceof FlowProgram.Watch || step instanceof FlowProgram.Procedure p && (p.event().isPresent() || p.terminalKey().isPresent() || p.startup());
    }
    private static Shape shape(FlowProgram.Step step) {
        if (eventHat(step)) return Shape.HAT;
        if (step instanceof FlowProgram.Procedure) return Shape.DEFINITION;
        if (step instanceof FlowProgram.StopScript stop && stop.cap()) return Shape.CAP;
        if (step instanceof FlowProgram.Otherwise) return Shape.MIDDLE;
        if (step instanceof FlowProgram.If || step instanceof FlowProgram.Repeat
            || step instanceof FlowProgram.Until || step instanceof FlowProgram.Forever || step instanceof FlowProgram.Atomic) return Shape.MOUTH;
        return FlowDraft.delimiter(step) ? Shape.FOOT : Shape.STACK;
    }
    private static boolean opensBody(Shape shape) { return shape == Shape.MOUTH || shape == Shape.MIDDLE; }
    private static Category category(FlowProgram.Step step) {
        if (eventHat(step) || step instanceof FlowProgram.Broadcast) return Category.EVENTS;
        if (step instanceof FlowProgram.Procedure definition && ScratchExtensions.reserved(definition.key())
            || step instanceof FlowProgram.Invoke call && ScratchExtensions.reserved(call.procedure())) return Category.EXTENSIONS;
        if (step instanceof FlowProgram.Procedure || step instanceof FlowProgram.Invoke || step instanceof FlowProgram.TryLaunch || step instanceof FlowProgram.TaskStart
            || step instanceof FlowProgram.TaskJoin || step instanceof FlowProgram.EndProcedure) return Category.CUSTOM;
        if (step instanceof FlowProgram.Evaluate || step instanceof FlowProgram.Shared || step instanceof FlowProgram.Reference || step instanceof FlowProgram.Monitor) return Category.VARIABLES;
        if (step instanceof FlowProgram.Ask || step instanceof FlowProgram.TimerReset) return Category.SENSING;
        if (step instanceof FlowProgram.Atomic || step instanceof FlowProgram.EndAtomic || step instanceof FlowProgram.Memory) return Category.STORAGE;
        if (step instanceof FlowProgram.SerialSend || step instanceof FlowProgram.SerialReceive || step instanceof FlowProgram.SerialStatus
            || step instanceof FlowProgram.Correlation || step instanceof FlowProgram.Read || step instanceof FlowProgram.Write
            || step instanceof FlowProgram.AwaitReadable || step instanceof FlowProgram.XBusStatus || step instanceof FlowProgram.SignalRead
            || step instanceof FlowProgram.SignalWrite) return Category.COMMUNICATION;
        return Category.CONTROL;
    }
}
