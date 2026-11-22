package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Bounded local debugger projections. The terminal and player programs never consume these observations. */
public final class TaskDebugView {
    public static final int PAGE_SIZE = 5;

    sealed interface Request permits PageRequest, TaskRequest {}
    record PageRequest(int page, int size) implements Request {
        PageRequest {
            if (page < 0 || size < 1 || size > PAGE_SIZE) throw new IllegalArgumentException("Invalid task page");
        }
    }
    record TaskRequest(long view, int row) implements Request {
        TaskRequest {
            if (view <= 0 || row < 0 || row >= PAGE_SIZE) throw new IllegalArgumentException("Invalid task selection");
        }
    }
    record Listing(DataValue.RecordValue value, List<UUID> ids) {}

    private TaskDebugView() {}

    public static DataValue.RecordValue pageRequest(int page) {
        return pageRequest(page, PAGE_SIZE);
    }
    static DataValue.RecordValue pageRequest(int page, int size) {
        var request = new PageRequest(page, size);
        return new DataValue.RecordValue(Map.of("page", number(request.page()), "size", number(request.size())));
    }
    public static DataValue.RecordValue taskRequest(long view, int row) {
        var request = new TaskRequest(view, row);
        return new DataValue.RecordValue(Map.of("view", number(request.view()), "row", number(request.row())));
    }
    static Request decode(DataValue.RecordValue value) {
        if (value.fields().keySet().equals(Set.of("page", "size")) && value.field("page") instanceof DataValue.IntegerValue page
                && value.field("size") instanceof DataValue.IntegerValue size && size.value() >= 1 && size.value() <= PAGE_SIZE
                && page.value() >= 0 && page.value() <= Integer.MAX_VALUE) return new PageRequest((int)page.value(), (int)size.value());
        if (value.fields().keySet().equals(Set.of("view", "row")) && value.field("view") instanceof DataValue.IntegerValue view
                && value.field("row") instanceof DataValue.IntegerValue row && row.value() >= 0 && row.value() < PAGE_SIZE)
            return new TaskRequest(view.value(), (int)row.value());
        throw new IllegalArgumentException("Invalid task debugger request");
    }

    static Listing directory(FlowBlockHost.Saved saved, PageRequest request) {
        var contexts = saved.tasks().contexts();
        int size = request.size(), pages = Math.max(1, Math.ceilDiv(contexts.size(), size));
        int page = Math.min(request.page(), pages - 1), from = page * size;
        var rows = new ArrayList<DataValue>(PAGE_SIZE);
        var ids = new ArrayList<UUID>(PAGE_SIZE);
        for (var task : contexts.subList(from, from + Math.min(size, contexts.size() - from))) {
            rows.add(new DataValue.RecordValue(summary(task)));
            ids.add(task.flow());
        }
        return new Listing(new DataValue.RecordValue(Map.of("debug_tasks", new DataValue.RecordValue(Map.of(
            "page", number(page), "pages", number(pages), "rows", new DataValue.ListValue(rows))))), List.copyOf(ids));
    }

    static Optional<DataValue.RecordValue> task(FlowBlockHost.Saved saved, UUID id) {
        return saved.tasks().contexts().stream().filter(task -> task.flow().equals(id)).findFirst().map(task -> {
            var fields = summary(task);
            var program = saved.program();
            if (task.cursor() >= 0 && task.cursor() < program.steps().size())
                fields.put("source", new DataValue.TextValue(program.steps().get(task.cursor()).sourceId()));
            task.assembly().flatMap(state -> state.calls()).filter(calls -> !calls.frames().isEmpty())
                .ifPresent(calls -> fields.put("call_stack", ComputerStatusView.callStack(program.steps(), calls.frames(), task.cursor())));
            fields.put("work", number(task.work()));
            if (task.sleepTicks() > 0) fields.put("sleep_ticks", number(task.sleepTicks()));
            task.diagnostic().ifPresent(error -> fields.put("diagnostic", new DataValue.RecordValue(Map.of(
                "code", new DataValue.TextValue(error.code().name()), "location", new DataValue.TextValue(error.location()),
                "message", new DataValue.TextValue(error.message())))));
            if (program.usesPrivateMonitors()) fields.put("private_monitors", FlowBlockHost.monitorValue(saved.privateMonitors().visible(task)));
            fields.put("locals", LocalReferences.describeRoots(locals(program, task)));
            return new DataValue.RecordValue(Map.of("debug_task", new DataValue.RecordValue(fields)));
        });
    }
    private static DataValue.RecordValue locals(FlowProgram program, FlowRuntime.Checkpoint task) {
        return program.compilation() == FlowProgram.Compilation.SOFTWARE_V1 && task.assembly().isPresent()
            ? program.displayTaskLocals(task.entry().orElseThrow(), task.assembly().orElseThrow()) : task.locals();
    }
    private static LinkedHashMap<String, DataValue> summary(FlowRuntime.Checkpoint task) {
        var entry = task.entry().orElseThrow();
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("procedure", new DataValue.TextValue(entry.id()));
        fields.put("revision", number(entry.revision()));
        fields.put("phase", new DataValue.TextValue(task.cancelled() ? "CANCELLED" : task.status().name()));
        fields.put("paused", new DataValue.BooleanValue(task.paused()));
        return fields;
    }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
