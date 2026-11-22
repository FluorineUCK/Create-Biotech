package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local layout authoring. Applying changes the workspace only, never an installed program. */
public final class ComputerTerminalEditorScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final int TREE_TOP = 152, ROW_HEIGHT = 22;
    private final Screen parent;
    private final Inventory inventory;
    private final ProgramWorkspace workspace;
    private final Runnable applied, close;
    private final TerminalDraft draft;
    private Consumer<Screen> navigator = screen -> minecraft.setScreen(screen);
    private String selected, literalText = "", columnsText = "2", entryText = "click", argumentText = "value";
    private EditBox literal, columns, entry, argument;
    private ButtonSlot buttonSlot = ButtonSlot.TITLE;
    private enum ButtonSlot { TITLE, ENABLED, INPUTS }
    private InputSlot inputSlot = InputSlot.INITIAL;
    private enum InputSlot { INITIAL, ENABLED, INPUTS }
    private boolean jsonLiteral;
    private boolean literalEdited;
    private int treePage;
    private final Set<String> expanded = new HashSet<>();
    private Map<String, CreateTextButton> treeButtons = Map.of();
    private Component notice = text("binding_sample");
    private record TreeRow(String id, int depth) {}

    public ComputerTerminalEditorScreen(ComputerProgramMenu menu, Inventory inventory, Component title, Screen parent,
            ProgramWorkspace workspace, Runnable applied, Runnable close) {
        super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); this.inventory = inventory;
        this.workspace = Objects.requireNonNull(workspace); this.applied = Objects.requireNonNull(applied); this.close = Objects.requireNonNull(close);
        draft = new TerminalDraft(workspace.terminal().orElseGet(TerminalDefinition::empty));
        selected = draft.snapshot().root(); expanded.addAll(draft.snapshot().nodes().keySet()); seedFields();
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(420, height - 12)); super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.TERMINAL));
        int addWidth = (imageWidth - 28) / 4;
        for (var kind : TerminalDraft.Kind.values()) {
            int index = kind.ordinal();
            button(8 + index % 4 * (addWidth + 4), 30 + index / 4 * 24, addWidth, "add." + kind.name().toLowerCase(Locale.ROOT), () -> act(() -> {
                String owner = selected;
                selected = draft.add(owner, kind); expanded.add(owner); expanded.add(selected); seedFields(); revealSelection();
            }));
        }
        var selectedNode = draft.snapshot().nodes().get(selected);
        int fieldWidth = selectedNode instanceof TerminalDefinition.Input ? (imageWidth - 28) / 3
            : selectedNode instanceof TerminalDefinition.Button ? (imageWidth - 28) / 2 : (imageWidth - 20) * 3 / 4;
        literal = field(8, 80, fieldWidth, "literal", literalText, value -> literalText = value);
        if (selectedNode instanceof TerminalDefinition.Grid) {
            entry = null;
            columns = field(12 + fieldWidth, 80, imageWidth - fieldWidth - 20, "columns", columnsText, value -> columnsText = value);
        } else if (selectedNode instanceof TerminalDefinition.Button) {
            columns = null;
            int remaining = imageWidth - fieldWidth - 24, entryWidth = remaining * 2 / 3;
            targetField(12 + fieldWidth, entryWidth, ((TerminalDefinition.Button)selectedNode).target());
            button(16 + fieldWidth + entryWidth, 80, remaining - entryWidth,
                text("button_slot", text("button_slot." + buttonSlot.name().toLowerCase(Locale.ROOT))),
                () -> { buttonSlot = ButtonSlot.values()[(buttonSlot.ordinal() + 1) % ButtonSlot.values().length]; seedFields(); init(); });
        }
        else if (selectedNode instanceof TerminalDefinition.Input) {
            columns = null;
            int remaining = imageWidth - fieldWidth - 24, metadataWidth = (remaining - 4) / 3;
            targetField(12 + fieldWidth, metadataWidth, ((TerminalDefinition.Input)selectedNode).target());
            argument = field(16 + fieldWidth + metadataWidth, 80, metadataWidth, "argument", argumentText, value -> argumentText = value);
            button(20 + fieldWidth + metadataWidth * 2, 80, remaining - metadataWidth * 2 - 4,
                text("input_slot", text("input_slot." + inputSlot.name().toLowerCase(Locale.ROOT))),
                () -> { inputSlot = InputSlot.values()[(inputSlot.ordinal() + 1) % InputSlot.values().length]; seedFields(); init(); });
        }
        else {
            columns = null;
            entry = null; argument = null;
            button(12 + fieldWidth, 80, imageWidth - fieldWidth - 20, text("literal_mode", jsonLiteral ? "JSON" : "TEXT"),
                () -> { jsonLiteral = !jsonLiteral; init(); }).active = draft.snapshot().nodes().get(selected) instanceof TerminalDefinition.Text;
        }
        int fifth = (imageWidth - 32) / 5;
        button(8, 104, fifth, "configure", () -> act(this::configure));
        button(12 + fifth, 104, fifth, "bind", this::browse).active = binding() != null;
        button(16 + fifth * 2, 104, fifth, "up", () -> act(() -> { draft.reorder(selected, -1); revealSelection(); })).active = draft.parent(selected).isPresent();
        button(20 + fifth * 3, 104, fifth, "down", () -> act(() -> { draft.reorder(selected, 1); revealSelection(); })).active = draft.parent(selected).isPresent();
        button(24 + fifth * 4, 104, fifth, "remove", () -> act(() -> {
            String owner = draft.parent(selected).orElseThrow(); draft.remove(selected); selected = owner; seedFields(); revealSelection();
        })).active = draft.parent(selected).filter(id -> !(draft.snapshot().nodes().get(id) instanceof TerminalDefinition.Repeat)).isPresent();
        int fourth = (imageWidth - 28) / 4;
        var path = binding() instanceof TerminalBinding.Path value ? value.path() : new ValuePath("memory", List.of());
        button(8, 128, fourth, text("path_root", path.input()), () -> act(() -> {
            String root = switch (path.input()) { case "memory" -> "output"; case "output" -> "row"; case "row" -> workspace.flow().isPresent() ? "tasks" : "memory"; default -> "memory"; };
            setBinding(new TerminalBinding.Path(new ValuePath(root, List.of())));
        })).active = binding() != null;
        button(12 + fourth, 128, fourth, "path_key", () -> act(() -> setBinding(new TerminalBinding.Path(path.key(literalText))))).active = binding() != null;
        button(16 + fourth * 2, 128, fourth, "path_index", () -> act(() -> setBinding(new TerminalBinding.Path(path.index(Integer.parseInt(literalText)))))).active = binding() != null;
        button(20 + fourth * 3, 128, fourth, "path_parent", () -> act(() -> setBinding(new TerminalBinding.Path(new ValuePath(path.input(), path.steps().subList(0, path.steps().size() - 1)))))).active = binding() instanceof TerminalBinding.Path && !path.steps().isEmpty();
        var rows = tree(); int capacity = treeRows(); int count = capacity == 0 ? 1 : Math.max(1, (rows.size() + capacity - 1) / capacity); treePage = Math.clamp(treePage, 0, count - 1);
        treeButtons = new LinkedHashMap<>();
        for (int i = 0, index = treePage * capacity; i < capacity && index < rows.size(); i++, index++) {
            var row = rows.get(index); var node = draft.snapshot().nodes().get(row.id());
            int indent = Math.min(row.depth() * 8, imageWidth / 3), y = treeTop() + i * ROW_HEIGHT;
            var toggle = button(8 + indent, y, 18, Component.literal(expanded.contains(row.id()) ? "-" : "+"), () -> {
                if (!expanded.remove(row.id())) expanded.add(row.id()); init();
            }); toggle.active = !TerminalDraft.children(node).isEmpty();
            String label = (row.id().equals(selected) ? "> " : "") + row.id() + " · " + text("kind." + TerminalDraft.kind(node).name().toLowerCase(Locale.ROOT)).getString();
            treeButtons.put(row.id(), button(30 + indent, y, imageWidth - 38 - indent, Component.literal(label), () -> { selected = row.id(); seedFields(); init(); }));
        }
        button(8, imageHeight - 51, 42, "tree_previous", () -> { treePage--; init(); }).active = treePage > 0;
        button(54, imageHeight - 51, 42, "tree_next", () -> { treePage++; init(); }).active = treePage + 1 < count;
        int third = (imageWidth - 24) / 3;
        button(8, imageHeight - 27, third, "installed", () -> navigator.accept(new ComputerTerminalScreen(menu, inventory, title, this)));
        button(12 + third, imageHeight - 27, third, "apply", () -> act(() -> { workspace.setTerminal(Optional.of(draft.snapshot())); applied.run(); close.run(); }));
        button(16 + third * 2, imageHeight - 27, third, "cancel", this::onClose);
    }
    private int treeTop() { return TREE_TOP; }
    private int treeRows() { return Math.max(1, (imageHeight - treeTop() - 54) / ROW_HEIGHT); }
    private List<TreeRow> tree() {
        var result = new ArrayList<TreeRow>(); var pending = new ArrayDeque<TreeRow>(); pending.push(new TreeRow(draft.snapshot().root(), 0));
        while (!pending.isEmpty()) {
            var row = pending.pop(); result.add(row);
            if (!expanded.contains(row.id())) continue;
            var children = TerminalDraft.children(draft.snapshot().nodes().get(row.id()));
            for (int i = children.size() - 1; i >= 0; i--) pending.push(new TreeRow(children.get(i), row.depth() + 1));
        }
        return result;
    }
    private void revealSelection() {
        int capacity = treeRows(); if (capacity == 0) { treePage = 0; return; }
        var rows = tree(); for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(selected)) { treePage = i / capacity; break; }
    }
    private void seedFields() {
        literalEdited = false;
        var node = draft.snapshot().nodes().get(selected);
        boolean plainText = node instanceof TerminalDefinition.Text || node instanceof TerminalDefinition.Button && buttonSlot == ButtonSlot.TITLE || node instanceof TerminalDefinition.Input && inputSlot == InputSlot.INITIAL;
        jsonLiteral = !plainText;
        if (node instanceof TerminalDefinition.Grid grid) columnsText = Integer.toString(grid.columns());
        if (node instanceof TerminalDefinition.Button button && button.target() instanceof TerminalTarget.Event event) entryText = event.entry();
        if (node instanceof TerminalDefinition.Input input) { if (input.target() instanceof TerminalTarget.Event event) entryText = event.entry(); argumentText = input.argument(); }
        if (binding() instanceof TerminalBinding.Literal literal) {
            if (plainText && literal.value() instanceof DataValue.TextValue text) { literalText = text.value(); return; }
            jsonLiteral = true;
            try { literalText = EditorValueFormat.format(literal.value()); }
            catch (IllegalArgumentException unsupported) { literalText = ""; }
        } else literalText = "";
    }
    private TerminalBinding binding() {
        return switch (draft.snapshot().nodes().get(selected)) {
            case TerminalDefinition.Text value -> value.value(); case TerminalDefinition.Item value -> value.value();
            case TerminalDefinition.Repeat value -> value.items();
            case TerminalDefinition.Button value -> switch (buttonSlot) {
                case TITLE -> value.title(); case ENABLED -> value.enabled(); case INPUTS -> value.inputs();
            };
            case TerminalDefinition.Input value -> switch (inputSlot) { case INITIAL -> value.initial(); case ENABLED -> value.enabled(); case INPUTS -> value.inputs(); };
            default -> null;
        };
    }
    private void setBinding(TerminalBinding binding) {
        var node = draft.snapshot().nodes().get(selected);
        draft.replace(selected, switch (node) {
            case TerminalDefinition.Text ignored -> new TerminalDefinition.Text(binding);
            case TerminalDefinition.Item ignored -> new TerminalDefinition.Item(binding);
            case TerminalDefinition.Repeat repeat -> new TerminalDefinition.Repeat(binding, repeat.template());
            case TerminalDefinition.Button button -> switch (buttonSlot) {
                case TITLE -> new TerminalDefinition.Button(binding, button.enabled(), button.target(), button.inputs());
                case ENABLED -> new TerminalDefinition.Button(button.title(), binding, button.target(), button.inputs());
                case INPUTS -> new TerminalDefinition.Button(button.title(), button.enabled(), button.target(), binding);
            };
            case TerminalDefinition.Input input -> switch (inputSlot) { case INITIAL -> new TerminalDefinition.Input(binding, input.enabled(), input.target(), input.argument(), input.inputs()); case ENABLED -> new TerminalDefinition.Input(input.initial(), binding, input.target(), input.argument(), input.inputs()); case INPUTS -> new TerminalDefinition.Input(input.initial(), input.enabled(), input.target(), input.argument(), binding); };
            default -> throw new IllegalArgumentException("Select a text, item or repeat");
        });
        literalEdited = false;
    }
    private void configure() {
        if (draft.snapshot().nodes().get(selected) instanceof TerminalDefinition.Grid grid)
            draft.replace(selected, new TerminalDefinition.Grid(Integer.parseInt(columnsText), grid.children()));
        else if (draft.snapshot().nodes().get(selected) instanceof TerminalDefinition.Button button) {
            TerminalTarget target = configuredTarget(button.target());
            TerminalBinding replacement = literalEdited
                ? new TerminalBinding.Literal(EditorValueFormat.parse(jsonLiteral ? EditorValueFormat.Kind.JSON : EditorValueFormat.Kind.TEXT, literalText))
                : null;
            TerminalDefinition.Button candidate = switch (buttonSlot) {
                case TITLE -> new TerminalDefinition.Button(replacement == null ? button.title() : replacement, button.enabled(), target, button.inputs());
                case ENABLED -> new TerminalDefinition.Button(button.title(), replacement == null ? button.enabled() : replacement, target, button.inputs());
                case INPUTS -> new TerminalDefinition.Button(button.title(), button.enabled(), target, replacement == null ? button.inputs() : replacement);
            };
            draft.replace(selected, candidate);
        } else if (draft.snapshot().nodes().get(selected) instanceof TerminalDefinition.Input input) {
            TerminalTarget target = configuredTarget(input.target());
            TerminalBinding replacement = literalEdited ? new TerminalBinding.Literal(EditorValueFormat.parse(jsonLiteral ? EditorValueFormat.Kind.JSON : EditorValueFormat.Kind.TEXT, literalText)) : null;
            draft.replace(selected, switch (inputSlot) { case INITIAL -> new TerminalDefinition.Input(replacement == null ? input.initial() : replacement, input.enabled(), target, argumentText, input.inputs()); case ENABLED -> new TerminalDefinition.Input(input.initial(), replacement == null ? input.enabled() : replacement, target, argumentText, input.inputs()); case INPUTS -> new TerminalDefinition.Input(input.initial(), input.enabled(), target, argumentText, replacement == null ? input.inputs() : replacement); });
        } else {
            setBinding(new TerminalBinding.Literal(EditorValueFormat.parse(jsonLiteral ? EditorValueFormat.Kind.JSON : EditorValueFormat.Kind.TEXT, literalText)));
        }
        literalEdited = false;
    }
    private TerminalTarget configuredTarget(TerminalTarget current) {
        return workspace.flow().isPresent() ? current : new TerminalTarget.Event(entryText);
    }
    private void targetField(int x, int width, TerminalTarget target) {
        if (workspace.flow().isEmpty()) { entry = field(x, 80, width, "entry", entryText, value -> entryText = value); return; }
        entry = null;
        var label = target instanceof TerminalTarget.Task task ? task.procedure().id() + "@" + task.procedure().revision() : "—";
        var control = button(x, 80, width, text("select_task", label), this::selectTask);
        control.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("select_task_hint", label)));
    }
    private void selectTask() {
        var definitions = workspace.flow().orElseThrow().canvas().detached().stream().map(stack -> stack.steps().getFirst())
            .filter(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure.class::isInstance)
            .map(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure.class::cast).toList();
        navigator.accept(new ComputerTerminalTargetScreen(menu, inventory, title, definitions, key -> {
            var target = new TerminalTarget.Task(key);
            var node = draft.snapshot().nodes().get(selected);
            draft.replace(selected, switch (node) {
                case TerminalDefinition.Button b -> new TerminalDefinition.Button(b.title(), b.enabled(), target, b.inputs());
                case TerminalDefinition.Input i -> new TerminalDefinition.Input(i.initial(), i.enabled(), target, i.argument(), i.inputs());
                default -> throw new IllegalArgumentException("Select an interactive control");
            });
            // Only the target changes. Unapplied text fields and explicit argument bindings stay intact.
            navigator.accept(this); init();
        }, () -> navigator.accept(this)));
    }
    private DataValue.RecordValue samples() {
        var memory = new LinkedHashMap<String, DataValue>(); workspace.root().cells().forEach((name, cell) -> memory.put(name, cell.initial()));
        var values = new LinkedHashMap<String, DataValue>(); values.put("memory", new DataValue.RecordValue(memory)); values.put("output", DataValue.NullValue.INSTANCE);
        if (workspace.flow().isPresent()) values.put("tasks", new DataValue.ListValue(List.of()));
        menu.terminalStatus().ifPresent(status -> values.putAll(TerminalStatusView.decode(status).roots().fields()));
        values.put("row", DataValue.NullValue.INSTANCE);
        var ancestors = new ArrayDeque<String>(); String cursor = selected;
        while (draft.parent(cursor).isPresent()) { cursor = draft.parent(cursor).orElseThrow(); ancestors.push(cursor); }
        while (!ancestors.isEmpty()) if (draft.snapshot().nodes().get(ancestors.pop()) instanceof TerminalDefinition.Repeat repeat) {
            try { var list = repeat.items().resolve(new DataValue.RecordValue(values));
                values.put("row", list instanceof DataValue.ListValue items && !items.values().isEmpty() ? items.values().getFirst() : DataValue.NullValue.INSTANCE);
            } catch (RuntimeException unavailable) { values.put("row", DataValue.NullValue.INSTANCE); }
        }
        return new DataValue.RecordValue(values);
    }
    private void browse() {
        act(() -> navigator.accept(new ComputerValueTreeScreen<>(menu, inventory, title, this, samples(), path -> {
            setBinding(new TerminalBinding.Path(path)); navigator.accept(this); init();
        }, text("select_binding"), text("binding_sample"), () -> navigator.accept(this))));
    }
    private void act(Runnable action) {
        try { action.run(); notice = text("binding_sample"); init(); }
        catch (IllegalArgumentException | NoSuchElementException error) { notice = text("invalid", String.valueOf(error.getMessage())); }
    }
    private EditBox field(int x, int y, int width, String key, String value, Consumer<String> changed) {
        var box = addRenderableWidget(new EditBox(font, leftPos + x, topPos + y, width, 18, text(key)));
        box.setMaxLength(24 * 1024); box.setValue(value); box.setHint(text(key));
        box.setResponder(next -> { changed.accept(next); if (key.equals("literal")) literalEdited = true; }); return box;
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) { return button(x, y, width, text(key), action); }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(notice.getString(), imageWidth - 90), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        String detail = binding() instanceof TerminalBinding.Path path ? path.path().display() : text("binding_sample").getString();
        graphics.drawString(font, font.plainSubstrByWidth(detail, imageWidth - 110), leftPos + 102, topPos + imageHeight - 45, 0xffaaccee, false);
    }
    @Override public void onClose() { close.run(); }
    @Override public void removed() { }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.terminal." + key, args); }
}
