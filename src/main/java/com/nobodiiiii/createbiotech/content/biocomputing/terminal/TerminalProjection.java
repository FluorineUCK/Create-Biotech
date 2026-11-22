package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;

/** Iterative read-only projection. Only visible repeat rows consume view budget. */
public final class TerminalProjection {
    private TerminalProjection() {}
    public record View(String instance, String node, TerminalDefinition.Node definition,
                       Optional<DataValue> value, List<View> children, Optional<Page> page, Optional<Problem> problem,
                       Optional<ResolvedButton> button, Optional<ResolvedInput> input) {
        public View {
            Objects.requireNonNull(instance); Objects.requireNonNull(node); Objects.requireNonNull(definition);
            Objects.requireNonNull(value); Objects.requireNonNull(page); Objects.requireNonNull(problem); Objects.requireNonNull(button); Objects.requireNonNull(input);
            children = List.copyOf(children);
        }
        public View(String instance, String node, TerminalDefinition.Node definition, Optional<DataValue> value, List<View> children, Optional<Page> page, Optional<Problem> problem, Optional<ResolvedButton> button) { this(instance, node, definition, value, children, page, problem, button, Optional.empty()); }
    }
    public record Page(int index, int count, int totalItems) {}
    public record Problem(String code, String detail) {
        public Problem { Objects.requireNonNull(code); Objects.requireNonNull(detail); }
    }
    public record ResolvedButton(String title, boolean enabled, DataValue.RecordValue inputs) {}
    public record ResolvedInput(String text, boolean enabled, DataValue.RecordValue inputs) {}
    public static View project(TerminalDefinition definition, DataValue.RecordValue roots,
                               Map<String, Integer> pages, int rowsPerPage, int maxViews) {
        return project(definition, roots, pages, rowsPerPage, maxViews, null);
    }
    /** Installed observations distinguish an unpublished output from an invalid value path. */
    public static View project(TerminalStatusView snapshot, Map<String, Integer> pages, int rowsPerPage, int maxViews) {
        return project(snapshot.terminal().orElseThrow(), snapshot.roots(), pages, rowsPerPage, maxViews,
            snapshot.unavailableOutput().orElse(null));
    }
    private static View project(TerminalDefinition definition, DataValue.RecordValue roots,
                                Map<String, Integer> pages, int rowsPerPage, int maxViews, Problem unavailableOutput) {
        Objects.requireNonNull(definition); Objects.requireNonNull(roots); Objects.requireNonNull(pages);
        if (rowsPerPage <= 0 || maxViews <= 0) throw new IllegalArgumentException("Projection page size and view budget must be positive");
        var stack = new ArrayDeque<Frame>();
        int used = 1;
        stack.push(new Frame(segment(definition.root()), definition.root(), definition.nodes().get(definition.root()), roots, pages, rowsPerPage, unavailableOutput));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.hasNext()) {
                // Reserve before resolving a child or constructing its view, including error views.
                if (used == maxViews) throw new IllegalArgumentException("Terminal projection exceeds maxViews: " + maxViews);
                used++;
                String child;
                String instance;
                DataValue.RecordValue childRoots = frame.roots;
                if (frame.node instanceof TerminalDefinition.Repeat repeat) {
                    int row = frame.next++;
                    child = repeat.template();
                    instance = frame.instance + "/" + segment(child) + "[" + row + "]";
                    var fields = new LinkedHashMap<>(frame.roots.fields());
                    fields.put("row", frame.items.get(row));
                    childRoots = new DataValue.RecordValue(fields);
                } else {
                    child = frame.childIds.get(frame.next++);
                    instance = frame.instance + "/" + segment(child);
                }
                stack.push(new Frame(instance, child, definition.nodes().get(child), childRoots, pages, rowsPerPage, unavailableOutput));
            } else {
                View view = frame.finish();
                stack.pop();
                if (stack.isEmpty()) return view;
                stack.peek().children.add(view);
            }
        }
        throw new IllegalStateException("Missing terminal root projection");
    }
    // Length-prefixed ids plus explicit absolute row index make special ids/nested pages unambiguous.
    private static String segment(String id) { return id.length() + ":" + id; }
    private static final class Frame {
        final String instance;
        final String id;
        final TerminalDefinition.Node node;
        final DataValue.RecordValue roots;
        final List<View> children = new ArrayList<>();
        List<String> childIds = List.of();
        List<DataValue> items = List.of();
        DataValue value;
        Page page;
        Problem problem;
        ResolvedButton button;
        ResolvedInput input;
        int next;
        int end;
        Frame(String instance, String id, TerminalDefinition.Node node, DataValue.RecordValue roots,
              Map<String, Integer> pages, int rowsPerPage, Problem unavailableOutput) {
            this.instance = instance; this.id = id; this.node = node; this.roots = roots;
            if (node instanceof TerminalDefinition.Button control) {
                try { button = TerminalInteraction.resolveButton(control, roots, unavailableOutput); }
                catch (TerminalInteraction.BindingException error) { problem = new Problem(error.code(), error.getMessage()); }
                return;
            }
            if (node instanceof TerminalDefinition.Input control) {
                try { input = TerminalInteraction.resolveInput(control, roots, unavailableOutput); }
                catch (TerminalInteraction.BindingException error) { problem = new Problem(error.code(), error.getMessage()); }
                return;
            }
            TerminalBinding binding = switch (node) {
                case TerminalDefinition.Text text -> text.value();
                case TerminalDefinition.Item item -> item.value();
                case TerminalDefinition.Repeat repeat -> repeat.items();
                default -> null;
            };
            if (binding == null) {
                childIds = TerminalDefinition.children(node);
                end = childIds.size();
                return;
            }
            if (unavailableOutput != null && binding instanceof TerminalBinding.Path path && path.path().input().equals("output")) {
                problem = unavailableOutput;
                return;
            }
            try { value = binding.resolve(roots); }
            catch (ValuePath.PathException error) {
                String code = switch (error.failure()) {
                    case TYPE_MISMATCH -> "type_mismatch";
                    case INDEX_OUT_OF_BOUNDS -> "index_out_of_bounds";
                };
                problem = new Problem(code, error.getMessage());
                return;
            } catch (ComputationException error) {
                if (error.code() != ComputationException.Code.MISSING_FIELD) throw error;
                problem = new Problem("missing_field", error.location() + ": " + error.getMessage());
                return;
            }
            if (node instanceof TerminalDefinition.Item && !validItem(value)) {
                problem = new Problem("invalid_item", "Item requires a record with text item and non-negative integer count");
            } else if (node instanceof TerminalDefinition.Repeat) {
                if (!(value instanceof DataValue.ListValue list)) {
                    problem = new Problem("type_mismatch", "Repeat requires a list");
                    return;
                }
                items = list.values();
                int count = (int) Math.max(1, ((long) items.size() + rowsPerPage - 1) / rowsPerPage);
                int requested = pages.getOrDefault(instance, 0);
                int index = Math.clamp(requested, 0, count - 1);
                page = new Page(index, count, items.size());
                next = (int) ((long) index * rowsPerPage);
                end = (int) Math.min(items.size(), (long) next + rowsPerPage);
            }
        }
        boolean hasNext() { return next < end; }
        View finish() {
            return new View(instance, id, node, Optional.ofNullable(value), children, Optional.ofNullable(page), Optional.ofNullable(problem), Optional.ofNullable(button), Optional.ofNullable(input));
        }
    }
    private static boolean validItem(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) return false;
        return record.fields().get("item") instanceof DataValue.TextValue
            && record.fields().get("count") instanceof DataValue.IntegerValue count && count.value() >= 0;
    }
}
