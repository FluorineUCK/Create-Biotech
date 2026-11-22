package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.*;
import java.util.*;
import net.minecraft.network.chat.Component;

/** One searchable description per existing editor prototype; never an execution registry. */
final class FlowBlockCatalog {
    enum SensingSection {
        INPUT, LINK, OUTPUT;
        String key() { return "sensing_" + name().toLowerCase(Locale.ROOT); }
        String exampleTag() { return "sensing:" + name().toLowerCase(Locale.ROOT); }
        static SensingSection from(com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.SensingRole role) {
            return switch (role) {
                case NONE -> null;
                case INPUT -> INPUT;
                case LINK -> LINK;
                case OUTPUT -> OUTPUT;
            };
        }
    }
    record Entry(String id, Category category, String group, Component label, Kind command, InlineGraphEdits.Source value,
            FlowProgram.Procedure procedure, boolean taskStart) {
        Entry(String id, Category category, String group, Component label, Kind command, InlineGraphEdits.Source value, FlowProgram.Procedure procedure) {
            this(id, category, group, label, command, value, procedure, false);
        }
        Entry(String id, Category category, String group, Component label, Kind command, InlineGraphEdits.Source value) {
            this(id, category, group, label, command, value, null);
        }
        Entry {
            if ((command == null ? 0 : 1) + (value == null ? 0 : 1) + (procedure == null ? 0 : 1) != 1)
                throw new IllegalArgumentException("One prototype source required");
        }
        SensingSection sensingSection() {
            if (id.startsWith("extension:")) {
                var key=procedure!=null?procedure.key():((InlineGraphEdits.ProcedureResult)value).procedure().key();
                return com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(key)
                    .map(b->SensingSection.from(b.command().sensingRole())).orElse(null);
            }
            if(command == Kind.TIMER_RESET || value instanceof InlineGraphEdits.Timer || command == Kind.ASK || value instanceof InlineGraphEdits.Answer || value instanceof InlineGraphEdits.KeyDown)return SensingSection.INPUT;
            if(category==Category.READERS)return SensingSection.INPUT;
            if(category==Category.OUTPUT)return SensingSection.OUTPUT;
            if(category==Category.COMMUNICATION)return switch(command) {
                case SIGNAL_READ -> SensingSection.INPUT;
                case SIGNAL_WRITE -> SensingSection.OUTPUT;
                default -> SensingSection.LINK;
            };
            return null;
        }
        boolean localCall() { return id.startsWith("bind:"); }
        boolean tryLaunch() { return id.startsWith("try_launch:"); }
        boolean taskJoin() { return id.startsWith("join:"); }
        boolean extensionHat() {
            return id.startsWith("extension:") && procedure != null && com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(procedure.key())
                .map(binding -> binding.command().shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.HAT).orElse(false);
        }
        int extensionBodies() {
            return procedure == null || !id.startsWith("extension:") ? 0
                : com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(procedure.key())
                    .map(binding -> binding.command().shape().bodyCount()).orElse(0);
        }
        boolean template() { return id.startsWith("template:") || id.startsWith("extension:"); }
        boolean advanced() { return category == Category.STORAGE; }
        Component help() { return text("catalog_help", text("category_" + category.name().toLowerCase(Locale.ROOT)), text("group_" + group), label,
            text(value instanceof InlineGraphEdits.Operator edge && edge.operation().numericEdge() ? (edge.operation().retainedEdge() ? "sample_edge_hint" : "edge_hint") : value instanceof InlineGraphEdits.RandomRange range ? (range.decimal() ? "random_decimal_hint" : "random_integer_hint") : id.equals("mathop") ? "mathop_hint" : localCall() ? "procedure_bind_invoke_hint" : command == Kind.TIMER_RESET || value instanceof InlineGraphEdits.Timer ? "timer_hint" : command == Kind.ASK || value instanceof InlineGraphEdits.Answer ? "ask_hint" : value instanceof InlineGraphEdits.KeyDown?"key_down_hint":command==Kind.STARTUP?"startup_hat_hint":command==Kind.TERMINAL_KEY?"terminal_key_hint":command==Kind.RECEIVE_BROADCAST?"broadcast_receive_hint":command==Kind.BROADCAST_WAIT?"broadcast_wait_hint":command==Kind.BROADCAST?"broadcast_hint":template() ? (group.equals("text") ? "text_next_template_hint" : "recipe_template_hint") : value instanceof InlineGraphEdits.Operator op && op.operation() == com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation.REQUIRE_VALUE ? "require_value_hint" : tryLaunch() ? "try_launch_hint" : taskJoin() ? "task_join_hint" : taskStart ? "task_start_hint" : command == Kind.ATOMIC ? "atomic_hint" : command == Kind.STOP_SCRIPT ? "stop_script_hint" : command == Kind.WAIT_UNTIL ? "wait_until_hint" : command == Kind.XBUS_STATUS ? "xbus_status_hint" : value instanceof InlineGraphEdits.TextOperator t && t.operation() == com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation.CONCAT ? "text_concat_hint": value != null ? "inline_drag_hint" : procedure != null ? "procedure_drag_hint" : "palette_hint")); }
        boolean matches(String query) {
            String math = id.equals("mathop") ? ScratchMathFunctions.OPTIONS.stream()
                .map(op -> op.name() + " " + text(ScratchMathFunctions.key(op)).getString() + " " + InlineGraphView.operationName(op).getString())
                .collect(java.util.stream.Collectors.joining(" ")) : "";
            String haystack = (id + " " + category + " " + group + " " + label.getString() + " " + math + " "
                + text("keywords_" + group).getString()).toLowerCase(Locale.ROOT);
            return Arrays.stream(query.strip().toLowerCase(Locale.ROOT).split("\\s+")).allMatch(haystack::contains);
        }
    }
    static List<Entry> entries(Map<String, DataType> locals) {
        return entries(locals, List.of());
    }
    static List<Entry> entries(Map<String, DataType> locals, List<FlowProgram.Procedure> procedures) {
        return entries(locals, procedures, Map.of());
    }
    static List<Entry> entries(Map<String, DataType> locals, List<FlowProgram.Procedure> procedures, Map<String, DataType> publicVariables) {
        var result = new ArrayList<Entry>();
        for (var category : Category.values()) for (var command : category.kinds) {
            String id = command.name().toLowerCase(Locale.ROOT);
            String group = switch (command) {
                case TIMER_RESET -> "timer";
                case ASK -> "prompt";
                case READ, WRITE, READABLE, XBUS_STATUS -> "xbus";
                case SIGNAL_READ, SIGNAL_WRITE -> "signals";
                case SERIAL_SEND, SERIAL_RECEIVE, SERIAL_STATUS, CORRELATION -> "serial";
                case EVALUATE -> "calls";
                case MONITOR_SHOW, MONITOR_HIDE, ASSIGN, PUBLIC_SET, PUBLIC_ADD -> "variables";
                case LIST_APPEND_LOCAL, LIST_INSERT_LOCAL, LIST_REMOVE_LOCAL, LIST_REPLACE_LOCAL, LIST_CLEAR -> "lists";
                case ATOMIC, MEMORY_READ, MEMORY_WRITE -> "storage";
                case MEMORY_BEGIN, MEMORY_COMMIT, MEMORY_ROLLBACK -> "transactions";
                default -> "control";
            };
            result.add(new Entry(id, category, group, text("add_" + id), command, null));
        }
        for (var value : InlineGraphEdits.palette(locals, publicVariables)) {
            Category category; String id, group;
            if (value instanceof InlineGraphEdits.Variable variable) {
                category = variable.path().isEmpty() ? Category.VARIABLES : Category.DATA;
                group = variable.path().isEmpty() ? "variables" : "fields";
                id = "local:" + part(variable.name()) + variable.path().stream().map(FlowBlockCatalog::part).reduce("", String::concat);
            } else if (value instanceof InlineGraphEdits.PublicVariable variable) {
                category = variable.path().isEmpty() ? Category.VARIABLES : Category.DATA;
                group = variable.path().isEmpty() ? "variables" : "fields";
                id = "public:" + part(variable.name()) + variable.path().stream().map(FlowBlockCatalog::part).reduce("", String::concat);
            } else if (value instanceof InlineGraphEdits.ListConstruction || value instanceof InlineGraphEdits.RecordConstruction) {
                category = Category.DATA;
                group = value instanceof InlineGraphEdits.ListConstruction?"lists":"fields";
                id = value instanceof InlineGraphEdits.ListConstruction?"list_make":"record_make";
            } else if (value instanceof InlineGraphEdits.RandomRange range) {
                category = Category.OPERATORS; group = "operators"; id = range.decimal() ? "random_decimal" : "random_integer";
            } else if (value instanceof InlineGraphEdits.Operator op && op.operation() == ScratchMathFunctions.DEFAULT) {
                category = Category.OPERATORS; group = "operators"; id = "mathop";
            } else if (value instanceof InlineGraphEdits.Timer) {
                category = Category.SENSING; group = "timer"; id = "timer_read";
            } else if (value instanceof InlineGraphEdits.Answer) {
                category = Category.SENSING; group = "prompt"; id = "answer";
            } else if (value instanceof InlineGraphEdits.KeyDown) {
                category=Category.SENSING;group="keyboard";id="key_down";
            } else if (value instanceof InlineGraphEdits.ValueEquality) {
                category = Category.OPERATORS; group = "operators"; id = "value_equals";
            } else if (value instanceof InlineGraphEdits.ListSearch search) {
                category = Category.DATA; group = "lists"; id = search.contains() ? "list_contains" : "list_find";
            } else if (value instanceof InlineGraphEdits.ListItem || value instanceof InlineGraphEdits.ListAppend) {
                category = Category.DATA; group = "lists"; id = value instanceof InlineGraphEdits.ListAppend ? "list_append" : "list_get";
            } else {
                category = value instanceof InlineGraphEdits.Operator op && op.operation().category() == com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation.Category.VALUE ? Category.DATA : Category.OPERATORS;
                group = value instanceof InlineGraphEdits.Operator op && op.operation() == com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation.LIST_LENGTH ? "lists" : value instanceof InlineGraphEdits.TextOperator ? "text" : value instanceof InlineGraphEdits.Operator ? "operators" : "constants";
                id = value instanceof InlineGraphEdits.Operator op ? op.operation().name().toLowerCase(Locale.ROOT)
                    : value instanceof InlineGraphEdits.TextOperator op ? "text_" + op.operation().name().toLowerCase(Locale.ROOT)
                    : "constant_" + DataType.typeOf(((InlineGraphEdits.Literal)value).value()).toString().toLowerCase(Locale.ROOT);
            }
            result.add(new Entry(id, category, group, label(value), null, value));
        }
        for (var procedure : procedures) {
            if (procedure.startup() || procedure.terminalKey().isPresent() || procedure.event().isPresent() || com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(procedure.key())) continue;
            if (FlowProcedureTools.supportsInlineOperands(procedure))
                result.add(new Entry("bind:" + part(procedure.key().id()) + procedure.key().revision(), Category.CUSTOM, "calls",
                    text("block_bind_invoke", procedure.key().id(), procedure.key().revision()), null, null, procedure));
            result.add(new Entry("procedure:" + part(procedure.key().id()) + procedure.key().revision(),
                Category.CUSTOM, "calls", text("block_invoke", procedure.key().id(), procedure.key().revision()), null, null, procedure));
            result.add(new Entry("task:" + part(procedure.key().id()) + procedure.key().revision(),
                Category.CUSTOM, "calls", text("block_task_start", procedure.key().id(), procedure.key().revision()), null, null, procedure, true));
            result.add(new Entry("join:" + part(procedure.key().id()) + procedure.key().revision(),
                Category.CUSTOM, "calls", text("block_task_join", procedure.key().id(), procedure.key().revision()), null, null, procedure));
            if (procedure.inputs().isEmpty() && procedure.outputs().isEmpty())
                result.add(new Entry("try_launch:" + part(procedure.key().id()) + procedure.key().revision(), Category.CUSTOM, "calls",
                    text("add_try_launch", procedure.key().id()), null, null, procedure));
        }
        for (var procedure : procedures) {
            if (procedure.startup() || procedure.terminalKey().isPresent() || procedure.event().isPresent() || com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(procedure.key())) continue;
            if (FlowProgram.supportsValueCall(procedure) && procedure.inputs().values().stream().allMatch(t -> t.accepts(InlineGraphEdits.defaultValue(t)))) {
                for (var output : new TreeSet<>(procedure.outputs().keySet())) {
                    var reporter = new InlineGraphEdits.ProcedureResult(procedure, output);
                    result.add(new Entry("reporter:" + part(procedure.key().id()) + procedure.key().revision() + ":" + output,
                        Category.CUSTOM, "calls", label(reporter), null, reporter));
                }
            }
        }
        for(var template:FlowProcedureTemplates.all())
            result.add(new Entry("template:"+template.key().id(),Category.DATA,template.key().id().equals("text_next")?"text":"recipes",text("template_"+template.key().id()),null,null,template));
        return List.copyOf(result);
    }
    static List<Entry> search(Map<String, DataType> locals, Category category, String query) {
        return search(locals, List.of(), category, query);
    }
    static List<Entry> search(Map<String, DataType> locals, List<FlowProgram.Procedure> procedures, Category category, String query) {
        return search(locals, procedures, category, query, false);
    }
    static List<Entry> search(Map<String, DataType> locals, List<FlowProgram.Procedure> procedures, Category category, String query, boolean advanced) {
        return search(locals, procedures, Map.of(), category, query, advanced);
    }
    static List<Entry> search(Map<String, DataType> locals, List<FlowProgram.Procedure> procedures, Map<String, DataType> publicVariables, Category category, String query, boolean advanced) {
        // A nonempty query searches all categories; selecting a category clears it in the view.
        return entries(locals, procedures, publicVariables).stream().filter(e -> advanced || !e.advanced())
            .filter(e -> query.isBlank() ? !e.localCall() && (category == Category.SENSING ? e.sensingSection() != null : e.category() == category) : e.matches(query))
            // Keep the current procedure's parameters visible before the growing command directory.
            .sorted(Comparator.comparingInt(e -> query.isBlank() && category == Category.VARIABLES && e.value() != null ? 0 : 1)).toList();
    }
    static List<Entry> extensionEntries(Map<String,Long> enabled, Category category, String query) {
        var entries = new ArrayList<Entry>();
        for (var extension : com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.all()) {
            if (!Objects.equals(enabled.get(extension.id().toString()),extension.revision())) continue;
            for (var command : extension.commands()) {
                var binding = new com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.Binding(extension,command);
                var procedure = binding.template("extension_template");
                var reporter = command.shape().bodyCount() > 0 || command.shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.HAT || command.shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.COMMAND
                    ? null : new InlineGraphEdits.ProcedureResult(procedure, command.outputs().keySet().iterator().next());
                var entry = new Entry("extension:"+binding.key().id(),Category.EXTENSIONS,"extensions",Component.translatable(command.labelKey()),
                    null,reporter,reporter == null ? procedure : null);
                if (query.isBlank() ? (category == Category.EXTENSIONS || category == Category.SENSING && entry.sensingSection() != null) : entry.matches(query)) entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }
    static Component label(InlineGraphEdits.Source source) {
        if (source instanceof InlineGraphEdits.ProcedureParameter param) return Component.literal(param.name());
        if (source instanceof InlineGraphEdits.RandomRange range) return text(range.decimal() ? "inline_random_decimal" : "inline_random_integer");
        if (source instanceof InlineGraphEdits.Timer) return text("inline_timer");
        if (source instanceof InlineGraphEdits.Answer) return text("inline_answer");
        if(source instanceof InlineGraphEdits.KeyDown)return text("inline_key_down",FlowBlockCanvas.keyLabel("space"));
        if (source instanceof InlineGraphEdits.ProcedureResult call) {
            if (com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(call.procedure().key()))
                return extensionValueLabel(call.procedure().key());
            return procedureValueLabel(call.procedure().key(), call.result(), call.procedure().inputs().keySet());
        }
        if (source instanceof InlineGraphEdits.ValueEquality) return text("inline_value_equals");
        if (source instanceof InlineGraphEdits.ListConstruction)return text("inline_list_make");
        if (source instanceof InlineGraphEdits.RecordConstruction)return text("inline_record_make");
        if (source instanceof InlineGraphEdits.ListItem) return text("inline_list_get");
        if (source instanceof InlineGraphEdits.ListAppend) return text("inline_list_append");
        if (source instanceof InlineGraphEdits.ListSearch search) return text(search.contains() ? "inline_list_contains" : "inline_list_find");
        if (source instanceof InlineGraphEdits.Operator op) {
            if (op.operation() == ScratchMathFunctions.DEFAULT) return text("inline_mathop");
            String id = op.operation().name().toLowerCase(Locale.ROOT);
            return switch (op.operation()) {
                case ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, LESS_INTEGER, EQUAL_INTEGER, MOD_INTEGER, MOD_DECIMAL -> text("inline_" + id);
                default -> InlineGraphEdits.numeric(op.operation()) ? InlineGraphView.operationName(op.operation()) : text("inline_" + id);
            };
        }
        if (source instanceof InlineGraphEdits.TextOperator op) return text("inline_text_" + op.operation().name().toLowerCase(Locale.ROOT));
        if (source instanceof InlineGraphEdits.Variable variable) return text("inline_private", variable.label());
        if (source instanceof InlineGraphEdits.PublicVariable variable) return text("inline_public", variable.label());
        return text("inline_literal", EditorValueFormat.format(((InlineGraphEdits.Literal)source).value()));
    }
    static Component procedureValueLabel(com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key key,
                                        String result, Collection<String> inputs) {
        return text("procedure_value", key.id(), key.revision(), result,
            inputs.isEmpty() ? "" : " (" + String.join(", ", inputs) + ")");
    }

    static Component extensionValueLabel(com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key key) {
        return com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(key)
            .<Component>map(binding -> Component.translatable(binding.command().labelKey()))
            .orElseGet(() -> text("extension_missing_block", key.id()));
    }
    private static String part(String text) { return text.length() + ":" + text; }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args); }
}
