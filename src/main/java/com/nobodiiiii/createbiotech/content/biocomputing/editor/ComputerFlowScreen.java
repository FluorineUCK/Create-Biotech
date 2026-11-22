package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBranches;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Kind;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Scratch control blocks and nested reporters. No command is executed while editing. */
public final class ComputerFlowScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final List<Category> SIDEBAR = List.of(Category.EVENTS,Category.CONTROL,Category.OPERATORS,
        Category.VARIABLES,Category.STORAGE,Category.DATA,Category.SENSING,Category.CUSTOM,Category.EXTENSIONS);
    private enum InputSource { LOCAL, LITERAL, EXPRESSION }
    private static final int TOP = 80;
    private static final int PALETTE_ROW = 24, PALETTE_SCROLL_WIDTH = 6;
    private final ProgramWorkspace workspace;
    private final Inventory editorInventory;
    private Consumer<net.minecraft.client.gui.screens.Screen> navigator = screen -> minecraft.setScreen(screen);
    private final Consumer<ProgramDocument> replace;
    private final Runnable changed, close, debugger;
    private final BiConsumer<Optional<FlowBlockCatalog.SensingSection>, Runnable> documents;
    private FlowBlockCatalog.SensingSection sensingSection = FlowBlockCatalog.SensingSection.INPUT;
    private Runnable saveDraft;
    private Supplier<Component> draftStatus;
    private BooleanSupplier canSaveDraft;
    private CreateTextButton saveDraftButton;
    public ComputerFlowScreen withDraftSaving(Runnable save, Supplier<Component> status, BooleanSupplier enabled) {
        saveDraft = Objects.requireNonNull(save); draftStatus = Objects.requireNonNull(status);
        canSaveDraft = Objects.requireNonNull(enabled); return this;
    }
    private final FlowDraft draft;
    private final FlowBlockCanvas canvas;
    private EdgeDefinition edgeDefinition;
    private record EdgeDefinition(String source, Runnable confirm) {}
    private static final List<Category> EDGE_CATEGORIES = List.of(Category.OPERATORS, Category.VARIABLES, Category.DATA,
        Category.SENSING, Category.CUSTOM, Category.EXTENSIONS);
    private boolean edgeDecimal;
    private ScalarOperation edgeCombine = ScalarOperation.OR_BOOLEAN;
    private final Map<CreateTextButton, Kind> prototypes = new LinkedHashMap<>();
    private final Map<CreateTextButton, FlowBlockCatalog.Entry> procedurePrototypes = new LinkedHashMap<>();
    private FlowBlockCatalog.Entry takingProcedure;
    private final Map<CreateTextButton, InlineGraphEdits.Source> reporters = new LinkedHashMap<>();
    private InlineGraphEdits.Source takingReporter;
    private ReporterDrag reporterDrag;
    private double pointerX=-1,pointerY=-1;
    private FlowBlockCanvas.ParkedSlot editingParked;
    private final List<CreateTextButton> reporterMenu = new ArrayList<>();
    private final class ReporterDrag {
        final FlowBlockCanvas.Slot source;
        final FlowBlockCanvas.ParkedSlot parked;
        final double x, y;
        private ScratchValueLayout.Reporter fragment;
        ReporterDrag(FlowBlockCanvas.Slot source, FlowBlockCanvas.ParkedSlot parked, double x, double y) {
            this.source = source; this.parked = parked; this.x = x; this.y = y;
        }
        boolean moved(double mx, double my) { return Math.hypot(mx - x, my - y) > 4; }
        ScratchValueLayout.Reporter fragment() {
            if (fragment == null) {
                var layout = draft.snapshot().canvas().values();
                if (parked != null) fragment = layout.parked().get(parked.index()).subtree(parked.slot().node());
                else {
                    var step = draft.find(source.call()).orElseThrow();
                    fragment = scopeReporter(source.call(), FlowReporterEdits.take(inlineGraph(step), FlowInlineValues.bindings(step),
                        layout.forBlock(source.call()), source.node(), canvas.reporterPosition(x, y)));
                }
            }
            return fragment;
        }
    }
    private Category category = Category.CONTROL;
    private Kind taking;
    private int palettePage, paletteOffset, paletteWidth, paletteX, categoryWidth, categoryStep;
    private double paletteGrab = -1, paletteWheelRemainder;
    private String searchQuery = "";
    private EditBox catalogSearch;
    private final List<net.minecraft.client.gui.components.AbstractWidget> catalogWidgets = new ArrayList<>();
    private List<FlowBlockCatalog.Entry> catalogEntries = List.of();
    private String selected, editing, port = "", local = "", expression = "", schema = "";
    private boolean readResult, nativeReceive, claimReceiveFaults;
    private boolean serialPortsOpen, serialPortsPending;
    private long serialPortsResponseVersion;
    private int serialPortPage;
    private List<String> serialPorts = List.of();
    private Component serialPortsNotice = Component.empty();
    private boolean serialWireless;
    private String resultLocal = "";
    private InputSource inputSource = InputSource.LOCAL;
    private String inputLiteral = "", inputOutput = "";
    private FlowInput originalInput;
    private Optional<FlowProgram.WaitUnit> waitUnit = Optional.empty();
    private boolean uneditableLiteral;
    private FlowProgram.Evaluate originalCall;
    private final Map<String, String> callArguments = new LinkedHashMap<>(), callReturns = new LinkedHashMap<>();
    private String bindingExpression = "";
    private boolean editingBindings, reviewedBindings;
    private int bindingPage;
    private DataType retainedType;
    private Kind kind;
    private boolean parameters, initials, waiting;
    private boolean creatingBroadcastChannel;
    private boolean cellDeclarations, advancedStorage;
    private String initialName = "", initialValue = "", selectedInitial;
    private int literalKind, initialPage;
    private long responseVersion;
    private FlowDocumentBody hintBody;
    private ResourceLimits hintHardware;
    private Map<String, ProgramDefinition.Cell> hintCells = Map.of();
    private final Map<String, Boolean> hintClosedSignatures = new HashMap<>();
    private final Map<String, Boolean> hintSoftwareSignatures = new HashMap<>();
    private final Map<String, Map<String, DataType>> hintScopes = new HashMap<>();
    private Map<ModuleDefinition.Key, FlowProgram.Procedure> hintProcedures = Map.of();
    private final Map<CreateTextButton, FlowBlockCatalog.Entry> hintCatalog = new LinkedHashMap<>();
    private Component notice = text("hint");
    private EditBox inlineField;
    private FlowBlockCanvas.Slot editingSlot;
    private FlowBlockCanvas.Slot variableSlot;
    private List<InlineGraphEdits.Source> variableChoices = List.of();
    private final List<CreateTextButton> variableWidgets = new ArrayList<>();
    private int variablePage;
    private boolean namedListTarget, listDeclaration, sharedListDeclaration;
    private String listName = "";
    private int listElement;
    private InlineGraphEdits.ConstructorDraft construction;
    private String constructionCall;
    private int constructionParked = -1;
    private final Deque<InlineGraphEdits.ConstructorDraft> constructionParents = new ArrayDeque<>();
    private int constructionPan;
    private int constructionPage;
    private String constructionFocus, constructionLiteralNode;
    private final List<FlowBlockCanvas.Slot> constructionFocusSlots = new ArrayList<>();
    private InlineGraphView.Tile constructionFocusTile;
    private final Map<CreateTextButton, Integer> constructionSlots = new LinkedHashMap<>();

    public ComputerFlowScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ProgramWorkspace workspace,
        Consumer<ProgramDocument> replace, Runnable changed, Runnable close, Runnable documents, Runnable debugger) {
        this(menu,inventory,title,workspace,replace,changed,close,(section,resume)->documents.run(),debugger);
    }
    public ComputerFlowScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ProgramWorkspace workspace,
        Consumer<ProgramDocument> replace, Runnable changed, Runnable close, BiConsumer<Optional<FlowBlockCatalog.SensingSection>, Runnable> documents, Runnable debugger) {
        super(menu, inventory, title); this.workspace = Objects.requireNonNull(workspace); this.replace = Objects.requireNonNull(replace);
        editorInventory = inventory;
        this.changed = Objects.requireNonNull(changed); this.close = Objects.requireNonNull(close);
        this.documents = Objects.requireNonNull(documents); this.debugger = Objects.requireNonNull(debugger);
        draft = new FlowDraft(workspace.flow().orElseGet(FlowDocumentBody::empty)); responseVersion = menu.responseVersion();
        canvas = new FlowBlockCanvas(draft, this::canvasChanged, menu::capturedInputs);
    }
    private void canvasChanged() { selected = canvas.selected(); publish(); init(); }
    @Override protected void init() {
        closeReporterMenu(); reporterDrag=null; editingParked=null;
        closeVariableChoices();
        saveDraftButton = null;
        // Re-entry from another editor can change the workspace archive independently of the Flow body.
        hintBody = null;
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets(); setFocused(null);
        inlineField = null;
        editingSlot = null;
        
        constructionSlots.clear();
        constructionFocusSlots.clear();
        constructionFocusTile = null;
        canvas.font(font);
        prototypes.clear(); procedurePrototypes.clear(); reporters.clear(); catalogWidgets.clear(); hintCatalog.clear(); catalogSearch = null;
        paletteGrab = -1;
        takingReporter = null; takingProcedure = null;
        categoryWidth = Math.clamp(imageWidth / 8, 42, 64); paletteX = 12 + categoryWidth;
        paletteWidth = Math.clamp(imageWidth / 5, 70, 130); categoryStep = Math.min(24, (imageHeight - 106) / SIDEBAR.size());
        canvas.bounds(leftPos + paletteX + paletteWidth + 8, topPos + TOP, imageWidth - paletteX - paletteWidth - 16, imageHeight - TOP - 52);
        canvas.selected(selected);
        if (workspace.flow().isEmpty()) {
            button(8, 56, imageWidth - 16, "new_flow", () -> act(() -> {
                var empty = new ProgramWorkspace(); empty.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
                replace.accept(empty.snapshot());
                onClose();
            }));
            button(imageWidth - 84, imageHeight - 27, 76, "back", this::onClose); return;
        }
        if (edgeDialog()) { initEdgeDefinition(); return; }
        if (listDeclaration) { listDeclarationEditor(); return; }
        if (initials) { initialEditor(); return; }
        if (parameters) { parameterEditor(); return; }
        int quarter = (imageWidth - 32) / 5;
        addRenderableWidget(ManualButton.at(font, leftPos + 24 + 4 * quarter, topPos + 30, quarter, 20, ManualFile.QUICK));
        button(8, 30, quarter, "initials", () -> declarations(false)).active = construction == null;
        button(12 + quarter, 30, quarter, "undo", () -> editBase(() -> act(() -> {
            draft.undo(); publish();
        }))).active = construction == null && draft.canUndo();
        button(16 + 2 * quarter, 30, quarter, "redo", () -> editBase(() -> act(() -> {
            draft.redo(); publish();
        }))).active = construction == null && draft.canRedo();
        button(20 + 3 * quarter, 30, quarter, "documents", () -> editBase(()->documents.accept(Optional.empty(),()->navigator.accept(this)))).active = construction == null;
        for (var group : SIDEBAR) {
            var choice = addRenderableWidget(new CreateTextButton(font, leftPos + 8, topPos + 54 + SIDEBAR.indexOf(group) * categoryStep,
                categoryWidth, categoryStep - 2, text("category_" + group.name().toLowerCase(Locale.ROOT)), () -> {
                    taking = null; takingReporter = null; takingProcedure = null; canvas.cancel(); cancelSlot(); category = group; resetPaletteScroll();
                    searchQuery = ""; catalogSearch.setValue(""); refreshCatalog();
                }, true));
            choice.setTooltip(Tooltip.create(choice.getMessage()));
        }
        int searchWidth = construction == null ? imageWidth - paletteX - 8 : paletteWidth;
        catalogSearch = field(paletteX, 54, searchWidth, "catalog_search", searchQuery, value -> {
            taking = null; takingReporter = null; takingProcedure = null; canvas.cancel(); searchQuery = value; resetPaletteScroll(); refreshCatalog();
        });
        refreshCatalog();
        if (construction != null) {
            constructorEditor();
            return;
        }
        var install = button(imageWidth - 144, 5, 64, "install", this::install);
        if (saveDraft != null) {
            saveDraftButton = button(imageWidth - 212, 5, 64, "save_draft", () -> editBase(() -> {
                workspace.setFlow(Optional.of(draft.snapshot())); saveDraft.run();
            }));
            refreshDraftStatus();
        }
        install.active = !waiting && menu.response().isPresent();
        var installHint = text("install_hint").copy().append("\n").append(resourceSummary());
        install.setTooltip(Tooltip.create(installHint));
        // Create's container foreground reads its own tooltip list; keep narration in sync.
        install.setToolTip(installHint);
        var debug = button(imageWidth - 76, 5, 68, "debugger", debugger);
        debug.active = menu.response().isPresent(); debug.setTooltip(Tooltip.create(text("debugger_hint")));
        int footer = imageHeight - 27;
        button(8, footer, 60, "reset_view", canvas::resetWithSelection);
        // Keep the compact controls clear of the lower-left mod overlays.
        zoomControl(72, footer, -1);
        zoomControl(96, footer, 1);
        int w = (imageWidth - 136) / 3;
        boolean editable = selectedStep().filter(step -> !delimiter(step)).isPresent();
        var editBlock = button(120, footer, w, "edit", () -> selectedStep().ifPresent(step -> edit(step, type(step))));
        var removeBlock = button(124 + w, footer, w, "remove", () -> editBase(() -> act(() -> {
            draft.removeAndReleaseUnusedExtensions(selected, workspace); selected = null; publish();
        })));
        editBlock.active = removeBlock.active = editable;
        for (var control : List.of(editBlock, removeBlock, button(128 + 2 * w, footer, w, "back", this::onClose)))
            FlowBlockSkin.fitToolboxCaption(control, font);
    }

    private void zoomControl(int x, int y, int direction) {
        var control = button(x, y, 20, Component.literal(direction > 0 ? "+" : "−"),
            () -> canvas.zoomAtCenter(direction));
        control.active = !waiting;
        var hint = text(direction > 0 ? "zoom_in" : "zoom_out");
        control.setTooltip(Tooltip.create(hint));
        control.setToolTip(hint);
    }
    /** Constructor forms own a temporary draft; underlying history/navigation stays unchanged. */
    private void editBase(Runnable action) {
        if (construction == null) action.run();
    }

    private void constructorEditor() {
        int x = paletteX + paletteWidth + 8;
        int w = imageWidth - x - 8;
        int count = Math.max(1, (imageHeight - TOP - 106) / 36);
        int pages = Math.max(1, (construction.rows().size() + count - 1) / count);
        constructionPage = Math.clamp(constructionPage, 0, pages - 1);
        button(x, 54, w, "construction_members", () -> {
        }).active = false;
        if (constructionFocus != null) {
            constructionFocusTile = InlineGraphView.layout(construction.preview(constructionFocus), font::width);
            constructionSlots(constructionFocusTile, leftPos + x - constructionPan, topPos + TOP);
            button(x, imageHeight - 104, w, "construction_back_members", () -> {
                constructionFocus = null;
                init();
            });
        } else {
            for (int i = 0; i < count && constructionPage * count + i < construction.rows().size(); i++) {
                int row = constructionPage * count + i, y = TOP + i * 36;
                var value = construction.rows().get(row);
                int keyWidth = construction.record() ? Math.max(52, w / 4) : 28;
                if (construction.record()) {
                    field(x, y, keyWidth, "construction_key", value.key(),
                        key -> construction.rename(row, key));
                } else {
                    button(x, y, keyWidth, Component.literal(Integer.toString(row + 1)), () -> {
                    }).active = false;
                }
                int vx = x + keyWidth + 4, vw = w - keyWidth - 72;
                var slot = button(vx, y, vw,
                    Component.literal(InlineGraphView.label(construction.node(row))),
                    () -> editConstructionLiteral(row, vx, y, vw));
                constructionSlots.put(slot, row);
                button(x + w - 64, y, 20, "construction_up", () -> {
                    construction.move(row, -1);
                    init();
                }).active = row > 0;
                button(x + w - 42, y, 20, "construction_down", () -> {
                    construction.move(row, 1);
                    init();
                }).active = row + 1 < construction.rows().size();
                button(x + w - 20, y, 20, "construction_remove", () -> {
                    construction.remove(row);
                    init();
                });
            }
        }
        int half = (w - 4) / 2;
        if (constructionFocus == null) {
            button(x, imageHeight - 104, half, "construction_previous", () -> {
                constructionPage--;
                init();
            }).active = constructionPage > 0;
            button(x + half + 4, imageHeight - 104, half, "construction_next", () -> {
                constructionPage++;
                init();
            }).active = constructionPage + 1 < pages;
            button(x, imageHeight - 78, w, "construction_add", () -> {
                construction.add();
                constructionPage = (construction.rows().size() - 1) / count;
                init();
            });
        }
        button(x, imageHeight - 27, half, "construction_cancel", this::cancelConstruction);
        button(x + half + 4, imageHeight - 27, half, "construction_apply", () -> act(() -> {
            var edit = construction.finish();
            if (!constructionParents.isEmpty()) {
                construction = constructionParents.pop();
                construction.accept(edit);
                constructionFocus = null;
                constructionPage = 0;
            } else {
                applyConstruction(edit);
                closeConstructor();
                publish();
            }
        }));
    }

    private void applyConstruction(InlineGraphEdits.Edit edit) {
        var context = draft.snapshot();
        var candidate = new FlowDraft(context);
        var layout = context.canvas().values();
        var parked = constructionParked < 0 ? null : layout.parked().get(constructionParked);
        var step = parked == null ? draft.find(constructionCall).orElseThrow() : null;
        var previous = parked == null ? inlineGraph(step) : parked.graph();

        // Link only references added by this edit. Unrelated missing extensions in a
        // saved draft must remain inspectable instead of making member edits impossible.
        var before = extensionReporterKeys(previous);
        var added = extensionReporterKeys(edit.graph());
        added.removeAll(before);
        if (!added.isEmpty()) {
            if (parked == null) {
                added.forEach(key -> bindExtensionReporter(candidate, key));
                validateInline(step, edit.graph(), edit.bindings(), candidate.snapshot());
            } else {
                // A parked value needs signatures for checking, not an executable template.
                parkedTypes(edit.graph(), context);
            }
        }

        if (parked == null) candidate.editInline(constructionCall, edit.graph(), edit.bindings());
        else {
            var reporters = new ArrayList<>(layout.parked());
            reporters.set(constructionParked, parked.withConstructor(edit));
            candidate.editValues(layout.withParked(reporters));
        }
        if (parked == null) draft.acceptReporterEdit(candidate, workspace);
        else draft.accept(candidate);
    }

    private void cancelConstruction() {
        if (constructionParents.isEmpty()) closeConstructor();
        else construction = constructionParents.pop();
        constructionFocus = null;
        constructionPage = 0;
        init();
    }

    private void closeConstructor() {
        construction = null;
        constructionCall = null;
        constructionParked = -1;
    }

    private boolean constructionVisible(double x, double y) {
        return x >= leftPos + paletteX + paletteWidth + 8 && x < leftPos + imageWidth - 8
            && y >= topPos + TOP && y < topPos + imageHeight - 114;
    }

    private void constructionSlots(InlineGraphView.Tile tile, double x, double y) {
        if (tile.node() != null) {
            constructionFocusSlots.add(new FlowBlockCanvas.Slot(constructionCall, tile.node(), tile.label(),
                x, y, tile.width(), tile.height(), false));
        }
        for (var child : tile.children()) {
            constructionSlots(child.tile(), x + child.x(), y + child.y());
        }
    }

    private void editConstructionLiteral(int row, int x, int y, int w) {
        String node = construction.rows().get(row).child();
        if (!(construction.node(row) instanceof ComputationGraph.Constant)) {
            constructionFocus = node;
            init();
            return;
        }
        editConstructionNode(node, leftPos + x, topPos + y, w);
    }

    private void editConstructionNode(String node, double x, double y, int w) {
        if (construction.node(node) instanceof ComputationGraph.MakeList || construction.node(node) instanceof ComputationGraph.MakeRecord) {
            var parent = construction;
            constructionParents.push(parent);
            construction = new InlineGraphEdits.ConstructorDraft(parent.editableGraph(), parent.bindings(), node);
            constructionFocus = null;
            constructionPage = 0;
            init();
            return;
        }
        if (!(construction.node(node) instanceof ComputationGraph.Constant c)) {
            constructionFocus = node;
            constructionPan = 0;
            init();
            return;
        }
        cancelSlot();
        constructionLiteralNode = node;
        inlineField = addRenderableWidget(new EditBox(font, (int) x, (int) y, w, 18, text("inline_value")));
        inlineField.setMaxLength(ProgramEditorWire.MAX_BYTES);
        inlineField.setValue(c.value() instanceof DataValue.TextValue t ? t.value() : EditorValueFormat.format(c.value()));
        setFocused(inlineField);
    }
    private void refreshCatalog() {
        catalogWidgets.forEach(this::removeWidget); catalogWidgets.clear(); hintCatalog.clear(); prototypes.clear(); procedurePrototypes.clear(); reporters.clear();
        var body = draft.snapshot();
        String scopeSource = construction == null ? selected : constructionCall;
        var owner = FlowProcedureTools.owner(body, scopeSource);
        var parkedScope = constructionParked < 0 ? Optional.<ScratchValueLayout.ProcedureScope>empty()
            : body.canvas().values().parked().get(constructionParked).procedureScope();
        boolean missingScope = parkedScope.filter(scope -> owner.filter(p -> p.key().equals(scope.key())).isEmpty()).isPresent();
        var locals = missingScope ? Map.<String, DataType>of() : menu.response()
            .map(reply -> FlowProcedureTools.locals(body, scopeSource, workspace.snapshot().archive(), reply.hardware()))
            .orElseGet(() -> owner.map(FlowProgram.Procedure::inputs).orElse(body.inputs()));
        var publicTypes = new TreeMap<String, DataType>();
        workspace.root().cells().forEach((name, cell) -> publicTypes.put(name, cell.type()));
        catalogEntries = java.util.stream.Stream.concat(FlowBlockCatalog.search(locals, FlowProcedureTools.definitions(body), publicTypes, category, searchQuery, advancedStorage).stream(),
            FlowBlockCatalog.extensionEntries(body.canvas().extensions(),category,searchQuery).stream())
            .filter(entry -> !(entry.extensionHat() || entry.command()==Kind.ASK || entry.command()==Kind.TIMER_RESET || entry.command()==Kind.WAIT_UNTIL || entry.command()==Kind.STOP_SCRIPT || entry.value() instanceof InlineGraphEdits.Answer || entry.value() instanceof InlineGraphEdits.RandomRange || entry.value() instanceof InlineGraphEdits.Timer || entry.command()==Kind.WATCH || entry.command()==Kind.STARTUP || entry.command()==Kind.TERMINAL_KEY || entry.command()==Kind.RECEIVE_BROADCAST || entry.command()==Kind.MONITOR_SHOW || entry.command()==Kind.MONITOR_HIDE || entry.command()==Kind.BROADCAST || entry.command()==Kind.BROADCAST_WAIT || entry.taskStart() || entry.taskJoin() || entry.tryLaunch() || entry.value() instanceof InlineGraphEdits.ListSearch || entry.value() instanceof InlineGraphEdits.KeyDown || entry.value() instanceof InlineGraphEdits.Operator edge && edge.operation().numericEdge()) || body.execution() == FlowProgram.Execution.REGISTER_V1).filter(entry -> FlowNamedLists.operation(entry.command()).isEmpty()
                || body.execution()==FlowProgram.Execution.REGISTER_V1 && (!FlowNamedLists.targets(locals).isEmpty() || !FlowNamedLists.targets(publicTypes).isEmpty())).toList();
        if (edgeDialog()) { catalogEntries = catalogEntries.stream().filter(entry -> entry.value() != null && !(entry.value() instanceof InlineGraphEdits.Variable)).toList(); edgePalette(); return; }
        if(sensingOpen())catalogEntries=catalogEntries.stream().filter(e->e.sensingSection()==sensingSection).toList();
        int total = catalogEntries.size(), size = paletteSize();
        if(sensingOpen())sensingControls();
        boolean custom = category == Category.CUSTOM && searchQuery.isBlank();
        if (custom) {
            var define = button(paletteX, TOP, paletteWidth, "define_procedure", () -> procedure(null, null));
            define.active = construction == null;
            catalogWidgets.add(define);
        }
        if (category == Category.EXTENSIONS && searchQuery.isBlank()) {
            var library = button(paletteX, TOP, paletteWidth, "extension_library", this::openExtensions);
            library.active = construction == null;
            catalogWidgets.add(library);
        }
        if (category == Category.VARIABLES && searchQuery.isBlank()) {
            var privateDeclaration = button(paletteX, TOP, paletteWidth, "declare_private", () -> declarations(false));
            var publicDeclaration = button(paletteX, TOP + 24, paletteWidth, "declare_public", () -> declarations(true));
            privateDeclaration.active = publicDeclaration.active = construction == null;
            catalogWidgets.add(privateDeclaration);
            catalogWidgets.add(publicDeclaration);
        }
        if (category == Category.DATA && searchQuery.isBlank()) {
            var declaration=button(paletteX,TOP,paletteWidth,"declare_list",()->{
                listName="";listElement=0;sharedListDeclaration=false;listDeclaration=true;init();
            });
            declaration.active=construction==null && body.execution()==FlowProgram.Execution.REGISTER_V1;
            catalogWidgets.add(declaration);
        }
        if (category == Category.STORAGE && searchQuery.isBlank()) {
            var declaration = button(paletteX, TOP, paletteWidth, "declare_cell", () -> declarations(true));
            declaration.active = construction == null;
            catalogWidgets.add(declaration);
            catalogWidgets.add(button(paletteX, TOP + 24, paletteWidth, advancedStorage ? "advanced_on" : "advanced_off", () -> {
                advancedStorage = !advancedStorage; resetPaletteScroll(); refreshCatalog();
            }));
        }
        paletteOffset = Math.clamp(paletteOffset, 0, paletteMaximum());
        for (int row = 0; row < size && paletteOffset + row < total; row++) {
            var entry = catalogEntries.get(paletteOffset + row);
            var prototype = button(paletteX, TOP + (row + paletteHeaders()) * 24, paletteWidth, entry.label(),
                () -> {
                    if (construction != null && entry.value() == null) return;
                    if (entry.command() != null) edit(null, entry.command());
                    else notice = text(entry.procedure() != null ? "procedure_drag_hint" : "inline_drag_hint");
                });
            prototype.active = (construction == null || entry.value() != null)
                && (entry.command() != Kind.MONITOR_SHOW && entry.command() != Kind.MONITOR_HIDE || !monitorTargets(selected, locals).isEmpty());
            var shape = entry.extensionHat() ? FlowBlockSkin.Shape.HAT : entry.extensionBodies() > 0 ? FlowBlockSkin.Shape.MOUTH : entry.value() != null ? FlowBlockSkin.shape(entry.value())
                : entry.command() != null ? FlowBlockSkin.shape(entry.command()) : FlowBlockSkin.Shape.STACK;
            if (entry.command() == Kind.SLEEP && inlineWait()) canvas.styleWaitPrototype(prototype, font);
            else FlowBlockSkin.prototype(prototype, font, entry.category(), shape, entry.command() == Kind.IF_ELSE || entry.extensionBodies() == 2);
            hintCatalog.put(prototype, entry); catalogWidgets.add(prototype);
            if (entry.command() == Kind.WATCH) continue;
            if (entry.command() != null) prototypes.put(prototype, entry.command());
            else if (entry.procedure() != null) procedurePrototypes.put(prototype, entry);
            else reporters.put(prototype, entry.value());
        }
        if (searchQuery.isBlank() && category == Category.EVENTS)
            catalogWidgets.add(button(paletteX, TOP, paletteWidth, "locate_start", canvas::resetView));
        int halfPalette = (paletteWidth - 4) / 2;
        var previous = button(paletteX, imageHeight - 76, halfPalette, "palette_previous", () -> scrollPaletteTo(paletteOffset - size));
        previous.active = paletteOffset > 0; catalogWidgets.add(previous);
        var next = button(paletteX + halfPalette + 4, imageHeight - 76, halfPalette, "palette_next", () -> scrollPaletteTo(paletteOffset + size));
        next.active = paletteOffset < paletteMaximum(); catalogWidgets.add(next);
    }
    private boolean sensingOpen() { return category == Category.SENSING && searchQuery.isBlank(); }
    private void sensingControls() {
        int column=(paletteWidth-4)/3;
        for(var section:FlowBlockCatalog.SensingSection.values()) {
            var filter=button(paletteX+section.ordinal()*(column+2),TOP,column,section.key(),()->{
                taking=null;takingReporter=null;takingProcedure=null;canvas.cancel();cancelSlot();
                sensingSection=section;resetPaletteScroll();refreshCatalog();
            });
            filter.active=section!=sensingSection;
            filter.setTooltip(Tooltip.create(text(section.key()+"_hint")));catalogWidgets.add(filter);
        }
        int half = (paletteWidth - 4) / 2;
        var examples = button(paletteX, TOP + 24, half, "sensing_examples",
            () -> editBase(() -> documents.accept(Optional.of(sensingSection), () -> navigator.accept(this))));
        examples.active = construction == null;
        FlowBlockSkin.fitToolboxCaption(examples, font);
        var examplesHint = text("sensing_examples_hint");
        examples.setTooltip(Tooltip.create(examplesHint));
        examples.setToolTip(examplesHint);
        catalogWidgets.add(examples);
        var extensions = button(paletteX + half + 4, TOP + 24, paletteWidth - half - 4,
            "sensing_extensions", () -> openExtensions(Optional.of(sensingSection)));
        extensions.active = construction == null;
        FlowBlockSkin.fitToolboxCaption(extensions, font);
        var extensionsHint = text("sensing_extensions_hint");
        extensions.setTooltip(Tooltip.create(extensionsHint));
        extensions.setToolTip(extensionsHint);
        catalogWidgets.add(extensions);
    }
    private int paletteHeaders() { return !searchQuery.isBlank() ? 0 : category == Category.SENSING ? 2 : category == Category.DATA || category == Category.EVENTS ? 1 : category == Category.STORAGE || category == Category.VARIABLES ? 2 : category == Category.CUSTOM || category == Category.EXTENSIONS ? 1 : 0; }
    private int paletteSize() { return Math.max(1, (imageHeight - TOP - 76) / PALETTE_ROW - paletteHeaders()); }
    private int paletteMaximum() { return Math.max(0, catalogEntries.size() - paletteSize()); }

    private void resetPaletteScroll() {
        palettePage = paletteOffset = 0;
        paletteWheelRemainder = 0;
        paletteGrab = -1;
    }

    private void scrollPaletteTo(int offset) {
        offset = Math.clamp(offset, 0, paletteMaximum());
        if (offset == paletteOffset) return;
        taking = null; takingReporter = null; takingProcedure = null; reporterDrag = null;
        canvas.cancel();
        paletteOffset = offset;
        refreshCatalog();
    }

    private record PaletteTrack(int x, int y, int height, int thumbY, int thumbHeight) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + PALETTE_SCROLL_WIDTH && my >= y && my < y + height;
        }
    }

    private PaletteTrack paletteTrack() {
        if (catalogSearch == null || edgeDialog() || paletteMaximum() == 0) return null;
        int y = topPos + TOP + paletteHeaders() * PALETTE_ROW;
        int height = paletteSize() * PALETTE_ROW;
        int thumb = Math.clamp(height * paletteSize() / catalogEntries.size(), 12, height);
        int thumbY = y + (int)Math.round((height - thumb) * (double)paletteOffset / paletteMaximum());
        return new PaletteTrack(leftPos + paletteX + paletteWidth + 1, y, height, thumbY, thumb);
    }

    private void dragPalette(PaletteTrack track, double y) {
        int travel = track.height() - track.thumbHeight();
        if (travel == 0) return;
        double fraction = Math.clamp((y - paletteGrab - track.y()) / travel, 0.0, 1.0);
        scrollPaletteTo((int)Math.round(fraction * paletteMaximum()));
    }
    private void declarations(boolean cells) {
        if (construction != null) return;
        cellDeclarations = cells; initials = true; selectedInitial = null; initialName = initialValue = ""; initialPage = 0; init();
    }
    private void addUpgrade(List<BlockMenuItem> items, ModuleDefinition.Key key, Predicate<FlowProgram.Procedure> eligible,
            BiFunction<FlowProgram.Procedure, FlowProgram.Procedure, Optional<Map<String, DataValue>>> defaults,
            BiConsumer<FlowProgram.Procedure, FlowProgram.Procedure> update) {
        if (draft.snapshot().execution() != FlowProgram.Execution.REGISTER_V1 || ScratchExtensions.reserved(key)) return;
        var versions = new TreeMap<Long, List<FlowProgram.Procedure>>();
        FlowProcedureTools.definitions(draft.snapshot()).stream().filter(this::isCustomDefinition)
            .filter(def -> def.key().id().equals(key.id())).forEach(def ->
                versions.computeIfAbsent(def.key().revision(), unused -> new ArrayList<>()).add(def));
        var matching = versions.getOrDefault(key.revision(), List.of());
        if (matching.size() != 1 || !eligible.test(matching.getFirst())) return;
        var current = matching.getFirst();
        var choices = versions.tailMap(key.revision(), false).values().stream()
            .filter(version -> version.size() == 1).map(List::getFirst).toList();
        if (!choices.isEmpty()) items.add(1, new BlockMenuItem("procedure_upgrade_call", () ->
            navigator.accept(new ComputerScratchUpgradeScreen(menu, editorInventory, current, choices,
                next -> defaults.apply(current, next), next -> {
                    update.accept(current, next);
                    navigator.accept(this);
                }, () -> navigator.accept(this)))));
    }
    private void upgradeValue(ReporterDrag drag, FlowProgram.Procedure current, FlowProgram.Procedure next) {
        var layout = draft.snapshot().canvas().values();
        if (drag.parked != null) {
            var parked = new ArrayList<>(layout.parked());
            int index = drag.parked.index();
            var reporter = parked.get(index);
            var graph = ScratchCallUpgrade.value(reporter.graph(), drag.parked.slot().node(), current, next);
            parked.set(index, new ScratchValueLayout.Reporter(reporter.position(), graph, reporter.arguments(),
                reporter.shadows(), reporter.procedureScope()));
            draft.editValues(layout.withParked(parked));
        } else {
            var step = draft.find(drag.source.call()).orElseThrow();
            var graph = ScratchCallUpgrade.value(inlineGraph(step), drag.source.node(), current, next);
            var bindings = FlowInlineValues.bindings(step);
            validateInline(step, graph, bindings);
            draft.editInline(step.sourceId(), graph, bindings, layout);
        }
        publish();
    }
    private boolean isCustomDefinition(FlowProgram.Procedure definition) {
        return !ScratchExtensions.reserved(definition.key());
    }
    private void goToProcedure(ModuleDefinition.Key key) {
        var matches = FlowProcedureTools.definitions(draft.snapshot()).stream()
            .filter(this::isCustomDefinition).filter(definition -> definition.key().equals(key)).toList();
        if (matches.size() != 1) {
            notice = text(matches.isEmpty() ? "procedure_missing" : "procedure_ambiguous", key.id(), key.revision());
            return;
        }
        selected = matches.getFirst().sourceId(); canvas.selected(selected); canvas.focus(selected);
    }
    private void openProcedureReferences(ModuleDefinition.Key key) {
        navigator.accept(new ComputerScratchReferencesScreen(menu, editorInventory, key, ScratchProcedureReferences.find(draft, key),
            this::focusReference, () -> navigator.accept(this)));
    }
    private void openExtensions() { openExtensions(Optional.empty()); }
    private void openExtensions(Optional<FlowBlockCatalog.SensingSection> section) {
        if (construction != null) return;
        canvas.cancel(); taking = null; takingReporter = null; takingProcedure = null;
        var library = new ComputerScratchExtensionScreen(menu, editorInventory, title, draft, (id, revision) -> {
            draft.setExtension(id, revision);
            publish();
        }, navigator, this::focusReference, () -> { navigator.accept(this); init(); });
        section.ifPresent(library::sensingExtensions);
        navigator.accept(library);
    }
    private void focusReference(ScratchProcedureReferences.Reference ref) {
        selected = ref.parkedIndex() < 0 ? ref.source() : null;
        canvas.selected(selected);
        if (ref.parkedIndex() >= 0) canvas.focusParked(ref.parkedIndex(), ref.node());
        else if (ref.node() != null) canvas.focus(ref.source(), ref.node());
        else canvas.focus(ref.source());
        navigator.accept(this);
    }
    private void procedure(FlowProgram.Procedure definition, FlowProgram.Step call) {
        if (definition!=null && definition.terminalKey().isPresent()) { notice=text("terminal_key_hint");return; }
        if (definition!=null && definition.startup()) { notice=text("startup_hat_hint");return; }
        if (definition!=null && definition.event().isPresent() && call==null) { edit(definition,Kind.RECEIVE_BROADCAST); return; }
        if (construction != null) return;
        canvas.cancel(); taking = null; takingReporter = null; takingProcedure = null;
        String source = call != null ? call.sourceId() : definition == null ? draft.nextId() : definition.sourceId();
        navigator.accept(new ComputerFlowProcedureScreen(menu,editorInventory,title,source,definition,call,(step, labels) -> {
            if (step instanceof FlowProgram.Procedure declaration) {
                var candidate = new FlowDraft(draft.snapshot());
                candidate.setProcedureLabels(source, List.of());
                FlowProcedureTools.define(candidate, declaration, definition != null);
                candidate.setProcedureLabels(source, labels);
                draft.accept(candidate);
            }
            else draft.replace(source,step);
            selected=source; canvas.selected(source); publish(); canvas.focus(source);
        },()->navigator.accept(this),FlowProcedureTools.definitions(draft.snapshot()),workspace.root().cells(),
            draft.snapshot().execution() == FlowProgram.Execution.REGISTER_V1,
            draft.snapshot().canvas().procedureLabels().getOrDefault(source, List.of())));
    }
    private record MonitorTarget(String name, boolean privateLocal) {
        Component label() { return text(privateLocal ? "monitor_private_target" : "monitor_target", name); }
    }
    private List<MonitorTarget> monitorTargets(String source, Map<String, DataType> locals) {
        var result = new ArrayList<MonitorTarget>();
        workspace.root().cells().keySet().stream().sorted().map(name -> new MonitorTarget(name, false)).forEach(result::add);
        locals.keySet().stream().sorted().map(name -> new MonitorTarget(name, true)).forEach(result::add);
        return result;
    }
    private void showMonitorMenu(FlowProgram.Monitor monitor, double x, double y, int page) {
        closeReporterMenu();
        var cells = monitorTargets(monitor.sourceId(), hintLocals(monitor.sourceId()));
        if (cells.isEmpty()) { notice = text("monitor_block_hint"); return; }
        int size = Math.max(1, Math.min(8, (imageHeight - TOP - 52) / 21 - 2));
        int start = page * size, end = Math.min(start + size, cells.size());
        boolean paged = cells.size() > size;
        int width = Math.min(imageWidth - 16, Math.max(92, cells.subList(start, end).stream()
            .mapToInt(cell -> font.width(cell.label())).max().orElse(0) + 12));
        int px = Math.clamp((int)x - leftPos, 8, imageWidth - width - 8);
        int py = Math.clamp((int)y - topPos, TOP, Math.max(TOP, imageHeight - 52 - (end - start + (paged ? 1 : 0)) * 21));
        for (int i = start; i < end; i++, py += 21) {
            var target = cells.get(i);
            var choice = button(px, py, width, target.label(), () -> act(() -> {
                draft.replace(monitor.sourceId(), new FlowProgram.Monitor(monitor.sourceId(), target.name(), monitor.visible(), target.privateLocal()));
                publish();
            }));
            choice.active = target.privateLocal() != monitor.privateLocal() || !target.name().equals(monitor.cell());
            reporterMenu.add(choice);
        }
        if (paged) {
            int half = width / 2;
            var previous = button(px, py, half - 1, "palette_previous", () -> showMonitorMenu(monitor, x, y, page - 1));
            var next = button(px + half, py, width - half, "palette_next", () -> showMonitorMenu(monitor, x, y, page + 1));
            previous.active = start > 0;
            next.active = end < cells.size();
            reporterMenu.add(previous); reporterMenu.add(next);
        }
    }
    private boolean edgeDialog() { return edgeDefinition != null && construction == null; }
    private int edgeInputsY() { return Math.max(138, imageHeight - 146); }
    private int edgePaletteY() { return edgeInputsY() + 44; }
    private int edgeConditionY() { return 102; }
    private int edgeColumns() { return Math.max(1, (imageWidth - 12) / 118); }
    private void initEdgeDefinition() {
        int y = edgeInputsY();
        canvas.bounds(leftPos + 8, topPos + edgeConditionY(), imageWidth - 16, y - edgeConditionY() - 3);
        int third = (imageWidth - 24) / 3;
        edgeCard(8, third, false);
        edgeCard(12 + third, third, true);
        button(16 + 2 * third, 60, third, edgeDecimal ? "watch_number_decimal" : "watch_number_integer", () -> {
            edgeDecimal = !edgeDecimal; init();
        }).setTooltip(Tooltip.create(text("watch_number_hint")));
        button(16 + 2 * third, 81, third, edgeCombine == ScalarOperation.OR_BOOLEAN ? "watch_combine_or" : "watch_combine_and", () -> {
            edgeCombine = edgeCombine == ScalarOperation.OR_BOOLEAN ? ScalarOperation.AND_BOOLEAN : ScalarOperation.OR_BOOLEAN;
            init();
        }).setTooltip(Tooltip.create(text("watch_combine_hint")));
        int tabs = EDGE_CATEGORIES.size(), tabWidth = (imageWidth - 16 - (tabs - 1) * 2) / tabs;
        for (int i = 0; i < tabs; i++) {
            var group = EDGE_CATEGORIES.get(i);
            button(8 + i * (tabWidth + 2), y, tabWidth, "category_" + group.name().toLowerCase(Locale.ROOT), () -> {
                takingReporter = null; canvas.cancel(); cancelSlot(); category = group; resetPaletteScroll();
                searchQuery = ""; catalogSearch.setValue(""); refreshCatalog();
            });
        }
        catalogSearch = field(8, y + 22, imageWidth - 68, "catalog_search", searchQuery, value -> {
            takingReporter = null; canvas.cancel(); searchQuery = value; resetPaletteScroll(); refreshCatalog();
        });
        refreshCatalog();
        int footer = imageHeight - 27, w = (imageWidth - 32) / 5;
        button(8, footer, w, "undo", () -> act(() -> { draft.undo(); publish(); })).active = draft.canUndo();
        button(12 + w, footer, w, "redo", () -> act(() -> { draft.redo(); publish(); })).active = draft.canRedo();
        button(16 + 2 * w, footer, w, "reset_view", canvas::resetView);
        button(20 + 3 * w, footer, w, "cancel", this::onClose);
        button(24 + 4 * w, footer, w, "watch_apply_definition", () -> {
            try { edgeDefinition.confirm().run(); }
            catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
        });
    }
    private void edgeCard(int x, int width, boolean falling) {
        var key = falling ? "watch_add_falling" : "watch_add_rising";
        var control = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + 60, width, 41,
            text(key), () -> act(() -> addEdge(falling)), true));
        control.setIcon((g, ignoredX, ignoredY) -> {
            ScratchEdgeEditor.waveform(g, control.getX() + 8, control.getY() + 4, width - 16, falling, control.active);
            g.drawCenteredString(font, font.plainSubstrByWidth(control.getMessage().getString(), width - 8),
                control.getX() + width / 2, control.getY() + 26, 0xffeeeeee);
        });
        control.setTooltip(Tooltip.create(text(falling ? "watch_falling_hint" : "watch_rising_hint")));
    }
    private void addEdge(boolean falling) {
        var step = draft.find(edgeDefinition.source()).orElseThrow();
        var graph = inlineGraph(step);
        var bindings = FlowInlineValues.bindings(step);
        var values = draft.snapshot().canvas().values();
        var addition = ScratchEdgeEditor.add(graph, bindings, falling, edgeDecimal, edgeCombine);
        var edit = addition.edit();
        validateInline(step, edit.graph(), edit.bindings());
        draft.editInline(step.sourceId(), edit.graph(), edit.bindings(), values);
        publish();
        canvas.revealCondition(addition.edge());
    }
    private void edgePalette() {
        int columns = edgeColumns(), rows = imageHeight - 45 - edgePaletteY() >= 43 ? 2 : 1;
        int count = columns * rows;
        int columnWidth = (imageWidth - 16 - (columns - 1) * 4) / columns;
        palettePage = Math.clamp(palettePage, 0, Math.max(0, (catalogEntries.size() - 1) / count));
        for (int i = 0; i < count && palettePage * count + i < catalogEntries.size(); i++) {
            var entry = catalogEntries.get(palettePage * count + i);
            var button = button(8 + (i % columns) * (columnWidth + 4), edgePaletteY() + (i / columns) * 23,
                columnWidth, entry.label(), () -> notice = text("inline_drag_hint"));
            FlowBlockSkin.prototype(button, font, entry.category(), FlowBlockSkin.shape(entry.value()), false);
            hintCatalog.put(button, entry); catalogWidgets.add(button); reporters.put(button, entry.value());
        }
        var previous = button(imageWidth - 56, edgeInputsY() + 21, 22, "palette_previous", () -> { palettePage--; refreshCatalog(); });
        previous.active = palettePage > 0; catalogWidgets.add(previous);
        var next = button(imageWidth - 30, edgeInputsY() + 21, 22, "palette_next", () -> { palettePage++; refreshCatalog(); });
        next.active = (palettePage + 1) * count < catalogEntries.size(); catalogWidgets.add(next);
    }
    private Component watchTooltip(FlowProgram.Watch watch, boolean shift) {
        var graph = inlineGraph(watch);
        var missing = ScratchExtensionMouth.missingWatch(watch, graph);
        if (missing.isPresent()) return text("extension_missing_block", missing.orElseThrow().id());
        return ScratchExtensionMouth.binding(watch, graph).<Component>map(binding ->
            FlowTypeDetails.tooltip(Component.translatable(binding.command().helpKey()).append("\n").append(text("extension_hat_hint")),
                FlowTypeDetails.signature(binding.template("extension")), shift, menu.nativeIsa1b()))
            .orElseGet(() -> text("watch_hint"));
    }
    private void defineWatch(FlowProgram.Watch existing) {
        if (existing != null && ScratchExtensionMouth.isMouth(existing, inlineGraph(existing))) {
            notice = watchTooltip(existing, false); return;
        }
        canvas.cancel();
        var prototype = existing == null ? FlowBlockPalette.prototype(Kind.WATCH, draft, workspace, List.of(), List.of()) : List.<FlowProgram.Step>of();
        var watch = existing == null ? (FlowProgram.Watch)prototype.get(1) : existing;
        var before = workspace.snapshot();
        var source = draft.snapshot();
        var initial = watchEditorGraph(before, source, watch);
        var editing = new ProgramWorkspace(new ProgramDocument(before.archive(), before.positions(), before.terminal(), Optional.of(
            source.edited(source.inputs(), source.steps(), source.initial(), source.canvas().withValues(source.canvas().values().withParked(List.of())), source.inline()))));
        var editor = new ComputerFlowScreen(menu, editorInventory, title, editing, unused -> {}, () -> {}, () -> navigator.accept(this),
            () -> {}, () -> {});
        editor.edgeDefinition = new EdgeDefinition(watch.sourceId(), () -> applyWatchDefinition(editor, prototype, watch));
        editor.canvas.conditionOnly(watch.sourceId());
        editor.category = Category.OPERATORS;
        editor.searchQuery = "";
        editor.notice = text("watch_definition_hint");
        var bound = new FlowProgram.Watch(watch.sourceId(), watch.handler(), new FlowInput.Expression(watch.sourceId(), "value", initial.bindings().arguments()));
        editor.draft.defineWatch(replaceWatch(prototype, bound), new ProgramDocument.Position(16, 16), bound, initial.graph(),
            source.canvas().values().forBlock(watch.sourceId()));
        editor.draft.clearHistory(); // Preparing the starting condition is not a user edit.
        editing.setFlow(Optional.of(editor.draft.snapshot()));
        navigator.accept(editor);
    }
    private static InlineGraphEdits.Edit watchEditorGraph(ProgramDocument document, FlowDocumentBody body, FlowProgram.Watch watch) {
        var graph = FlowInlineValues.graph(body, watch);
        if (graph != null) {
            String output = watch.condition() instanceof FlowInput.Expression ref ? ref.output() : "value";
            return InlineGraphEdits.prune(new ComputationGraph(graph.inputs(), graph.nodes(),
                Map.of("value", graph.outputs().get(output))), FlowInlineValues.bindings(watch));
        }
        if (watch.condition() instanceof FlowInput.Expression ref) {
            var entry = document.archive().program().entries().get(ref.entry());
            if (entry == null || !entry.graph().outputs().containsKey(ref.output()))
                throw new IllegalArgumentException(text("watch_missing_condition", ref.entry()).getString());
            var original = entry.graph();
            var args = new LinkedHashMap<String, String>();
            original.inputs().keySet().forEach(port -> args.put(port, ref.argument(port)));
            return InlineGraphEdits.prune(new ComputationGraph(original.inputs(), original.nodes(),
                Map.of("value", original.outputs().get(ref.output()))), new FlowCallBindings(args, Map.of()));
        }
        throw new IllegalArgumentException(text("watch_definition_hint").getString());
    }
    private static boolean edgeSample(ComputationGraph.Node node) {
        return node instanceof ComputationGraph.Scalar scalar && scalar.operation().retainedEdge();
    }
    private static List<FlowProgram.Step> replaceWatch(List<FlowProgram.Step> hat, FlowProgram.Watch replacement) {
        return hat.stream().map(step -> step instanceof FlowProgram.Watch ? replacement : step).toList();
    }
    private void applyWatchDefinition(ComputerFlowScreen editor, List<FlowProgram.Step> prototype, FlowProgram.Watch original) {
        var watch = (FlowProgram.Watch)editor.draft.find(original.sourceId()).orElseThrow();
        var graph = editor.inlineGraph(watch);
        var cleaned = InlineGraphEdits.prune(graph, FlowInlineValues.bindings(watch));
        if (cleaned.graph().nodes().values().stream().noneMatch(ComputerFlowScreen::edgeSample))
            throw new IllegalArgumentException(text("watch_requires_sample").getString());
        if (!cleaned.graph().inputs().isEmpty())
            throw new IllegalArgumentException(text("watch_no_private_inputs").getString());
        editor.validateInline(watch, cleaned.graph(), cleaned.bindings());
        var bound = new FlowProgram.Watch(original.sourceId(), original.handler(),
            new FlowInput.Expression(original.sourceId(), "value", cleaned.bindings().arguments()));
        var candidate = new FlowDraft(draft.snapshot());
        bindExtensionReporters(candidate, cleaned.graph());
        var position = prototype.isEmpty() ? canvas.blockPosition(original.sourceId())
            : canvas.newHatPosition(Math.max(FlowBlockCanvas.WIDTH, font.width(FlowBlockCanvas.watchTitle(bound)) + 16));
        candidate.defineWatch(replaceWatch(prototype, bound), position, bound, cleaned.graph(),
            editor.draft.snapshot().canvas().values().forBlock(original.sourceId()));
        draft.accept(candidate);
        selected = prototype.isEmpty() ? original.sourceId() : prototype.getFirst().sourceId();
        publish();
        canvas.focus(selected);
        navigator.accept(this);
    }
    private void edit(FlowProgram.Step step, Kind type) {
        if (step instanceof FlowProgram.StopScript stop) { showStopMenu(stop, leftPos + imageWidth / 2.0, topPos + TOP); return; }
        if (step instanceof FlowProgram.Watch watch) { act(() -> defineWatch(watch)); return; }
        if (step instanceof FlowProgram.Procedure p && FlowProcedureTools.watch(draft.snapshot(), p).isPresent()) {
            act(() -> defineWatch(FlowProcedureTools.watch(draft.snapshot(), p).orElseThrow())); return;
        }
        if (type == Kind.WATCH) { act(() -> defineWatch(null)); return; }
        if ((step instanceof FlowProgram.If || step instanceof FlowProgram.Until)
                && ScratchExtensionMouth.isMouth(step, inlineGraph(step))) {
            notice = text("extension_mouth_hint");
            return;
        }
        if (step instanceof FlowProgram.Monitor monitor) {
            showMonitorMenu(monitor, leftPos + imageWidth / 2.0, topPos + TOP, 0); return;
        }
        if (type == Kind.MONITOR_SHOW || type == Kind.MONITOR_HIDE) { notice = text("monitor_block_hint"); return; }
        if(FlowNamedLists.operation(type).isPresent()) {
            act(()->{var block=namedListPrototype(type);draft.place(List.of(block.step()),new FlowDraft.Socket(null,draft.snapshot().steps().size()),
                new ProgramDocument.Position(16,16),Map.of(block.step().sourceId(),block.graph()));selected=block.step().sourceId();publish();});return;
        }
        if(step!=null && draft.snapshot().inline().containsKey(step.sourceId())
                && FlowNamedLists.target(step,inlineGraph(step),expectedValue(step)).isPresent()) {
            notice=step instanceof FlowProgram.Shared shared?text("named_list_shared_hint",shared.listResult().orElseThrow()):text("named_list_select");return;
        }
        if (construction != null) return;
        canvas.cancel(); taking = null; takingProcedure = null;
        if (step instanceof FlowProgram.Procedure p && p.startup()) { notice=text("startup_hat_hint");return; }
        if (step instanceof FlowProgram.Broadcast || step instanceof FlowProgram.Procedure p && p.event().isPresent()) {
            editBroadcastChannel(step, false); return;
        }
        if (step instanceof FlowProgram.Procedure declaration) { procedure(declaration,null); return; }
        if (step instanceof FlowProgram.TaskJoin join) {
            var definition = FlowProcedureTools.definitions(draft.snapshot()).stream().filter(p -> p.key().equals(join.procedure())).findFirst();
            if (definition.isEmpty()) { notice = text("procedure_missing",join.procedure().id(),join.procedure().revision()); return; }
            procedure(definition.orElseThrow(),join); return;
        }
        if (step instanceof FlowProgram.TryLaunch) { notice = text("try_launch_hint"); return; }
        if (step instanceof FlowProgram.TaskStart launch) {
            var definition = FlowProcedureTools.definitions(draft.snapshot()).stream().filter(p -> p.key().equals(launch.procedure())).findFirst();
            if (definition.isEmpty()) { notice = text("procedure_missing",launch.procedure().id(),launch.procedure().revision()); return; }
            procedure(definition.orElseThrow(),launch); return;
        }
        if (step instanceof FlowProgram.Invoke call) {
            var definition = FlowProcedureTools.definitions(draft.snapshot()).stream().filter(p -> p.key().equals(call.procedure())).findFirst();
            if(definition.isEmpty()){notice=text("procedure_missing",call.procedure().id(),call.procedure().revision());return;}
            procedure(definition.orElseThrow(),call);return;
        }
        if (step != null && !(step instanceof FlowProgram.Shared) && !(step instanceof FlowProgram.Reference) && draft.snapshot().inline().containsKey(step.sourceId())) {
            canvas.inlineSlots().stream().filter(slot -> slot.call().equals(step.sourceId()) && (step instanceof FlowProgram.Evaluate ? slot.target() : !slot.target())).findFirst().ifPresent(this::editSlot);
            return;
        }
        if (type == Kind.TERMINAL_KEY) { notice=text("terminal_key_hint");return; }
        if (type == Kind.STARTUP) { notice=text("startup_hat_hint");return; }
        if (type == Kind.RECEIVE_BROADCAST) { notice=text("broadcast_receive_hint"); return; }
        if (type == Kind.BROADCAST || type == Kind.BROADCAST_WAIT) {
            kind=type; editing=null; creatingBroadcastChannel=false; local=FlowBlockPalette.defaultEventChannel(draft); parameters=true; init(); return;
        }
        if (type == Kind.ASSIGN) {
            var pins = menu.response().orElseThrow().pins();
            var prototype = FlowBlockPalette.prototype(type, draft, workspace, pins.xbus(), pins.simpleIo());
            act(() -> { draft.place(prototype, new FlowDraft.Socket(null, draft.snapshot().steps().size()), new ProgramDocument.Position(16, 16),
                Map.of(prototype.getFirst().sourceId(), zeroGraph())); selected = prototype.getFirst().sourceId(); publish(); });
            return;
        }
        kind = type; editing = step == null ? null : step.sourceId(); parameters = true;
        port = "x0"; local = ""; expression = ""; schema = "{}"; retainedType = null;
        readResult = false; nativeReceive = false; claimReceiveFaults = false; resultLocal = "";
        originalInput = null; inputSource = InputSource.LOCAL; inputLiteral = booleanSlot() ? "false" : "0"; inputOutput = "";
        waitUnit = Optional.empty();
        uneditableLiteral = false;
        originalCall = null; callArguments.clear(); callReturns.clear(); bindingExpression = "";
        editingBindings = reviewedBindings = false; bindingPage = 0;
        serialPortsOpen = false;
        if (serialKind()) port = ""; // Do not infer a binding from XBus or a different Computer.
        if (kind == Kind.MEMORY_READ || kind == Kind.MEMORY_WRITE) port = workspace.root().cells().keySet().stream().sorted().findFirst().orElse("cell");
        if (kind == Kind.REF_ADDRESS || kind == Kind.PUBLIC_SET || kind == Kind.PUBLIC_ADD) port = workspace.root().cells().keySet().stream().sorted().findFirst().orElse("variable");
        if (type == Kind.SIGNAL_READ || type == Kind.SIGNAL_WRITE)
            port = menu.response().flatMap(reply -> reply.pins().simpleIo().stream().findFirst()).orElse("p0");
        if(step instanceof FlowProgram.Reference ref){port=ref.target();local=ref.local();originalInput=ref.value().orElse(null);if(originalInput instanceof FlowInput.Local named)resultLocal=named.name();}
        else if (step instanceof FlowProgram.Shared shared) { port = shared.cell(); originalInput = shared.value(); }
        else if (step instanceof FlowProgram.Memory memory) { port = memory.cell(); local = memory.local(); }
        else if (step instanceof FlowProgram.Atomic atomic) local = atomic.result();
        else if (step instanceof FlowProgram.Read read) { port = read.port(); local = read.local(); retainedType = read.type(); schema = ""; readResult = read.result(); }
        else if (step instanceof FlowProgram.SignalRead read) { port = read.port(); local = read.local(); }
        else if (step instanceof FlowProgram.XBusStatus status) { port = status.port(); local = status.local(); }
        else if (step instanceof FlowProgram.SignalWrite write) { port = write.port(); local = write.local(); }
        else if (step instanceof FlowProgram.Write write) { port = write.port(); local = write.local(); resultLocal = write.result().orElse(""); }
        else if (step instanceof FlowProgram.AwaitReadable wait) { port = wait.port(); resultLocal = wait.result().orElse(""); }
        else if (step instanceof FlowProgram.Evaluate evaluate) {
            expression = evaluate.expression(); originalCall = evaluate;
            evaluate.bindings().ifPresent(bindings -> {
                bindingExpression = expression; callArguments.putAll(bindings.arguments()); callReturns.putAll(bindings.returns());
            });
        }
        else if (step instanceof FlowProgram.If branch) loadInput(branch.condition());
        else if (kind == Kind.SLEEP && (step != null || inlineWait())) {
            var sleep = step instanceof FlowProgram.Sleep existing ? existing : FlowBlockPalette.waitPrototype("wait_default");
            loadInput(sleep.ticks()); waitUnit = sleep.unit();
        }
        else if (step instanceof FlowProgram.Correlation tag) local = tag.local();
        else if (step instanceof FlowProgram.Repeat repeat) loadInput(repeat.count());
        else if (step instanceof FlowProgram.Until until) loadInput(until.condition());
        else if (step instanceof FlowProgram.WaitUntil wait) loadInput(wait.condition());
        else if (step instanceof FlowProgram.SerialSend send) { port = send.port(); local = send.local(); resultLocal = send.result(); }
        else if (step instanceof FlowProgram.SerialReceive receive) { port = receive.port(); local = receive.local(); nativeReceive = receive.nativeNbt(); }
        else if (step instanceof FlowProgram.SerialStatus status) { port = status.port(); local = status.local(); claimReceiveFaults = status.claimReceiveFaults(); }
        if (kind == Kind.REPEAT || kind == Kind.UNTIL || kind == Kind.WAIT_UNTIL) notice = inputHint();
        init();
    }
    private void parameterEditor() {
        if (serialPortsOpen) { serialPortEditor(); return; }
        if (editingBindings) { callBindingEditor(); return; }
        if (kind==Kind.RECEIVE_BROADCAST || kind==Kind.BROADCAST || kind==Kind.BROADCAST_WAIT) {
            var input=field(8,54,imageWidth-44,"broadcast_name",local,value->local=value); input.setMaxLength(64);
            var channels=FlowBlockPalette.eventChannels(draft);
            if (!creatingBroadcastChannel)
                button(imageWidth-32,54,24,"next_pin",()->input.setValue(next(channels,local))).active=!channels.isEmpty();
            notice=text(kind==Kind.RECEIVE_BROADCAST?"broadcast_receive_hint":kind==Kind.BROADCAST_WAIT?"broadcast_wait_hint":"broadcast_hint");
            int half=(imageWidth-20)/2;
            button(8,imageHeight-27,half,"apply",()->act(()->{
                String channel=local.strip();
                if (creatingBroadcastChannel && FlowBlockPalette.eventChannels(draft).contains(channel))
                    throw new IllegalArgumentException(text("broadcast_channel_exists").getString());
                if(editing!=null) replaceBroadcastChannel(draft.find(editing).orElseThrow(),channel);
                else { String id=draft.nextId(); draft.insertAfter(selected,new FlowProgram.Broadcast(id,channel,kind==Kind.BROADCAST_WAIT)); selected=id; }
                creatingBroadcastChannel=parameters=false; publish();
            }));
            button(12+half,imageHeight-27,half,"cancel",()->{creatingBroadcastChannel=parameters=false;init();}); return;
        }
        if (kind == Kind.EVALUATE) {
            field(8, 54, imageWidth - 16, "expression", expression, value -> expression = value);
            button(8, 78, imageWidth - 16, "call_bindings", () -> act(this::openCallBindings));
            // Visible existing entry choices; no hidden expression body or special calculation node.
            int row = 0;
            for (String name : workspace.entryNames()) {
                if (104 + row * 22 >= imageHeight - 55) break;
                button(8, 104 + row++ * 22, imageWidth - 16, Component.literal(name), () -> { expression = name; init(); });
            }
        } else if(kind.name().startsWith("REF_")) {
            var target=field(8,54,imageWidth-44,kind==Kind.REF_ADDRESS?"memory_cell":"reference_local",port,value->port=value);
            var names=kind==Kind.REF_ADDRESS?workspace.root().cells().keySet().stream().sorted().toList():List.<String>of();
            button(imageWidth-32,54,24,"next_pin",()->target.setValue(next(names,port))).active=!names.isEmpty();
            if(kind!=Kind.REF_WRITE)field(8,82,imageWidth-16,"memory_result",local,value->local=value);
            if(kind==Kind.REF_WRITE||kind==Kind.REF_SAME)field(8,kind==Kind.REF_WRITE?82:110,imageWidth-16,kind==Kind.REF_SAME?"reference_other":"memory_value",resultLocal,value->{resultLocal=value;originalInput=value.isBlank()?null:new FlowInput.Local(value);});
            notice=text("reference_hint");
        } else if (kind == Kind.PUBLIC_SET || kind == Kind.PUBLIC_ADD) {
            var cell = field(8, 54, imageWidth - 44, "public_variable", port, value -> port = value);
            var names = workspace.root().cells().keySet().stream().sorted().toList();
            button(imageWidth - 32, 54, 24, "next_pin", () -> cell.setValue(next(names, port))).active = !names.isEmpty();
            notice = text("public_hint");
        } else if (kind == Kind.ATOMIC) {
            field(8, 54, imageWidth - 16, "memory_result", local, value -> local = value);
            notice = text("atomic_hint");
        } else if (kind.name().startsWith("MEMORY_")) {
            if (kind == Kind.MEMORY_READ || kind == Kind.MEMORY_WRITE) {
                var cell = field(8, 54, imageWidth - 44, "memory_cell", port, value -> port = value);
                var names = workspace.root().cells().keySet().stream().sorted().toList();
                button(imageWidth - 32, 54, 24, "next_pin", () -> cell.setValue(next(names, port))).active = !names.isEmpty();
            }
            if (kind != Kind.MEMORY_BEGIN && kind != Kind.MEMORY_ROLLBACK)
                field(8, 82, imageWidth - 16, kind == Kind.MEMORY_WRITE ? "memory_value" : "memory_result", local, value -> local = value);
            notice = text("memory_hint");
        } else if (kind == Kind.CORRELATION) {
            field(8, 54, imageWidth - 16, "correlation_local", local, value -> local = value);
            notice = text("correlation_hint");
        } else if (kind == Kind.FOREVER) {
            notice = text("forever_hint");
        } else if (kind == Kind.STOP_SCRIPT) {
            notice = text("stop_script_hint");
        } else if (kind == Kind.REPEAT || kind == Kind.UNTIL || conditionalKind() || kind == Kind.WAIT_UNTIL
                || kind == Kind.SLEEP && inlineWait()) {
            inputEditor();
        } else if (kind == Kind.SLEEP) {
            field(8, 54, imageWidth - 16, "ticks_local", local, value -> local = value);
            notice = text("sleep_hint");
        } else {
            boolean signal = kind == Kind.SIGNAL_READ || kind == Kind.SIGNAL_WRITE;
            var pins = serialKind() ? List.<String>of() : menu.response().map(reply -> signal ? reply.pins().simpleIo() : reply.pins().xbus()).orElse(List.of());
            var pinField = field(8, 54, imageWidth - 44, serialKind() ? "serial_port" : "pin", port, value -> port = value);
            var pinHint = serialKind() ? text("serial_hint") : text(signal ? "available_signals" : "available_pins", pins.isEmpty() ? "—" : String.join(", ", pins));
            if (kind == Kind.XBUS_STATUS) pinHint = pinHint.copy().append(" · ").append(text("xbus_status_hint"));
            pinField.setTooltip(Tooltip.create(pinHint)); notice = pinHint;
            if (serialKind()) {
                var browser = button(imageWidth - 32, 54, 24, text("serial_ports"), () -> {
                    serialPortsOpen = true;
                    serialWireless = false;
                    refreshSerialPorts();
                });
                browser.setTooltip(Tooltip.create(text("serial_ports_hint")));
            } else {
                var nextPin = button(imageWidth - 32, 54, 24, "next_pin", () ->
                    pinField.setValue(pins.get((pins.indexOf(port) + 1) % pins.size())));
                nextPin.active = !pins.isEmpty(); nextPin.setTooltip(Tooltip.create(pinHint));
            }
            if (kind != Kind.READABLE) field(8, 82, imageWidth - 16, "local", local, value -> local = value);
            if (kind == Kind.READ) {
                var value = field(8, 110, imageWidth - 16, retainedType == null ? "record_sample" : "keep_type", schema, text -> schema = text);
                if (retainedType != null) value.setTooltip(Tooltip.create(text("retained_type", retainedType instanceof DataType.RecordType rec ? rec.fields().keySet().toString() : "ANY")));
                var mode = button(8, 138, imageWidth - 16, text("result_mode", text(readResult ? "enabled" : "disabled")), () -> { readResult = !readResult; init(); });
                mode.setTooltip(Tooltip.create(text("read_result_hint")));
            } else if (kind == Kind.SERIAL_SEND) {
                field(8, 110, imageWidth - 16, "serial_result_local", resultLocal, text -> resultLocal = text);
            } else if (kind == Kind.SERIAL_RECEIVE) {
                button(8, 110, imageWidth - 16, text(nativeReceive ? "serial_format_nbt" : "serial_format_record"), () -> { nativeReceive = !nativeReceive; init(); });
            } else if (kind == Kind.SERIAL_STATUS) {
                var mode = button(8, 110, imageWidth - 16, text(claimReceiveFaults ? "serial_status_claim" : "serial_status_read"), () -> { claimReceiveFaults = !claimReceiveFaults; init(); });
                mode.setTooltip(Tooltip.create(text("serial_claim_hint")));
                notice = text("serial_claim_hint");
            } else if (kind == Kind.WRITE || kind == Kind.READABLE) {
                var result = field(8, kind == Kind.WRITE ? 110 : 82, imageWidth - 16, "result_local", resultLocal, text -> resultLocal = text);
                result.setTooltip(Tooltip.create(text("operation_result_hint")));
            }
        }
        int half = (imageWidth - 20) / 2;
        button(8, imageHeight - 27, half, "apply", () -> act(() -> {
            if (kind == Kind.EVALUATE && !bindingExpression.equals(expression)
                    && !(originalCall != null && originalCall.expression().equals(expression) && !reviewedBindings)) {
                openCallBindings(); return;
            }
            String id = editing == null ? draft.nextId() : editing;
            FlowProgram.Step step = switch (kind) {
                case MONITOR_SHOW, MONITOR_HIDE -> throw new IllegalStateException("Use the inline monitor dropdown");
                case WATCH,STARTUP,TERMINAL_KEY,RECEIVE_BROADCAST,BROADCAST,BROADCAST_WAIT -> throw new IllegalStateException("Events use their own editor");
                case REF_ADDRESS,REF_READ,REF_WRITE,REF_READONLY,REF_SAME -> new FlowProgram.Reference(id,FlowProgram.ReferenceKind.valueOf(kind.name().substring(4)),port,kind==Kind.REF_WRITE?"":local,kind==Kind.REF_WRITE||kind==Kind.REF_SAME?Optional.ofNullable(originalInput==null?new FlowInput.Local(resultLocal):originalInput):Optional.empty());
                case PUBLIC_SET, PUBLIC_ADD -> new FlowProgram.Shared(id, port,
                    originalInput == null ? FlowBlockPalette.publicDefault(workspace, port, kind == Kind.PUBLIC_ADD) : originalInput, kind == Kind.PUBLIC_ADD);
                case ATOMIC -> new FlowProgram.Atomic(id, local);
                case END_ATOMIC -> new FlowProgram.EndAtomic(id);
                case MEMORY_BEGIN, MEMORY_READ, MEMORY_WRITE, MEMORY_COMMIT, MEMORY_ROLLBACK -> new FlowProgram.Memory(id,
                    FlowProgram.MemoryKind.valueOf(kind.name().substring("MEMORY_".length())),
                    kind == Kind.MEMORY_READ || kind == Kind.MEMORY_WRITE ? port : "",
                    kind == Kind.MEMORY_BEGIN || kind == Kind.MEMORY_ROLLBACK ? "" : local);
                case CORRELATION -> new FlowProgram.Correlation(id, local);
                case SERIAL_SEND -> new FlowProgram.SerialSend(id, port, local, resultLocal);
                case SERIAL_RECEIVE -> new FlowProgram.SerialReceive(id, port, local, nativeReceive);
                case SERIAL_STATUS -> new FlowProgram.SerialStatus(id, port, local, claimReceiveFaults);
                case XBUS_STATUS -> new FlowProgram.XBusStatus(id, port, local);
                case READ -> new FlowProgram.Read(id, port, local, schema.isBlank() && retainedType != null ? retainedType : sampleType(schema), readResult);
                case WRITE -> new FlowProgram.Write(id, port, local, resultLocal.isBlank() ? Optional.empty() : Optional.of(resultLocal));
                case SIGNAL_READ -> new FlowProgram.SignalRead(id, port, local);
                case SIGNAL_WRITE -> new FlowProgram.SignalWrite(id, port, local);
                case READABLE -> new FlowProgram.AwaitReadable(id, port, resultLocal.isBlank() ? Optional.empty() : Optional.of(resultLocal));
                case EVALUATE -> originalCall != null && originalCall.expression().equals(expression) && !reviewedBindings
                    ? originalCall : new FlowProgram.Evaluate(id, expression, new FlowCallBindings(nonblank(callArguments), nonblank(callReturns)));
                case ASSIGN, LIST_APPEND_LOCAL, LIST_INSERT_LOCAL, LIST_REMOVE_LOCAL, LIST_REPLACE_LOCAL, LIST_CLEAR -> throw new IllegalStateException("Inline assignment does not use the parameter form");
                case IF, IF_ELSE -> new FlowProgram.If(id, input());
                case OTHERWISE -> new FlowProgram.Otherwise(id);
                case END_IF -> new FlowProgram.EndIf(id);
                case FOREVER -> new FlowProgram.Forever(id);
                case END_FOREVER -> new FlowProgram.EndForever(id);
                case REPEAT -> new FlowProgram.Repeat(id, input());
                case END_REPEAT -> new FlowProgram.EndRepeat(id);
                case UNTIL -> new FlowProgram.Until(id, input());
                case END_UNTIL -> new FlowProgram.EndUntil(id);
                case STOP_SCRIPT -> new FlowProgram.StopScript(id);
                case WAIT_UNTIL -> new FlowProgram.WaitUntil(id, input());
                case TIMER_RESET -> new FlowProgram.TimerReset(id);
                case ASK -> editing == null ? new FlowProgram.Ask(id, FlowBlockPalette.defaultQuestion()) : draft.find(editing).orElseThrow();
                case SLEEP -> {
                    if (!inlineWait()) yield new FlowProgram.Sleep(id, local);
                    var amount = input();
                    var unit = waitUnit.isEmpty() && amount instanceof FlowInput.Literal literal && literal.value() instanceof DataValue.DecimalValue
                        ? Optional.of(FlowProgram.WaitUnit.TICKS) : waitUnit;
                    yield new FlowProgram.Sleep(id, amount, unit);
                }
            };
            if (editing == null && conditionalKind()) id = draft.insertBranch(selected, ((FlowProgram.If) step).condition(), kind == Kind.IF_ELSE);
            else if (editing == null && step instanceof FlowProgram.Atomic atomic) id = draft.insertAtomic(selected, atomic.result());
            else if (editing == null && kind == Kind.FOREVER) id = draft.insertForever(selected);
            else if (editing == null && step instanceof FlowProgram.Repeat repeat) id = draft.insertRepeat(selected, repeat.count());
            else if (editing == null && step instanceof FlowProgram.Until until) id = draft.insertUntil(selected, until.condition());
            else if (editing == null) draft.insertAfter(selected, step); else draft.replace(editing, step);
            selected = id; parameters = false; publish();
        }));
        button(12 + half, imageHeight - 27, half, "cancel", () -> { parameters = false; init(); });
    }
    private void openCallBindings() {
        if (!workspace.entryNames().contains(expression)) throw new IllegalArgumentException(text("call_missing").getString());
        if (!bindingExpression.equals(expression)) {
            callArguments.clear(); callReturns.clear();
            workspace.entry(expression).inputs().keySet().forEach(name -> callArguments.put(name, name));
            workspace.entry(expression).outputs().keySet().forEach(name -> callReturns.put(name, name));
            bindingExpression = expression;
        }
        reviewedBindings = true; editingBindings = true; bindingPage = 0; notice = text("call_hint");
    }
    private record CallRow(String port, boolean returned) {}
    private List<CallRow> callRows() {
        // Keep stale references visible and editable instead of silently removing them after a graph edit.
        var inputs = new TreeSet<>(workspace.entry(expression).inputs().keySet()); inputs.addAll(callArguments.keySet());
        var outputs = new TreeSet<>(workspace.entry(expression).outputs().keySet()); outputs.addAll(callReturns.keySet());
        var rows = new ArrayList<CallRow>(); inputs.forEach(name -> rows.add(new CallRow(name, false)));
        outputs.forEach(name -> rows.add(new CallRow(name, true))); return rows;
    }
    private void callBindingEditor() {
        var rows = callRows(); int count = Math.max(1, (imageHeight - 114) / 26);
        int last = Math.max(0, (rows.size() - 1) / count); bindingPage = Math.clamp(bindingPage, 0, last);
        button(8, 30, imageWidth - 16, Component.literal(expression), () -> {}).active = false;
        int half = (imageWidth - 20) / 2;
        for (int i = 0; i < count && bindingPage * count + i < rows.size(); i++) {
            var row = rows.get(bindingPage * count + i); var values = row.returned() ? callReturns : callArguments;
            var label = text(row.returned() ? "call_return" : "call_argument", row.port());
            button(8, 56 + i * 26, half, label, () -> {}).active = false;
            var field = field(12 + half, 57 + i * 26, half, row.returned() ? "call_target" : "call_source",
                values.getOrDefault(row.port(), ""), value -> values.put(row.port(), value));
            field.setMessage(label); field.setTooltip(Tooltip.create(label.copy().append(" ").append(text(row.returned() ? "call_target" : "call_source"))));
        }
        button(8, imageHeight - 27, 28, "previous", () -> { bindingPage--; init(); }).active = bindingPage > 0;
        button(40, imageHeight - 27, 28, "next", () -> { bindingPage++; init(); }).active = bindingPage < last;
        button(72, imageHeight - 27, imageWidth - 80, "call_back", () -> { editingBindings = false; init(); });
    }
    private static Map<String, String> nonblank(Map<String, String> values) {
        var result = new LinkedHashMap<String, String>(); values.forEach((port, local) -> { if (!local.isBlank()) result.put(port, local); }); return result;
    }
    private static DataType.RecordType sampleType(String text) {
        var value = EditorValueFormat.parse(EditorValueFormat.Kind.JSON, text);
        if (!(value instanceof DataValue.RecordValue)) throw new IllegalArgumentException("Read sample must be a named record");
        return (DataType.RecordType)DataType.typeOf(value);
    }
    private boolean conditionalKind() { return kind == Kind.IF || kind == Kind.IF_ELSE; }
    private boolean inlineWait() { return draft.snapshot().execution() == FlowProgram.Execution.REGISTER_V1; }
    private boolean booleanSlot() { return kind == Kind.UNTIL || conditionalKind() || kind == Kind.WAIT_UNTIL; }
    private Component inputHint() {
        if (inputSource == InputSource.LITERAL && uneditableLiteral) return text("invalid_literal", booleanSlot() ? "BOOLEAN" : "INTEGER");
        var hint = text(conditionalKind() ? "branch_hint" : kind == Kind.UNTIL ? "until_hint" : kind == Kind.WAIT_UNTIL ? "wait_until_hint" : kind == Kind.SLEEP ? "sleep_hint" : "repeat_hint").copy();
        if (kind == Kind.SLEEP) hint.append(" ").append(FlowInlineValues.waitUnitLabel(waitUnit.orElse(FlowProgram.WaitUnit.TICKS)));
        if (inputSource != InputSource.LITERAL) hint.append(" ").append(text(inputSource == InputSource.EXPRESSION ? "input_expression_hint" : "input_local_hint"));
        return hint;
    }
    private void loadInput(FlowInput input) {
        originalInput = input;
        if (input instanceof FlowInput.Local named) local = named.name();
        else if (input instanceof FlowInput.Literal literal) {
            inputSource = InputSource.LITERAL;
            try { inputLiteral = EditorValueFormat.format(literal.value()); }
            catch (IllegalArgumentException invalid) { inputLiteral = ""; uneditableLiteral = true; }
        }
        else if (input instanceof FlowInput.Expression graph) { inputSource = InputSource.EXPRESSION; expression = graph.entry(); inputOutput = graph.output(); }
    }
    private FlowInput input() {
        return switch (inputSource) {
            case LOCAL -> new FlowInput.Local(local);
            case LITERAL -> new FlowInput.Literal(kind == Kind.SLEEP && inlineWait() ? waitNumber(inputLiteral)
                : EditorValueFormat.parse(booleanSlot() ? EditorValueFormat.Kind.BOOLEAN : EditorValueFormat.Kind.INTEGER, inputLiteral));
            case EXPRESSION -> originalInput instanceof FlowInput.Expression graph && graph.entry().equals(expression) && graph.output().equals(inputOutput)
                ? graph : new FlowInput.Expression(expression, inputOutput);
        };
    }
    private void inputEditor() {
        int half = (imageWidth - 20) / 2;
        int third = (imageWidth - 24) / 3;
        for (var source : InputSource.values()) button(8 + source.ordinal() * (third + 4), 30, third,
            "input_" + source.name().toLowerCase(Locale.ROOT), () -> { inputSource = source; notice = inputHint(); init(); }).active = inputSource != source;
        if (inputSource == InputSource.LOCAL) {
            field(8, 60, imageWidth - 16, booleanSlot() ? "condition" : "count_local", local, value -> local = value);
        } else if (inputSource == InputSource.LITERAL) {
            var value = field(8, 60, imageWidth - 16, booleanSlot() ? "boolean_value" : kind == Kind.SLEEP ? "wait_number" : "integer_value", inputLiteral, text -> {
                inputLiteral = text; uneditableLiteral = false; notice = inputHint();
            });
            if (booleanSlot()) {
                button(8, 88, half, "input_true", () -> value.setValue("true"));
                button(12 + half, 88, half, "input_false", () -> value.setValue("false"));
            }
        } else {
            var entry = field(8, 60, imageWidth - 44, "input_entry", expression, value -> expression = value);
            button(imageWidth - 32, 60, 24, "next_expression", () -> {
                expression = next(workspace.entryNames(), expression); inputOutput = ""; notice = inputHint(); init();
            }).active = !workspace.entryNames().isEmpty();
            var output = field(8, 88, imageWidth - 44, "input_output", inputOutput, value -> inputOutput = value);
            var nextOutput = button(imageWidth - 32, 88, 24, "next_output", () -> output.setValue(next(inputOutputs(), inputOutput)));
            nextOutput.active = !inputOutputs().isEmpty();
            entry.setResponder(value -> { expression = value; nextOutput.active = !inputOutputs().isEmpty(); });
        }
        if (kind == Kind.SLEEP) for (var unit : FlowProgram.WaitUnit.values()) {
            var choice = button(8 + unit.ordinal() * (third + 4), 117, third, FlowInlineValues.waitUnitLabel(unit), () -> {
                waitUnit = Optional.of(unit); notice = inputHint(); init();
            });
            choice.active = unit != waitUnit.orElse(FlowProgram.WaitUnit.TICKS);
        }
    }
    private List<String> inputOutputs() {
        return workspace.entryNames().contains(expression) ? List.copyOf(workspace.entry(expression).outputs().keySet()) : List.of();
    }
    private static String next(List<String> values, String current) { return values.get((values.indexOf(current) + 1) % values.size()); }
    private void listDeclarationEditor() {
        var types = FlowNamedLists.ELEMENTS;
        int half = (imageWidth - 20) / 2;
        button(8, 30, imageWidth - 16, "declare_list", () -> {}).active = false;
        field(8, 54, half, "list_name", listName, value -> listName = value);
        button(12 + half, 53, half, text("list_element_type", ComputerGraphPorts.typeLabel(types.get(listElement))), () -> {
            listElement = (listElement + 1) % types.size(); init();
        }).setTooltip(Tooltip.create(text("list_type_hint")));
        button(8, 78, imageWidth - 16, sharedListDeclaration ? "list_scope_shared" : "list_scope_private", () -> {
            sharedListDeclaration = !sharedListDeclaration; init();
        }).setTooltip(Tooltip.create(text("list_scope_hint")));
        button(8, imageHeight - 27, half, "apply", () -> act(() -> {
            String name = listName.strip();
            if (name.isEmpty()) throw new IllegalArgumentException(text("list_name_blank").getString());
            if (sharedListDeclaration) {
                if (workspace.root().cells().containsKey(name)) throw new IllegalArgumentException(text("list_public_name_exists").getString());
                draft.declarePublicList(workspace, name, types.get(listElement));
            } else {
                if (draft.snapshot().inputs().containsKey(name)) throw new IllegalArgumentException(text("list_name_exists").getString());
                draft.declareList(name, types.get(listElement));
            }
            listDeclaration = false; selected = null; resetPaletteScroll(); publish();
        }));
        button(12 + half, imageHeight - 27, half, "cancel", () -> { listDeclaration = false; init(); });
    }
    private void initialEditor() {
        int half = (imageWidth - 20) / 2;
        button(8, 30, imageWidth - 16, cellDeclarations ? "memory_cells" : "initials", () -> {
            cellDeclarations = !cellDeclarations; selectedInitial = null; initialName = initialValue = ""; initialPage = 0; init();
        });
        var nameField = field(8, 54, half, cellDeclarations ? "memory_cell" : "initial_name", initialName, value -> initialName = value);
        var kinds = EditorValueFormat.Kind.values();
        button(12 + half, 54, half, Component.literal(kinds[literalKind].name()), () -> { literalKind = (literalKind + 1) % kinds.length; init(); });
        field(8, 82, imageWidth - 16, "initial_value", initialValue, value -> initialValue = value);
        button(8, 108, half, "save_initial", () -> act(() -> {
            var value = EditorValueFormat.parse(kinds[literalKind], initialValue);
            if (cellDeclarations) draft.editPublicVariable(workspace, selectedInitial, initialName, value);
            else draft.editPrivateVariable(workspace, selectedInitial, initialName, value);
            selectedInitial = initialName; publish();
        }));
        button(12 + half, 108, half, "remove", () -> act(() -> {
            if (cellDeclarations) draft.removePublicVariable(workspace, initialName);
            else draft.removePrivateVariable(workspace, initialName);
            selectedInitial = null; initialName = initialValue = ""; publish();
        }));
        var snapshotButton = button(8, 136, imageWidth - 16, "initial_snapshot", this::browseInitialSnapshot);
        snapshotButton.setTooltip(Tooltip.create(text("initial_snapshot_help")));
        snapshotButton.active = canImportInitialSnapshot();
        nameField.setResponder(value -> { initialName = value; snapshotButton.active = canImportInitialSnapshot(); });
        var displayed = new LinkedHashMap<String, DataValue>(draft.snapshot().initial().fields());
        if (cellDeclarations) { displayed.clear(); workspace.root().cells().forEach((name, cell) -> displayed.put(name, cell.initial())); }
        var values = new ArrayList<>(displayed.entrySet()); int size = Math.max(1, (imageHeight - 214) / 22);
        initialPage = Math.clamp(initialPage, 0, Math.max(0, (values.size() - 1) / size));
        for (int row = 0; row < size && initialPage * size + row < values.size(); row++) {
            var entry = values.get(initialPage * size + row);
            button(8, 160 + row * 22, imageWidth - 16, Component.literal(entry.getKey()),
                () -> act(() -> showInitial(entry.getKey(), entry.getValue(), initialLiteral(entry.getValue()))));
        }
        button(8, imageHeight - 27, 28, "previous", () -> { initialPage--; init(); }).active = initialPage > 0;
        button(40, imageHeight - 27, 28, "next", () -> { initialPage++; init(); }).active = (initialPage + 1) * size < values.size();
        button(72, imageHeight - 27, imageWidth - 168, "new_initial", () -> {
            selectedInitial = null; initialName = initialValue = ""; literalKind = EditorValueFormat.Kind.INTEGER.ordinal(); init();
        });
        button(imageWidth - 88, imageHeight - 27, 80, "back", () -> { initials = false; init(); });
    }
    private boolean canImportInitialSnapshot() {
        return !cellDeclarations && selectedInitial != null && selectedInitial.equals(initialName)
            && draft.snapshot().inputs().containsKey(selectedInitial) && !menu.debuggerInputs().fields().isEmpty();
    }
    private void browseInitialSnapshot() {
        if (!canImportInitialSnapshot()) return;
        String destination = selectedInitial;
        var frozen = menu.debuggerInputs();
        navigator.accept(new ComputerValueTreeScreen<>(menu, editorInventory, title, this, frozen, path -> {
            var value = path.resolve(frozen);
            String literal = initialLiteral(value);
            draft.replaceInitial(destination, value);
            showInitial(destination, value, literal);
            publish();
            init();
            navigator.accept(this);
        }, text("apply_snapshot_initial"), text("snapshot_initial_hint", destination), () -> navigator.accept(this)));
    }
    private static String initialLiteral(DataValue value) {
        String literal = value instanceof DataValue.TextValue text ? text.value() : EditorValueFormat.format(value);
        if (literal.length() > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("Initial value is too large for literal editing");
        return literal;
    }
    private void showInitial(String name, DataValue value, String literal) {
        literalKind = switch (value) {
            case DataValue.IntegerValue ignored -> EditorValueFormat.Kind.INTEGER.ordinal();
            case DataValue.DecimalValue ignored -> EditorValueFormat.Kind.DECIMAL.ordinal();
            case DataValue.TextValue ignored -> EditorValueFormat.Kind.TEXT.ordinal();
            case DataValue.BooleanValue ignored -> EditorValueFormat.Kind.BOOLEAN.ordinal();
            case DataValue.NbtValue ignored -> EditorValueFormat.Kind.NBT.ordinal();
            default -> EditorValueFormat.Kind.JSON.ordinal();
        };
        selectedInitial = initialName = name; initialValue = literal;
    }
    private void publish() {
        if (selected != null && selectedStep().isEmpty()) selected = null;
        workspace.setFlow(Optional.of(draft.snapshot())); changed.run(); notice = text("draft_changed");
        if (edgeDefinition != null) { notice = text("watch_definition_hint"); return; }
        try { validateHardware(workspace.snapshot()); }
        catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
    }
    private void install() {
        act(() -> {
            var document = workspace.snapshot(); validateHardware(document);
            ProgramDocumentWire.encode(document, menu.response().orElseThrow().archiveLimits());
            menu.submit(document, ComputerRuntime.MemoryPolicy.RESET); waiting = true; notice = text("installing");
        });
    }
    private void validateHardware(ProgramDocument document) {
        var authority = menu.response().orElseThrow(); document.validate(authority.hardware(), authority.pins());
    }
    private Component resourceSummary() {
        if (draft.snapshot().execution() == FlowProgram.Execution.LEGACY_GRAPH) return text("legacy_backend");
        var hardware = menu.response().map(ProgramEditorResponse::hardware);
        if (hardware.isEmpty()) return text("install_hardware_pending");
        try {
            var limits = hardware.orElseThrow();
            var document = workspace.snapshot();
            var program = document.flow().orElseThrow().program(document.archive());
            var usage = program.usage(limits);
            String families = com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowXBus.image(program).instructionFamilies().stream()
                .map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily::id)
                .collect(java.util.stream.Collectors.joining(", "));
            return text("assembly_resources", usage.programUnits(), limits.programUnits(), usage.constantUnits(), usage.registers(), limits.registerSlots(), limits.workPerSlice())
                .copy().append("\n").append(text("instruction_families", families));
        } catch (RuntimeException invalid) { return text("invalid", String.valueOf(invalid.getMessage())); }
    }
    private void act(Runnable action) {
        try { action.run(); } catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
        init();
    }
    private Optional<FlowProgram.Step> selectedStep() { return draft.find(selected); }
    private boolean canvasVisible() { return workspace.flow().isPresent() && !listDeclaration && !initials && !parameters && construction == null && !waiting; }
    @Override public void mouseMoved(double x,double y) {pointerX=x;pointerY=y;super.mouseMoved(x,y);}
    private boolean primaryGestureActive() {
        return paletteGrab >= 0 || reporterDrag != null || takingReporter != null
            || (canvasVisible() && canvas.active());
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        pointerX=x;pointerY=y;
        if (button != 0 && primaryGestureActive()) return true;
        if(!reporterMenu.isEmpty()) {
            if(button==0)for(var widget:List.copyOf(reporterMenu))if(widget.isMouseOver(x,y)){widget.mouseClicked(x,y,button);return true;}
            closeReporterMenu();return true;
        }
        if (variableSlot != null) {
            if (button == 0) for (var widget : List.copyOf(variableWidgets)) {
                if (widget.isMouseOver(x, y)) { widget.mouseClicked(x, y, button); return true; }
            }
            closeVariableChoices();
            return true; // Dismissal must not drag/delete the covered block.
        }
        if (inlineField != null) {
            if (inlineField.isMouseOver(x, y)) return super.mouseClicked(x, y, button);
            // Leaving a literal is an edit, not an implicit Escape. Invalid input
            // retains focus and prevents the clicked action from discarding it.
            if (button != 0) return true;
            boolean inConstructor = construction != null;
            commitSlot();
            if (inlineField != null || inConstructor) return true;
        }
        if (button == 0 && (canvasVisible() || construction != null)) {
            var track = paletteTrack();
            if (track != null && track.contains(x, y)) {
                paletteWheelRemainder = 0;
                paletteGrab = y >= track.thumbY() && y < track.thumbY() + track.thumbHeight()
                    ? y - track.thumbY() : track.thumbHeight() / 2.0;
                dragPalette(track, y);
                return true;
            }
        }
        if (construction != null && button == 0) {
            for (int i = constructionFocusSlots.size()-1;i >= 0;i--) {
                var slot = constructionFocusSlots.get(i);
                if (constructionVisible(x, y) && slot.contains(x, y)) {
                    if (presetHit(slot, x) && showExtensionPresets(new ExtensionPreset(slot, -1, construction), x, y))
                        return true;
                    if (slot.label().endsWith(" ▾") && showMathFunctions(new ExtensionPreset(slot, -1, construction), x, y)) return true;
                    editConstructionNode(slot.node(), slot.x(), slot.y(), Math.min(160, (int)slot.width()));
                    return true;
                }
            }
            for (var entry:reporters.entrySet())if (entry.getKey().isMouseOver(x, y)) {
                takingReporter = entry.getValue();
                return true;
            }
            // Opening a member literal focuses its EditBox. Container click dispatch
            // would focus the source button again after its callback.
            for (var member : List.copyOf(constructionSlots.keySet())) {
                if (member.mouseClicked(x, y, button)) return true;
            }
        }
        if (canvasVisible()) try {
            if (button == 0) {
                var stop = canvas.stopMenuAt(x, y);
                if (stop != null) { canvas.cancel(); showStopMenu(stop, x, y); return true; }
                var wait = canvas.waitMenuAt(x, y);
                if (wait != null) { canvas.cancel(); showWaitUnitMenu(wait, x, y); return true; }
                var keyHat=canvas.keyMenuAt(x,y);
                if(keyHat!=null){
                    canvas.cancel();closeReporterMenu();showKeyMenu(keyHat.terminalKey().orElseThrow(),x,y,key->{
                        draft.replace(keyHat.sourceId(),new FlowProgram.Procedure(keyHat.sourceId(),keyHat.key(),keyHat.inputs(),keyHat.outputs(),keyHat.returns(),keyHat.rules(),keyHat.assembly(),keyHat.event(),Optional.of(key),keyHat.startup()));publish();
                    });return true;
                }
                var monitor = canvas.monitorMenuAt(x, y);
                if (monitor != null) { showMonitorMenu(monitor, x, y, 0); return true; }
                var broadcast = canvas.broadcastMenuAt(x, y);
                if (broadcast != null) { canvas.cancel(); showBroadcastMenu(broadcast, x, y, 0); return true; }
            }
            reporterDrag=null;
            var parked=canvas.parkedSlotHit(x,y);
            if(parked!=null && (button==0 || button==1)) {
                if (button == 0 && presetHit(parked.slot(), x)
                    && showExtensionPresets(new ExtensionPreset(parked.slot(), parked.index(), null), x, y)) return true;
                if (button == 0 && canvas.inlineLabelHit(parked.slot(), x, y)
                    && showMathFunctions(new ExtensionPreset(parked.slot(), parked.index(), null), x, y)) return true;
                setFocused(null);var drag=new ReporterDrag(null,parked,x,y);
                if(button==1)showReporterMenu(drag);else reporterDrag=drag;
                return true;
            }
            taking = null; takingReporter = null; takingProcedure = null; canvas.cancel();
            if (button == 0) for (var entry : procedurePrototypes.entrySet()) if (entry.getKey().isMouseOver(x,y)) {
                takingProcedure = entry.getValue();
                if(takingProcedure.template()){canvas.takeTemplate(takingProcedure.procedure(),x,y);return true;}
                if (takingProcedure.tryLaunch()) {
                    var id = draft.nextId();
                    canvas.takePrototype(List.of(new FlowProgram.TryLaunch(id, takingProcedure.procedure().key(), id + "_started")), x, y);
                    return true;
                }
                if (!takingProcedure.taskJoin() && !takingProcedure.taskStart() && !takingProcedure.localCall()) {
                    var prototype = FlowProcedureTools.prototype(draft, takingProcedure.procedure());
                    canvas.takePrototype(List.of(prototype.call()), prototype.graphs(), x, y);
                    return true;
                }
                canvas.takePrototype(List.of(takingProcedure.taskJoin()
                    ? new FlowProgram.TaskJoin(draft.nextId(), takingProcedure.procedure().key(), "task_handle", "task_result")
                    : takingProcedure.taskStart()
                    ? new FlowProgram.TaskStart(draft.nextId(), takingProcedure.procedure().key(), Map.of(), "task_handle")
                    : FlowProcedureTools.call(draft, takingProcedure.procedure())), Map.of(), x, y); return true;
            }
            if (button == 0) for (var entry : reporters.entrySet()) if (entry.getKey().isMouseOver(x,y)) { takingReporter = entry.getValue(); return true; }
            if (button == 0) {
                var parameter = canvas.parameterAt(x, y);
                if (parameter != null) { takingReporter = parameter; return true; }
            }
            var slot = canvas.inlineHit(x, y);
            if (slot != null && !slot.target()) {
                if (button == 0 && presetHit(slot, x)
                    && showExtensionPresets(new ExtensionPreset(slot, -1, null), x, y)) return true;
                var graph = inlineGraph(draft.find(slot.call()).orElseThrow());
                var node = graph.nodes().get(slot.node());
                if (button == 0 && node instanceof ComputationGraph.Scalar edge && edge.operation().numericEdge() && canvas.inlineLabelHit(slot,x,y)) {
                    canvas.cancel();
                    showClipboardMenu(x, y, List.of(edgeDirectionItem(slot, edge))); return true;
                }
                if (button == 0 && ScratchMathFunctions.hasMenu(node) && canvas.inlineLabelHit(slot, x, y)
                    && showMathFunctions(new ExtensionPreset(slot, -1, null), x, y)) return true;
                if(button==0 && node instanceof ComputationGraph.KeyDown key && x>=slot.x()+slot.width()-slot.height()) {
                    canvas.cancel();showKeyMenu(key.selector(),x,y,selectedKey->{
                        var nodes=new LinkedHashMap<>(graph.nodes());nodes.put(slot.node(),new ComputationGraph.KeyDown(selectedKey));
                        var changed=new ComputationGraph(graph.inputs(),nodes,graph.outputs());
                        var step=draft.find(slot.call()).orElseThrow();
                        draft.editInline(step.sourceId(),changed,FlowInlineValues.bindings(step),draft.snapshot().canvas().values());publish();
                    });return true;
                }
                if (ScratchListPositions.hasMenu(node) && ScratchListPositions.sockets(graph).containsKey(slot.node())
                        && (node instanceof ComputationGraph.ListPosition || button == 1 || x >= slot.x()+slot.width()-slot.height())) {
                    if (draft.snapshot().execution() == FlowProgram.Execution.REGISTER_V1) showListPositionMenu(slot);
                    else notice = text("list_position_requires_isa");
                    return true;
                }
            }
            if(slot!=null && !slot.target() && (button==0 || button==1)) {
                var step=draft.find(slot.call()).orElseThrow();var graph=inlineGraph(step);var node=graph.nodes().get(slot.node());
                var named=FlowNamedLists.target(step,graph,expectedValue(step));
                boolean listTarget=named.isPresent() && slot.node().equals(((ComputationGraph.ListEdit)graph.nodes().get(graph.outputs().get("value"))).arguments().get("source"));
                if(!listTarget && !(node instanceof ComputationGraph.Constant) && !(node instanceof ComputationGraph.ListPosition)) {
                    setFocused(null);var drag=new ReporterDrag(slot,null,x,y);
                    if(button==1)showReporterMenu(drag);else reporterDrag=drag;
                    return true;
                }
            }
            if (button == 0 && slot != null && editSlot(slot)) return true;
            if(button==1) {
                var hit = canvas.blockAt(x,y);
                if (edgeDefinition == null && hit != null && hit.step() instanceof FlowProgram.Watch watch) {
                    showClipboardMenu(x,y,List.of(new BlockMenuItem("watch_edit_definition", () -> act(() -> defineWatch(watch))),
                        new BlockMenuItem("delete_reporter", () -> act(() -> { draft.removeAndReleaseUnusedExtensions(watch.sourceId(), workspace); publish(); }))));
                    return true;
                }
                var statement=canvas.statementAt(x,y);
                if(statement!=null){showStatementMenu(statement,x,y);return true;}
                var block=canvas.blockAt(x,y);
                if (block != null && edgeDefinition == null && FlowDraft.clipboardEventHat(block.step())) {
                    var id = block.step().sourceId();
                    showClipboardMenu(x, y, List.of(
                        new BlockMenuItem("duplicate_reporter", () -> {
                            selected = draft.pasteEventScript(draft.copyEventScript(id), canvas.displacedPosition(x, y));
                            canvas.selected(selected);
                            publish();
                        }),
                        new BlockMenuItem("clipboard_copy", () -> copyEventScript(id, false)),
                        new BlockMenuItem("clipboard_cut", () -> copyEventScript(id, true)),
                        new BlockMenuItem("delete_event_script", () -> {
                            draft.removeAndReleaseUnusedExtensions(id, workspace);
                            selected = null; canvas.selected(null);
                            publish();
                        })));
                    return true;
                }
                if(block!=null && block.step() instanceof FlowProgram.Procedure definition && isCustomDefinition(definition)) {
                    showClipboardMenu(x,y,List.of(
                        new BlockMenuItem("procedure_context_edit",()->procedure(definition,null)),
                        new BlockMenuItem("procedure_find_references",()->openProcedureReferences(definition.key()))));
                    return true;
                }
                if (canvas.contains(x, y)) {
                    var items = new ArrayList<BlockMenuItem>();
                    if (workspace.scratchClipboard() != null)
                        items.add(new BlockMenuItem("clipboard_paste", () -> pasteClipboard(x, y)));
                    if (edgeDefinition == null && block == null && !canvas.mainHatAt(x, y))
                        items.add(new BlockMenuItem("clean_up_blocks", () -> { if (canvas.cleanUpBlocks()) publish(); }));
                    if (!items.isEmpty()) {
                        showClipboardMenu(x, y, items);
                        return true;
                    }
                }
            }
            if (button == 0) for (var entry : prototypes.entrySet()) if (entry.getKey().active && entry.getKey().isMouseOver(x, y)) {
                taking = entry.getValue();
                var pins = menu.response().orElseThrow().pins();
                if(FlowNamedLists.operation(taking).isPresent()) {
                    var block=namedListPrototype(taking);
                    canvas.takePrototype(List.of(block.step()),Map.of(block.step().sourceId(),block.graph()),x,y);return true;
                }
                if (taking == Kind.MONITOR_SHOW || taking == Kind.MONITOR_HIDE) {
                    var targets = monitorTargets(selected, hintLocals(selected));
                    if (targets.isEmpty()) return true;
                    var target = targets.getFirst();
                    canvas.takePrototype(List.of(new FlowProgram.Monitor(draft.nextId(), target.name(),
                        taking == Kind.MONITOR_SHOW, target.privateLocal())), Map.of(), x, y); return true;
                }
                var prototype = FlowBlockPalette.prototype(taking, draft, workspace, pins.xbus(), pins.simpleIo());
                canvas.takePrototype(prototype, taking == Kind.ASSIGN ? Map.of(prototype.getFirst().sourceId(), zeroGraph()) : Map.of(), x, y);
                return true;
            }
            if (canvas.click(x, y, button)) { selected = canvas.selected(); init(); return true; }
        } catch (RuntimeException invalid) { canvas.cancel(); notice = text("invalid", String.valueOf(invalid.getMessage())); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if(!reporterMenu.isEmpty() || reporterDrag!=null) return true;
        if (variableSlot != null) return true;
        if (button == 0 && paletteGrab >= 0) {
            var track = paletteTrack();
            if (track != null) dragPalette(track, y);
            return true;
        }
        if ((canvasVisible() || construction != null) && takingReporter != null && button == 0) return true;
        if (canvasVisible() && canvas.drag(x, y, button, dx, dy)) return true;
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button != 0 && primaryGestureActive()) return true;
        if (button == 0 && paletteGrab >= 0) {
            var track = paletteTrack();
            if (track != null) dragPalette(track, y);
            paletteGrab = -1;
            return true;
        }
        if(!reporterMenu.isEmpty())return true;
        if(canvasVisible() && reporterDrag!=null && button==0) {
            var drag=reporterDrag;reporterDrag=null;
            try {
                if (drag.moved(x, y)) dropReporter(drag, x, y, overPalette(x, y) ? ReporterDrop.DELETE : ReporterDrop.MOVE);
                else if(drag.source!=null)editSlot(drag.source);else editParkedValue(drag.parked);
            } catch(RuntimeException invalid){notice=text("invalid",String.valueOf(invalid.getMessage()));}
            return true;
        }
        if (variableSlot != null) return true;
        if (construction != null && takingReporter != null && button == 0) {
            var source = takingReporter;
            takingReporter = null;
            for (int i = constructionFocusSlots.size()-1;i >= 0;i--) {
                var slot = constructionFocusSlots.get(i);
                if (constructionVisible(x, y) && slot.contains(x, y)) {
                    construction.replaceNode(slot.node(), source);
                    init();
                    return true;
                }
            }
            for (var entry:constructionSlots.entrySet())if (entry.getKey().isMouseOver(x, y)) {
                construction.replace(entry.getValue(), source);
                init();
                break;
            }
            return true;
        }
        if (canvasVisible() && takingReporter != null && button == 0) {
            var source = takingReporter; takingReporter = null;
            var parked = parkedSocket(x, y);
            var slot = canvas.parkedHit(x, y) < 0 ? canvas.inlineHit(x, y) : null;
            try {
                if (parked != null) insertParkedPrototype(parked, source, x, y);
                else if (slot != null && !slot.target()) replaceInline(slot, source, canvas.displacedPosition(x, y));
                else if (slot == null && canvas.contains(x, y) && canvas.parkedHit(x, y) < 0 && canvas.blockAt(x, y) == null)
                    parkPaletteReporter(source, x, y);
                else notice = text("inline_drag_hint");
            } catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
            return true;
        }
        if (canvasVisible() && canvas.active()) {
            boolean legacyTemplate=button==0&&takingProcedure!=null&&takingProcedure.template()&&draft.snapshot().execution()==FlowProgram.Execution.LEGACY_GRAPH;
            var beforeTemplate=draft.snapshot();
            if (button == 0 && takingProcedure != null) {
                takingProcedure = null;
                if (!canvas.prototypeMoved(x,y)) { canvas.cancel(); notice = text("procedure_drag_hint"); return true; }
            }
            if (button == 0 && taking != null) {
                var type = taking; taking = null;
                if (!canvas.prototypeMoved(x, y)) { canvas.cancel(); edit(null, type); return true; }
            }
            try {
                if (overPalette(x, y) && canvas.discardDraggedStatements(x, y, button, workspace)) return true;
                if (canvas.release(x, y, button)) {
                    if (legacyTemplate && !beforeTemplate.equals(draft.snapshot())) notice = text("recipe_template_legacy");
                    return true;
                }
            }
            catch (RuntimeException invalid) { canvas.cancel(); notice = text("invalid", String.valueOf(invalid.getMessage())); return true; }
        }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!reporterMenu.isEmpty()) { closeReporterMenu(); return true; }
        if (variableSlot != null) return true;
        if (construction != null && constructionFocus != null && constructionVisible(x, y)) {
            constructionPan = Math.max(0, constructionPan + (int)(-dy * 32));
            init();
            return true;
        }
        var track = paletteTrack();
        if ((canvasVisible() || construction != null) && (overPalette(x, y) || track != null && track.contains(x, y))) {
            if (edgeDialog()) {
                taking = null; takingReporter = null; takingProcedure = null; reporterDrag = null;
                canvas.cancel(); palettePage -= (int)Math.signum(dy); refreshCatalog();
            } else {
                paletteWheelRemainder -= dy;
                int rows = (int) paletteWheelRemainder;
                paletteWheelRemainder -= rows;
                if (rows != 0 && paletteGrab < 0) scrollPaletteTo((int)Math.clamp((long)paletteOffset + rows, 0, paletteMaximum()));
            }
            return true;
        }
        reporterDrag = null;
        if (canvasVisible() && canvas.scroll(x, y, dx, dy, hasControlDown())) { taking = null; takingReporter = null; takingProcedure = null; cancelSlot(); return true; }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public boolean charTyped(char character, int modifiers) {
        return variableSlot != null || reporterDrag != null || !reporterMenu.isEmpty() || super.charTyped(character, modifiers);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if(!reporterMenu.isEmpty()){if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)closeReporterMenu();return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && reporterDrag!=null){reporterDrag=null;return true;}
        if(listDeclaration && key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE){listDeclaration=false;init();return true;}
        if (variableSlot != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) closeVariableChoices();
            return true;
        }
        if (construction != null && inlineField == null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            cancelConstruction();
            return true;
        }
        if (catalogSearch != null && catalogSearch.isFocused() && inlineField == null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { catalogSearch.setValue(""); setFocused(null); return true; }
            return super.keyPressed(key, scan, modifiers);
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && takingReporter != null) { takingReporter = null; return true; }
        if (inlineField != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { cancelSlot(); return true; }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) { commitSlot(); return true; }
            return super.keyPressed(key, scan, modifiers);
        }
        if (construction != null && getFocused() instanceof EditBox field
                && field.keyPressed(key, scan, modifiers)) {
            return true;
        }
        if (construction != null && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0)) {
            return true;
        }
        if (canvasVisible() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && canvas.cancel()) { taking = null; takingProcedure = null; return true; }
        if(canvasVisible() && !(getFocused() instanceof EditBox) && !canvas.active() && reporterDrag==null && takingReporter==null
                && (modifiers & (org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL|org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER))!=0
                && (modifiers & (org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT|org.lwjgl.glfw.GLFW.GLFW_MOD_ALT))==0
                && (key==org.lwjgl.glfw.GLFW.GLFW_KEY_C || key==org.lwjgl.glfw.GLFW.GLFW_KEY_X || key==org.lwjgl.glfw.GLFW.GLFW_KEY_V)) {
            act(()->{if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_V)pasteClipboard(pointerX,pointerY);else copyUnderPointer(key==org.lwjgl.glfw.GLFW.GLFW_KEY_X);});return true;
        }
        if (canvasVisible() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && hasControlDown()) {
            taking = null; takingProcedure = null; canvas.cancel(); act(() -> { if (hasShiftDown()) draft.redo(); else draft.undo(); publish(); }); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    private void refreshSerialPorts() {
        serialPorts = List.of();
        serialPortPage = 0;
        serialPortsNotice = text("serial_ports_loading");
        serialPortsPending = true;
        serialPortsResponseVersion = menu.controlResponseVersion();
        menu.control(serialWireless ? ComputerControlSession.Action.READ_RADIO : ComputerControlSession.Action.READ_SERIAL, new DataValue.RecordValue(Map.of()));
        init();
    }

    private void receiveSerialPorts() {
        serialPortsPending = false;
        try {
            if (serialWireless) {
                var radio = RadioControlData.View.decode(menu.radioStatus().orElseThrow());
                serialPorts = radio.bands().isPresent()
                    ? List.of(com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT) : List.of();
                serialPortsNotice = text(serialPorts.isEmpty() ? "serial_ports_no_radio" : "serial_ports_hint");
                init();
                return;
            }
            var status = menu.serialStatus().orElseThrow();
            var view = SerialControlData.decode(status);
            if (view.diagnostic().isPresent()) {
                serialPortsNotice = text("serial_ports_failure", view.diagnostic().orElseThrow());
            } else {
                serialPorts = view.ports().keySet().stream().sorted().toList();
                serialPortsNotice = text(serialPorts.isEmpty() ? "serial_ports_empty" : "serial_ports_hint");
            }
        } catch (IllegalArgumentException | NoSuchElementException invalid) {
            var error = menu.controlResponse().filter(reply -> !reply.code().equals("ok")).map(ProgramStatusResponse::code);
            serialPortsNotice = text("serial_ports_failure", error.orElse("invalid"));
        }
        init();
    }

    private void serialPortEditor() {
        int half = (imageWidth - 20) / 2;
        int pageSize = Math.max(1, (imageHeight - 106) / 22);
        serialPortPage = Math.min(serialPortPage, Math.max(0, (serialPorts.size() - 1) / pageSize));
        int start = serialPortPage * pageSize, end = Math.min(start + pageSize, serialPorts.size());
        int tab = (imageWidth - 28) / 4;
        button(8, 30, tab, "serial_ports_wired", () -> { serialWireless = false; refreshSerialPorts(); }).active = serialWireless;
        button(12 + tab, 30, tab, "serial_ports_wireless", () -> { serialWireless = true; refreshSerialPorts(); }).active = !serialWireless;
        button(16 + 2 * tab, 30, tab, "serial_ports_refresh", this::refreshSerialPorts).active = !serialPortsPending;
        button(20 + 3 * tab, 30, tab, "back", () -> { serialPortsOpen = false; init(); });
        for (int i = start; i < end; i++) {
            var name = serialPorts.get(i);
            var option = button(8, 54 + 22 * (i - start), imageWidth - 16, text("serial_port_candidate", name), () -> {
                port = name; serialPortsOpen = false; init();
            });
            option.setTooltip(Tooltip.create(Component.literal(name)));
        }
        if (serialPorts.size() > pageSize) {
            button(8, imageHeight - 27, half, "previous", () -> { serialPortPage--; init(); }).active = start > 0;
            button(12 + half, imageHeight - 27, half, "next", () -> { serialPortPage++; init(); }).active = end < serialPorts.size();
        }
        notice = serialPortsNotice;
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (serialPortsOpen && serialPortsPending && serialPortsResponseVersion != menu.controlResponseVersion())
            receiveSerialPorts();
        if (openReaderPreset != null && openReaderPreset.responseVersion() != menu.controlResponseVersion())
            refreshReaderPreset(openReaderPreset);
        refreshDraftStatus();
        if (responseVersion == menu.responseVersion()) return;
        responseVersion = menu.responseVersion();
        if (waiting) { waiting = false; menu.response().ifPresent(reply -> notice = reply.code().equals("applied") ? text("installed_paused") : text("invalid", reply.detail())); init(); }
    }
    private void refreshDraftStatus() {
        if (saveDraftButton == null) return;
        var status = draftStatus.get(); // Acknowledge only the sent snapshot, including while this child owns the menu.
        saveDraftButton.active = !waiting && canSaveDraft.getAsBoolean();
        saveDraftButton.setTooltip(Tooltip.create(status)); saveDraftButton.setToolTip(status);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(text(edgeDialog() ? "watch_definition_title" : draft.snapshot().execution() == FlowProgram.Execution.REGISTER_V1 ? "title_assembly" : "title_legacy").getString(),
            !initials && !parameters && workspace.flow().isPresent() ? imageWidth - (saveDraft == null ? 164 : 232) : imageWidth - 20), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        if (!initials && !parameters && construction == null && workspace.flow().isPresent()) {
            if (edgeDialog()) {
                var watch = (FlowProgram.Watch)draft.find(edgeDefinition.source()).orElseThrow();
                graphics.drawString(font, text("watch_preview"), leftPos + 8, topPos + 41, 0xffeeeeee, false);
                var caption = FlowBlockCanvas.watchTitle(watch);
                int hatWidth = Math.min(imageWidth - 112, Math.max(100, font.width(caption) + 20));
                ScratchEdgeEditor.hat(graphics, font, leftPos + (imageWidth - hatWidth) / 2, topPos + 32, hatWidth, caption);
                int index = EDGE_CATEGORIES.indexOf(category);
                int tabWidth = (imageWidth - 16 - (EDGE_CATEGORIES.size() - 1) * 2) / EDGE_CATEGORIES.size();
                if (searchQuery.isBlank() && index >= 0) {
                    int tabX = leftPos + 8 + index * (tabWidth + 2), tabY = topPos + edgeInputsY();
                    graphics.fill(tabX, tabY - 2, tabX + tabWidth, tabY, 0xffffdc80);
                }
            } else {
                int highlight = topPos + 54 + SIDEBAR.indexOf(category) * categoryStep;
                if (searchQuery.isBlank()) ScratchArt.draw(graphics, ScratchArt.Part.CATEGORY, leftPos + 5, highlight, 2, categoryStep - 2);
                if (catalogEntries.isEmpty()) {
                    String key = !searchQuery.isBlank() ? "catalog_no_matches" : "catalog_empty_" + category.name().toLowerCase(Locale.ROOT);
                    int startY = topPos + TOP + (category == Category.EVENTS && searchQuery.isBlank() ? 26 : 0), y = startY;
                    graphics.enableScissor(leftPos + paletteX, topPos + TOP, leftPos + paletteX + paletteWidth, topPos + imageHeight - 80);
                    for (var line : font.split(text(key), paletteWidth)) { graphics.drawString(font, line, leftPos + paletteX, y, 0xffdddddd, false); y += font.lineHeight + 2; }
                    graphics.disableScissor();
                    if (mouseX >= leftPos + paletteX && mouseX < leftPos + paletteX + paletteWidth && mouseY >= startY && mouseY < topPos + imageHeight - 80)
                        graphics.renderTooltip(font, font.split(text(key), imageWidth - 20), mouseX, mouseY);
                }
            }
            canvas.render(graphics, font, ComputerFlowScreen::blockLabel, text("main_hat"), text("parked_stack"));
            if (reporterDrag != null && reporterDrag.moved(mouseX, mouseY) && canvas.contains(mouseX, mouseY)
                    && movableReporter(reporterDrag)) {
                var slot = reporterSnapTarget(mouseX, mouseY);
                if (slot != null) ScratchArt.draw(graphics, ScratchArt.Part.DROP_OUTLINE, (int)slot.x(), (int)slot.y(), (int)slot.width(), (int)slot.height());
                try{canvas.previewReporter(graphics,font,reporterDrag.fragment(),mouseX,mouseY);}catch(RuntimeException ignored){ /* Invalid imported fragments remain editable. */ }
            }
            if (takingReporter != null) {
                var slot = reporterSnapTarget(mouseX, mouseY);
                if (slot != null) ScratchArt.draw(graphics, ScratchArt.Part.DROP_OUTLINE, (int)slot.x(), (int)slot.y(), (int)slot.width(), (int)slot.height());
                var sourceCategory = hintCatalog.values().stream().filter(entry -> takingReporter.equals(entry.value()))
                    .map(FlowBlockCatalog.Entry::category).findFirst().orElse(takingReporter instanceof InlineGraphEdits.ProcedureParameter ? Category.CUSTOM : category);
                var shape = FlowBlockSkin.shape(takingReporter);
                var caption = font.plainSubstrByWidth(reporterLabel(takingReporter).getString(), 124);
                FlowBlockSkin.block(graphics, mouseX + 6, mouseY + 6, font.width(caption) + 20, 20, sourceCategory, shape, false, false);
                graphics.drawString(font, caption, mouseX + 14, mouseY + 12, FlowBlockSkin.ink(sourceCategory, shape), false);
            }
        }
        var track = paletteTrack();
        if (track != null) {
            graphics.fill(track.x(), track.y(), track.x() + PALETTE_SCROLL_WIDTH, track.y() + track.height(), 0x44000000);
            graphics.fill(track.x() + 1, track.thumbY(), track.x() + PALETTE_SCROLL_WIDTH - 1,
                track.thumbY() + track.thumbHeight(), paletteGrab >= 0 ? 0xffffdc80 : 0xffcccccc);
        }
        if (constructionFocusTile != null) {
            int x = leftPos + paletteX + paletteWidth + 8;
            graphics.enableScissor(x, topPos + TOP, leftPos + imageWidth-8, topPos + imageHeight-114);
            FlowBlockCanvas.drawTile(graphics, font, constructionFocusTile, x-constructionPan, topPos + TOP);
            graphics.disableScissor();
        }
        boolean showDraft = draftStatus != null && !listDeclaration && !initials && !parameters && construction == null;
        int noticeY = topPos + imageHeight - (showDraft ? 49 : 45);
        graphics.drawString(font, font.plainSubstrByWidth(notice.getString(), imageWidth - 16), leftPos + 8, noticeY, 0xffffddaa, false);
        if (showDraft) {
            var status = draftStatus.get();
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 16),
                leftPos + 8, topPos + imageHeight - 38, 0xffcccccc, false);
            if (mouseY >= topPos + imageHeight - 38 && mouseY < topPos + imageHeight - 28)
                graphics.renderTooltip(font, font.split(status, imageWidth - 20), mouseX, mouseY);
        }
        if (mouseY >= noticeY && mouseY < noticeY + 10)
            graphics.renderTooltip(font, font.split(notice, imageWidth - 20), mouseX, mouseY);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.render(graphics, mouseX, mouseY, partial);
        var hint = typeTooltip(mouseX, mouseY, hasShiftDown());
        if (hint != null) graphics.renderTooltip(font, FlowTypeDetails.visibleLines(font, hint, width, height), mouseX, mouseY);
    }
    Component typeTooltip(int mouseX, int mouseY, boolean shift) {
        if(!reporterMenu.isEmpty())return null;
        if (variableSlot != null) {
            int rows = variableChoiceRows();
            for (int i = 0; i < rows && variablePage * rows + i < variableChoices.size(); i++) {
                if (variableWidgets.get(i).isMouseOver(mouseX, mouseY)) {
                    var source = variableChoices.get(variablePage * rows + i);
                    return FlowTypeDetails.tooltip(FlowBlockCatalog.label(source), FlowTypeDetails.source(source), shift, menu.nativeIsa1b());
                }
            }
            return null;
        }
        for (var entry : hintCatalog.entrySet()) if (entry.getKey().isMouseOver(mouseX, mouseY)) {
            var value=entry.getValue();
            var hints=value.value()!=null?FlowTypeDetails.source(value.value()):value.procedure()!=null?FlowTypeDetails.signature(value.procedure()):List.<FlowTypeDetails.Hint>of();
            return FlowTypeDetails.tooltip(value.help(),hints,shift,menu.nativeIsa1b());
        }
        if(construction!=null) {
            if(constructionVisible(mouseX,mouseY))for(int i=constructionFocusSlots.size()-1;i>=0;i--) {
                var slot=constructionFocusSlots.get(i); if(slot.contains(mouseX,mouseY))
                    return expressionTooltip(construction.preview(slot.node()),slot.node(),constructionCall,construction.bindings(),shift);
            }
            for(var entry:constructionSlots.entrySet())if(entry.getKey().isMouseOver(mouseX,mouseY)) {
                String node=construction.rows().get(entry.getValue()).child();
                return expressionTooltip(construction.preview(node),node,constructionCall,construction.bindings(),shift);
            }
            return null;
        }
        if(parameters||initials||listDeclaration)return null;
        var parked=canvas.parkedSlotHit(mouseX,mouseY);
        if(parked!=null) {
            var reporter=draft.snapshot().canvas().values().parked().get(parked.index());
            var scope=reporter.procedureScope().map(ScratchValueLayout.ProcedureScope::source).orElse(null);
            var hints=FlowTypeDetails.expression(reporter.graph(),parked.slot().node(),hintLocals(scope),reporter.arguments(),hintCells);
            return FlowTypeDetails.tooltip(text("parked_reporter_hint"),hints,shift,menu.nativeIsa1b());
        }
        var slot=canvas.inlineHit(mouseX,mouseY);
        if(slot!=null&&!slot.target()) {
            var step=draft.find(slot.call()).orElseThrow();
            if (!draft.snapshot().inline().containsKey(step.sourceId()) && FlowProgram.valueInput(step) instanceof FlowInput.Local local) {
                var result = FlowTypeDetails.tooltip(Component.empty(), List.of(FlowTypeDetails.local(local.name(), hintLocals(slot.call()))), shift, menu.nativeIsa1b());
                return result.getString().isEmpty() ? null : result;
            }
            var graph = inlineGraph(step);
            var caption = graph == null ? Component.empty()
                : ScratchExtensionMenus.readerSelection(step, graph, slot.node(), menu::capturedInputs)
                    .map(choice -> choice.tooltip(shift)).orElseGet(Component::empty);
            return expressionTooltip(graph,slot.node(),slot.call(),FlowInlineValues.bindings(step),shift,caption);
        }
        refreshHintScope();
        var hit=canvas.blockAt(mouseX,mouseY);
        if(hit!=null) {
            var step=hit.step();
            if (step instanceof FlowProgram.Watch watch) return watchTooltip(watch, shift);
            var definition=step instanceof FlowProgram.Procedure p?p:step instanceof FlowProgram.Invoke c?hintProcedures.get(c.procedure()):step instanceof FlowProgram.TryLaunch c?hintProcedures.get(c.procedure()):step instanceof FlowProgram.TaskStart c?hintProcedures.get(c.procedure()):step instanceof FlowProgram.TaskJoin c?hintProcedures.get(c.procedure()):null;
            if (definition != null) {
                var hints = FlowTypeDetails.signature(definition);
                boolean closed = step instanceof FlowProgram.Invoke && definition.assembly().isEmpty()
                    && hints.stream().anyMatch(hint->FlowTypeDetails.any(hint.type()))
                    && hintHardware != null && hintBody.compilation()!=FlowProgram.Compilation.ORIGINAL
                    && hintClosedSignatures.computeIfAbsent(step.sourceId(),id->{
                        try { return hintBody.program(workspace.snapshot().archive()).closedSignatureAt(id,hintHardware); }
                        catch (ComputationException | IllegalArgumentException incomplete) { return false; }
                    });
                boolean software=step instanceof FlowProgram.Invoke && hintHardware!=null && hintBody.compilation()==FlowProgram.Compilation.SOFTWARE_V1
                    && hintSoftwareSignatures.computeIfAbsent(step.sourceId(),id->{
                        try{return hintBody.program(workspace.snapshot().archive()).softwareSignatureAt(id,hintHardware);}
                        catch(ComputationException|IllegalArgumentException incomplete){return false;}
                    });
                return FlowTypeDetails.tooltip(blockLabel(step),hints,shift,menu.nativeIsa1b(),closed,software);
            }
        }
        return null;
    }
    private void refreshHintScope() {
        var cells=workspace.root().cells();
        var hardware = menu.response().map(ProgramEditorResponse::hardware).orElse(null);
        if(hintBody==draft.snapshot() && hintCells.equals(cells) && Objects.equals(hintHardware, hardware))return;
        hintHardware = hardware;
        hintBody=draft.snapshot();hintCells=new LinkedHashMap<>(cells);hintScopes.clear();hintClosedSignatures.clear();hintSoftwareSignatures.clear();
        var definitions=new LinkedHashMap<ModuleDefinition.Key,FlowProgram.Procedure>();
        FlowProcedureTools.definitions(hintBody).forEach(p->definitions.put(p.key(),p));hintProcedures=definitions;
    }
    private Map<String, DataType> hintLocals(String call) {
        refreshHintScope();
        return hintScopes.computeIfAbsent(call,id->hintHardware != null ? FlowProcedureTools.locals(hintBody,id,workspace.snapshot().archive(),hintHardware)
            : FlowProcedureTools.owner(hintBody,id).map(FlowProgram.Procedure::inputs).orElse(hintBody.inputs()));
    }
    private Component expressionTooltip(ComputationGraph graph,String node,String call,FlowCallBindings bindings,boolean shift) {
        return expressionTooltip(graph, node, call, bindings, shift, Component.empty());
    }
    private Component expressionTooltip(ComputationGraph graph,String node,String call,FlowCallBindings bindings,boolean shift,Component caption) {
        if(graph==null)return null;
        var hints=FlowTypeDetails.expression(graph,node,hintLocals(call),bindings.arguments(),hintCells);
        var result=FlowTypeDetails.tooltip(caption,hints,shift,menu.nativeIsa1b());
        return result.getString().isEmpty()?null:result;
    }
    @Override protected void renderLabels(GuiGraphics graphics, int x, int y) {}
    static Component blockLabel(FlowProgram.Step step) {
        return blockLabel(step, null);
    }
    static Component blockLabel(FlowProgram.Step step, ComputationGraph inline) {
        if (step instanceof FlowProgram.StopScript stop) return text(stopModeKey(stop.mode()));
        if (step instanceof FlowProgram.Watch watch) return FlowBlockCanvas.watchTitle(watch);
        if (step instanceof FlowProgram.Procedure p && ScratchExtensions.reserved(p.key())) return FlowBlockCatalog.extensionValueLabel(p.key());
        if(step instanceof FlowProgram.Procedure p && p.terminalKey().isPresent())return text("terminal_key_hat",FlowBlockCanvas.keyLabel(p.terminalKey().orElseThrow()));
        if(step instanceof FlowProgram.Procedure p && p.startup())return text("add_startup");
        if(step instanceof FlowProgram.Procedure procedure)return procedure.event().<Component>map(channel->text("receive_broadcast_hat",channel)).orElseGet(()->text("block_procedure",procedure.key().id(),procedure.key().revision()));
        if(step instanceof FlowProgram.TaskJoin join)return text("block_task_join",join.procedure().id(),join.procedure().revision());
        if(step instanceof FlowProgram.TryLaunch launch)return text("block_try_launch",launch.procedure().id(),launch.started());
        if(step instanceof FlowProgram.TaskStart launch)return text("block_task_start",launch.procedure().id(),launch.procedure().revision());
        if(step instanceof FlowProgram.Invoke call)return com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(call.procedure())
            .<Component>map(binding -> Component.translatable(binding.command().labelKey()))
            .orElseGet(() -> text("block_invoke",call.procedure().id(),call.procedure().revision()));
        if(step instanceof FlowProgram.EndProcedure)return text("block_end_procedure");
        if (step instanceof FlowProgram.Sleep sleep && sleep.unit().isPresent())
            return text("block_wait_duration", inputLabel(sleep.ticks(), inline), FlowInlineValues.waitUnitLabel(sleep.displayedUnit()));
        String parameters = switch (step) {
            case FlowProgram.Monitor monitor -> monitor.cell();
            case FlowProgram.Reference ref -> ref.target()+" → "+ref.local();
            case FlowProgram.Shared shared -> shared.cell();
            case FlowProgram.Atomic atomic -> atomic.result();
            case FlowProgram.EndAtomic ignored -> "";
            case FlowProgram.Memory memory -> switch (memory.kind()) {
                case READ -> memory.cell() + " → " + memory.local(); case WRITE -> memory.local() + " → " + memory.cell();
                case COMMIT -> memory.local(); default -> "";
            };
            case FlowProgram.Correlation tag -> tag.local();
            case FlowProgram.SerialSend send -> send.local() + " → " + send.port() + " → " + send.result();
            case FlowProgram.SerialReceive receive -> receive.port() + (receive.nativeNbt() ? " [NBT]" : "") + " → " + receive.local();
            case FlowProgram.SerialStatus status -> status.port() + " → " + status.local();
            case FlowProgram.XBusStatus status -> status.port() + " → " + status.local();
            case FlowProgram.SignalRead read -> read.port() + " → " + read.local();
            case FlowProgram.SignalWrite write -> write.local() + " → " + write.port();
            case FlowProgram.Read read -> read.port() + " → " + read.local();
            case FlowProgram.Write write -> write.local() + " → " + write.port() + write.result().map(name -> " → " + name).orElse("");
            case FlowProgram.AwaitReadable wait -> wait.port() + wait.result().map(name -> " → " + name).orElse("");
            case FlowProgram.Evaluate evaluate -> evaluate.expression();
            case FlowProgram.If branch -> inputLabel(branch.condition(), inline);
            case FlowProgram.Otherwise ignored -> "";
            case FlowProgram.EndIf ignored -> "";
            case FlowProgram.Forever ignored -> "";
            case FlowProgram.EndForever ignored -> "";
            case FlowProgram.Repeat repeat -> inputLabel(repeat.count(), inline);
            case FlowProgram.EndRepeat ignored -> "";
            case FlowProgram.Until until -> inputLabel(until.condition(), inline);
            case FlowProgram.StopScript ignored -> "";
            case FlowProgram.WaitUntil wait -> inputLabel(wait.condition(), inline);
            case FlowProgram.EndUntil ignored -> "";
            case FlowProgram.Procedure ignored -> "";
            case FlowProgram.EndProcedure ignored -> "";
            case FlowProgram.Invoke ignored -> "";
            case FlowProgram.Broadcast event -> event.channel();
            case FlowProgram.Watch ignored -> "";
            case FlowProgram.TryLaunch ignored -> "";
            case FlowProgram.TaskStart ignored -> "";
            case FlowProgram.TaskJoin ignored -> "";
            case FlowProgram.TimerReset ignored -> "";
            case FlowProgram.Ask ask -> inputLabel(ask.question(), inline);
            case FlowProgram.Sleep sleep -> inputLabel(sleep.ticks(), inline);
        };
        return text("block_" + type(step).name().toLowerCase(Locale.ROOT)
            + (FlowProgram.hasXBusResults(List.of(step)) ? "_result" : FlowProgram.hasFaultClaim(List.of(step)) ? "_claim" : ""), parameters);
    }
    private static ComputationGraph zeroGraph() {
        return new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "value"));
    }
    private static Component reporterLabel(InlineGraphEdits.Source source) {
        return FlowBlockCatalog.label(source);
    }
    private DataType expectedValue(FlowProgram.Step step) {
        if(step instanceof FlowProgram.Reference ref && ref.kind()==FlowProgram.ReferenceKind.WRITE){var type=FlowProcedureTools.locals(draft.snapshot(),step.sourceId(),workspace.snapshot().archive(),menu.response().orElseThrow().hardware()).get(ref.target());return type instanceof DataType.RefType r?r.target():DataType.Scalar.ANY;}
        return step instanceof FlowProgram.Shared shared
            ? Optional.ofNullable(workspace.root().cells().get(shared.cell())).map(ProgramDefinition.Cell::type).orElse(DataType.Scalar.ANY)
            : FlowInlineValues.expected(step);
    }
    private ComputationGraph inlineGraph(FlowProgram.Step step) {
        DataType type = expectedValue(step);
        if ((step instanceof FlowProgram.Shared || step instanceof FlowProgram.Sleep)
                && FlowProgram.valueInput(step) instanceof FlowInput.Local local)
            type = FlowProcedureTools.locals(draft.snapshot(), step.sourceId(), workspace.snapshot().archive(),
                menu.response().orElseThrow().hardware()).getOrDefault(local.name(), type);
        return FlowInlineValues.graph(draft.snapshot(), step, type);
    }
    private boolean editSlot(FlowBlockCanvas.Slot slot) {
        var step=draft.find(slot.call()).orElseThrow();
        var graph = inlineGraph(step);
        var node = graph.nodes().get(slot.node());
        var named=FlowNamedLists.target(step,graph,expectedValue(step));
        if(named.isPresent() && (slot.target() || slot.node().equals(((ComputationGraph.ListEdit)graph.nodes().get(graph.outputs().get("value"))).arguments().get("source")))) {
            closeVariableChoices();canvas.cancel();setFocused(null);variableSlot=slot;namedListTarget=true;
            variableChoices=namedListChoices(slot.call());variablePage=0;showVariableChoices();return true;
        }
        if (!slot.target() && (node instanceof ComputationGraph.Input || node instanceof ComputationGraph.ReadMemory)) {
            canvas.cancel();
            setFocused(null);
            var publicTypes = new TreeMap<String, DataType>();
            workspace.root().cells().forEach((name, cell) -> publicTypes.put(name, cell.type()));
            variableChoices = InlineGraphEdits.palette(new TreeMap<>(hintLocals(slot.call())), publicTypes).stream()
                .filter(source -> source instanceof InlineGraphEdits.Variable v && v.path().isEmpty()
                    || source instanceof InlineGraphEdits.PublicVariable shared && shared.path().isEmpty()).toList();
            variableSlot = slot; variablePage = 0; showVariableChoices();
            return true;
        }
        if (!slot.target() && (node instanceof ComputationGraph.MakeRecord || node instanceof ComputationGraph.MakeList)) {
            openConstructor(graph, FlowInlineValues.bindings(draft.find(slot.call()).orElseThrow()), slot.node(), slot.call(), -1);
            return true;
        }
        if (!slot.target() && !(node instanceof ComputationGraph.Constant)) return false;
        String value = slot.target() ? slot.label() : ((ComputationGraph.Constant)node).value() instanceof DataValue.TextValue text ? text.value() : EditorValueFormat.format(((ComputationGraph.Constant)node).value());
        canvas.cancel(); editingSlot = slot;
        int fieldWidth = Math.min(160, Math.max(44, (int)slot.width()));
        int fieldX = Math.clamp((int)slot.x(), leftPos + paletteX + paletteWidth + 8, leftPos + imageWidth - fieldWidth - 8);
        int fieldY = Math.clamp((int)slot.y(), topPos + TOP, topPos + imageHeight - 74);
        inlineField = addRenderableWidget(new EditBox(font, fieldX, fieldY, fieldWidth, 18, text("inline_value")));
        inlineField.setMaxLength(ProgramEditorWire.MAX_BYTES); inlineField.setValue(value); setFocused(inlineField);
        inlineField.setHighlightPos(0); notice = text("inline_edit_hint"); return true;
    }
    private void cancelSlot() {
        editingParked=null;
        if (inlineField != null) removeWidget(inlineField);
        inlineField = null;
        editingSlot = null;
        
        constructionLiteralNode = null;
        setFocused(null);
    }
    private void showListPositionMenu(FlowBlockCanvas.Slot slot) {
        closeVariableChoices(); canvas.cancel(); setFocused(null); variableSlot = slot;
        int width = Math.min(180, imageWidth-paletteX-paletteWidth-16);
        int x = Math.clamp((int)slot.x(), leftPos+paletteX+paletteWidth+8, leftPos+imageWidth-width-8);
        int y = Math.clamp((int)slot.y(), topPos+TOP, topPos+imageHeight-40-3*22);
        variableWidgets.add(button(x-leftPos,y-topPos,width,"list_position_number",()->chooseListPosition(slot,null)));
        int row=1;
        for(var choice:ComputationGraph.ListPosition.Choice.values()) {
            variableWidgets.add(button(x-leftPos,y-topPos+row++*22,width,"list_position_"+choice.name().toLowerCase(Locale.ROOT),()->chooseListPosition(slot,choice)));
        }
    }
    private void chooseListPosition(FlowBlockCanvas.Slot slot, ComputationGraph.ListPosition.Choice choice) {
        closeVariableChoices();
        act(() -> {
            var step = draft.find(slot.call()).orElseThrow();
            var layout = draft.snapshot().canvas().values();
            var edit = ScratchListPositions.choose(inlineGraph(step), FlowInlineValues.bindings(step), layout.forBlock(slot.call()), slot.node(), choice);
            var candidate = new FlowDraft(draft.snapshot());
            candidate.editInline(slot.call(), edit.graph(), edit.bindings(), layout.with(slot.call(), edit.shadows(), layout.parked()));
            // Reuse the compiler's typed entry validation before committing an editor change.
            var definition = new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),workspace.root().cells(),List.of(),List.of(),
                Map.of("inline",new ProgramDefinition.Entry(edit.graph(),List.of(),List.of())));
            ProgramCompiler.compileFlow(definition,workspace.snapshot().archive().modules(),menu.response().orElseThrow().hardware(),
                FlowProgram.valueProcedures(FlowProcedureTools.definitions(candidate.snapshot())));
            draft.accept(candidate); publish();
        });
    }
    private void closeVariableChoices() {
        variableWidgets.forEach(this::removeWidget);
        variableWidgets.clear(); variableChoices = List.of(); variableSlot = null; namedListTarget=false;
    }
    private void showVariableChoices() {
        variableWidgets.forEach(this::removeWidget); variableWidgets.clear();
        int rows = variableChoiceRows();
        int width = Math.min(180, imageWidth - paletteX - paletteWidth - 16);
        int x = Math.clamp((int)variableSlot.x(), leftPos + paletteX + paletteWidth + 8, leftPos + imageWidth - width - 8);
        int y = Math.clamp((int)variableSlot.y(), topPos + TOP, topPos + imageHeight - 40 - (rows + 1) * 22);
        for (int row = 0; row < rows && variablePage * rows + row < variableChoices.size(); row++) {
            var source = variableChoices.get(variablePage * rows + row);
            variableWidgets.add(button(x - leftPos, y - topPos + row * 22, width, FlowBlockCatalog.label(source), () -> {
                try { if(namedListTarget) replaceNamedList(variableSlot,source);else replaceInline(variableSlot, source); }
                catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
            }));
        }
        int third = (width - 8) / 3;
        int footer = y - topPos + rows * 22;
        var previous = button(x - leftPos, footer, third, "palette_previous", () -> { variablePage--; showVariableChoices(); });
        previous.active = variablePage > 0; variableWidgets.add(previous);
        var next = button(x - leftPos + third + 4, footer, third, "palette_next", () -> { variablePage++; showVariableChoices(); });
        next.active = (variablePage + 1) * rows < variableChoices.size(); variableWidgets.add(next);
        variableWidgets.add(button(x - leftPos + 2 * (third + 4), footer, third, "construction_cancel", this::closeVariableChoices));
    }
    private int variableChoiceRows() {
        return Math.min(Math.max(1, variableChoices.size()), Math.min(6, Math.max(1, (imageHeight - TOP - 70) / 22)));
    }
    private List<InlineGraphEdits.Source> namedListChoices(String owner) {
        var choices=new ArrayList<InlineGraphEdits.Source>();
        FlowNamedLists.targets(hintLocals(owner)).forEach((name,type)->choices.add(new InlineGraphEdits.Variable(name,List.of(),type)));
        var cells=new TreeMap<String,DataType>();workspace.root().cells().forEach((name,cell)->cells.put(name,cell.type()));
        FlowNamedLists.targets(cells).forEach((name,type)->choices.add(new InlineGraphEdits.PublicVariable(name,List.of(),type)));
        return List.copyOf(choices);
    }
    private FlowNamedLists.ScopedEdit namedListPrototype(Kind kind) {
        var choices=namedListChoices(selected);
        if(choices.isEmpty())throw new IllegalArgumentException(text("named_list_select").getString());
        var target=choices.getFirst();
        String name=target instanceof InlineGraphEdits.Variable v?v.name():((InlineGraphEdits.PublicVariable)target).name();
        var type=(DataType.ListType)(target instanceof InlineGraphEdits.Variable v?v.type():((InlineGraphEdits.PublicVariable)target).type());
        return FlowNamedLists.scopedPrototype(FlowNamedLists.operation(kind).orElseThrow(),draft.nextId(),name,type,
            target instanceof InlineGraphEdits.PublicVariable,hintLocals(selected).keySet());
    }
    private void replaceNamedList(FlowBlockCanvas.Slot slot,InlineGraphEdits.Source target) {
        var step=draft.find(slot.call()).orElseThrow();var graph=inlineGraph(step);
        var old=FlowNamedLists.target(step,graph,expectedValue(step)).orElseThrow();
        String name=target instanceof InlineGraphEdits.Variable v?v.name():((InlineGraphEdits.PublicVariable)target).name();
        var type=(DataType.ListType)(target instanceof InlineGraphEdits.Variable v?v.type():((InlineGraphEdits.PublicVariable)target).type());
        var layout=draft.snapshot().canvas().values();
        var edit=FlowNamedLists.chooseScope(step,graph,old,name,type,target instanceof InlineGraphEdits.PublicVariable,
            hintLocals(slot.call()).keySet(),layout.forBlock(slot.call()),canvas.displacedPosition(slot.x(),slot.y()));
        var parked=new ArrayList<>(layout.parked());edit.parked().ifPresent(reporter -> parked.add(scopeReporter(slot.call(),reporter)));
        var candidate=new FlowDraft(draft.snapshot());candidate.editInline(edit.step(),edit.graph(),layout.with(slot.call(),layout.forBlock(slot.call()),parked));
        draft.accept(candidate);publish();init();
        notice=edit.parked().isPresent()?text("named_list_parked_item"):edit.step() instanceof FlowProgram.Shared shared
            ?text("named_list_shared_hint",shared.listResult().orElseThrow()):text("named_list_select");
    }
    private static boolean ownsParameter(FlowDocumentBody body, String target, InlineGraphEdits.ProcedureParameter parameter) {
        return FlowProcedureTools.owner(body, target).filter(p -> procedureScope(p).equals(parameter.owner())
            && parameter.type().equals(p.inputs().get(parameter.name()))).isPresent();
    }
    private void replaceInline(FlowBlockCanvas.Slot slot, InlineGraphEdits.Source source) {
        replaceInline(slot, source, null);
    }

    /** A palette drop can displace a reporter; changing an existing field in place does not. */
    private void replaceInline(FlowBlockCanvas.Slot slot, InlineGraphEdits.Source source, ProgramDocument.Position displacedAt) {
        replaceInline(slot, source, displacedAt, false);
    }

    private void replaceInline(FlowBlockCanvas.Slot slot, InlineGraphEdits.Source source,
                               ProgramDocument.Position displacedAt, boolean preview) {
        if (source instanceof InlineGraphEdits.ProcedureParameter parameter) {
            if (!ownsParameter(draft.snapshot(), slot.call(), parameter))
                throw new IllegalArgumentException(text("procedure_parameter_scope").getString());
            source = parameter.variable();
        }
        var step = draft.find(slot.call()).orElseThrow();
        var sourceGraph = inlineGraph(step);
        if (displacedAt != null || step instanceof FlowProgram.Invoke invoke && invoke.operands().isPresent())
            FlowReporterEdits.requireUnshared(sourceGraph, slot.node());
        var edit = ScratchListPositions.placeReporter(sourceGraph, FlowInlineValues.bindings(step),
            draft.snapshot().canvas().values().forBlock(slot.call()), slot.node(), source);
        var candidate = new FlowDraft(draft.snapshot());
        if (source instanceof InlineGraphEdits.ProcedureResult result
                && com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(result.procedure().key())) {
            var binding = com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(result.procedure().key())
                .orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension reporter"));
            com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.requireEnabled(binding.extension(), candidate.snapshot().canvas().extensions());
            if (binding.command().shape() != com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.REPORTER
                    && binding.command().shape() != com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.BOOLEAN)
                throw new IllegalArgumentException("Not a Scratch extension value reporter");
            candidate.ensureTemplate(binding.template("extension"));
        }
        validateInline(step, edit.graph(), edit.bindings(), candidate.snapshot());
        var layout=draft.snapshot().canvas().values();var shadows=new LinkedHashMap<>(layout.forBlock(slot.call()));
        var old=inlineGraph(step).nodes().get(slot.node());
        if(old instanceof ComputationGraph.Constant constant && !(edit.graph().nodes().get(slot.node()) instanceof ComputationGraph.Constant))shadows.put(slot.node(),constant.value());
        else if(old instanceof ComputationGraph.ListPosition position)shadows.put(slot.node(),ScratchListPositions.positionShadow(position,shadows.get(slot.node())));
        var parked = preserveDisplacedReporter(step, slot, sourceGraph, layout, edit.wrapsPrevious() ? null : displacedAt);
        candidate.editInline(slot.call(), edit.graph(), edit.bindings(),layout.with(slot.call(),shadows,parked));
        if (preview) return;
        if (parked.size() > layout.parked().size()) canvas.placeLastDisplacedReporter(candidate);
        draft.acceptReporterEdit(candidate, workspace); publish(); init();
    }
    private List<ScratchValueLayout.Reporter> preserveDisplacedReporter(FlowProgram.Step step, FlowBlockCanvas.Slot slot,
            ComputationGraph graph, ScratchValueLayout layout, ProgramDocument.Position displacedAt) {
        var previous = graph.nodes().get(slot.node());
        if (displacedAt == null || previous instanceof ComputationGraph.Constant
                || previous instanceof ComputationGraph.ListPosition) return layout.parked();
        var displaced = FlowReporterEdits.take(graph, FlowInlineValues.bindings(step), layout.forBlock(slot.call()),
            slot.node(), displacedAt);
        var parked = new ArrayList<>(layout.parked());
        parked.add(scopeReporter(slot.call(), displaced));
        return parked;
    }

    private static ScratchValueLayout.ProcedureScope procedureScope(FlowProgram.Procedure owner) {
        return new ScratchValueLayout.ProcedureScope(owner.sourceId(), owner.key());
    }
    private ScratchValueLayout.Reporter scopeReporter(String source, ScratchValueLayout.Reporter reporter) {
        if (reporter.arguments().isEmpty()) return reporter;
        return FlowProcedureTools.owner(draft.snapshot(), source)
            .filter(p -> reporter.arguments().values().stream().anyMatch(p.inputs()::containsKey))
            .map(owner -> reporter.scoped(procedureScope(owner))).orElse(reporter);
    }
    private enum ReporterDrop { MOVE, DELETE, CUT }

    private void dropReporter(ReporterDrag drag, double x, double y, ReporterDrop action) {
        dropReporter(drag, x, y, action, false);
    }

    private boolean dropReporter(ReporterDrag drag, double x, double y, ReporterDrop action, boolean preview) {
        boolean discard = action != ReporterDrop.MOVE;
        if (!movableReporter(drag) || !discard && !canvas.contains(x, y)) return false;
        var source = drag.source;
        var parkedTarget = discard ? null : parkedSocket(x, y);
        boolean overParked = !discard && canvas.parkedHit(x, y) >= 0;
        if (overParked && parkedTarget == null) return false;
        var target = discard || overParked ? null : canvas.inlineHit(x, y);
        if (target != null && (target.target() || source != null && source.call().equals(target.call()) && source.node().equals(target.node()))) return false;
        var carried = drag.fragment().at(canvas.reporterPosition(x, y));
        if (target != null && carried.procedureScope().isPresent() && FlowProcedureTools.owner(draft.snapshot(), target.call())
                .map(ComputerFlowScreen::procedureScope).filter(carried.procedureScope().orElseThrow()::equals).isEmpty()) {
            if (!preview) notice = text("procedure_parameter_scope");
            return false;
        }
        if (source != null && target != null && source.call().equals(target.call()) && carried.graph().nodes().containsKey(target.node())) return false;
        if (drag.parked != null && parkedTarget != null && drag.parked.index() == parkedTarget.index()
                && carried.graph().nodes().containsKey(parkedTarget.slot().node())) return false;
        var candidate = new FlowDraft(draft.snapshot());
        var layout = candidate.snapshot().canvas().values();
        var parked = new ArrayList<>(layout.parked());
        int removedParkedRoot = -1;
        if (source == null) {
            int index = drag.parked.index();
            var parent = parked.get(index);
            String node = drag.parked.slot().node();
            if (parent.isRoot(node)) {
                parked.remove(index);
                removedParkedRoot = index;
            } else {
                var fallback = parent.shadows().get(node);
                if (fallback == null)
                    fallback = InlineGraphEdits.defaultValue(parkedTypes(carried.graph(), draft.snapshot()).get("value"));
                parked.set(index, parent.restoreChild(node, fallback));
            }
            candidate.editValues(layout.withParked(parked));
        } else {
            var step = candidate.find(source.call()).orElseThrow();
            var graph = inlineGraph(step);
            var defaults = layout.forBlock(source.call());
            DataValue fallback = defaults.get(source.node());
            if (fallback == null) {
                boolean root = source.node().equals(graph.outputs().get("value"));
                fallback = root && step instanceof FlowProgram.Repeat ? new DataValue.IntegerValue(10)
                    : root && step instanceof FlowProgram.Sleep sleep ? new DataValue.IntegerValue(1)
                    : InlineGraphEdits.defaultValue(inlineType(carried.graph()));
            }
            var restored = FlowReporterEdits.restore(graph, FlowInlineValues.bindings(step), defaults, source.node(), fallback);
            validateInline(step, restored.graph(), restored.bindings());
            candidate.editInline(source.call(), restored.graph(), restored.bindings(), layout.with(source.call(), restored.shadows(), parked));
        }
        layout = candidate.snapshot().canvas().values();
        if (parkedTarget != null) {
            int adjusted = parkedTarget.index();
            if (removedParkedRoot >= 0 && removedParkedRoot < adjusted) adjusted--;
            attachParked(candidate, adjusted, parkedTarget.slot().node(), carried, x, y, preview);
        } else if (target != null) {
            var step = candidate.find(target.call()).orElseThrow();
            var graph = FlowInlineValues.graph(candidate.snapshot(), step, expectedValue(step));
            var bindings = FlowInlineValues.bindings(step);
            var defaults = layout.forBlock(target.call());
            var locals = hintLocals(target.call());
            carried.graph().inputs().forEach((name, type) -> {
                var actual = locals.get(carried.arguments().get(name));
                if (actual == null || !type.compatibleWith(actual))
                    throw new IllegalArgumentException("Reporter input is unavailable here: " + carried.arguments().get(name));
            });
            boolean displace = !(graph.nodes().get(target.node()) instanceof ComputationGraph.Constant
                || graph.nodes().get(target.node()) instanceof ComputationGraph.ListPosition);
            if (displace) {
                var displaced = FlowReporterEdits.take(graph, bindings, defaults, target.node(), canvas.displacedPosition(x, y));
                parked.add(scopeReporter(target.call(), displaced));
            }
            var edit = FlowReporterEdits.attach(graph, bindings, defaults, target.node(), carried);
            bindExtensionReporters(candidate, carried.graph());
            validateInline(step, edit.graph(), edit.bindings(), candidate.snapshot());
            candidate.editInline(target.call(), edit.graph(), edit.bindings(), layout.with(target.call(), edit.shadows(), parked));
            if (!preview && displace) canvas.placeLastDisplacedReporter(candidate);
        } else if (!discard) {
            parked.add(carried);
            candidate.editValues(layout.withParked(parked));
        }
        if (preview) return true;
        switch (action) {
            case DELETE -> draft.acceptDeletion(candidate, workspace);
            case MOVE -> draft.acceptReporterEdit(candidate, workspace);
            case CUT -> draft.accept(candidate); // A cut preserves the clipboard's existing binding.
        }
        publish(); init();
        return true;
    }
    private void openConstructor(ComputationGraph graph, FlowCallBindings bindings, String node, String context, int parked) {
        var form = new InlineGraphEdits.ConstructorDraft(graph, bindings, node);
        canvas.cancel();
        construction = form;
        constructionCall = context;
        constructionParked = parked;
        constructionPage = 0;
        constructionFocus = null;
        constructionParents.clear();
        init();
        notice = text("construction_hint");
    }
    private void editParkedValue(FlowBlockCanvas.ParkedSlot parked) {
        var reporter = draft.snapshot().canvas().values().parked().get(parked.index());
        String id = parked.slot().node();
        var node = reporter.graph().nodes().get(id);
        if (node instanceof ComputationGraph.MakeList || node instanceof ComputationGraph.MakeRecord) {
            openConstructor(reporter.graph(), new FlowCallBindings(reporter.arguments(), Map.of()), id,
                reporter.procedureScope().map(ScratchValueLayout.ProcedureScope::source).orElse(null), parked.index());
        } else if (node instanceof ComputationGraph.Constant literal) {
            editingParked = parked;
            openInlineField(parked.slot(), editableText(literal.value()));
        }
    }
    private static String editableText(DataValue value) {
        return value instanceof DataValue.TextValue string ? string.value() : EditorValueFormat.format(value);
    }
    private void openInlineField(FlowBlockCanvas.Slot slot, String value) {
        canvas.cancel();
        int fieldWidth = Math.min(160, Math.max(44, (int)slot.width()));
        int fieldX = Math.clamp((int)slot.x(), leftPos + (paletteX + paletteWidth + 8), leftPos + imageWidth - fieldWidth - 8);
        int fieldY = Math.clamp((int)slot.y(), topPos + TOP, topPos + imageHeight - 74);
        inlineField = addRenderableWidget(new EditBox(font, fieldX, fieldY, fieldWidth, 18, text("inline_value")));
        inlineField.setMaxLength(ProgramEditorWire.MAX_BYTES); inlineField.setValue(value); setFocused(inlineField);
        inlineField.setHighlightPos(0); notice = text("inline_edit_hint");
    }
    private static EditorValueFormat.Kind literalKind(DataValue value) {
        return switch (value) {
            case DataValue.IntegerValue ignored -> EditorValueFormat.Kind.INTEGER;
            case DataValue.DecimalValue ignored -> EditorValueFormat.Kind.DECIMAL;
            case DataValue.BooleanValue ignored -> EditorValueFormat.Kind.BOOLEAN;
            case DataValue.TextValue ignored -> EditorValueFormat.Kind.TEXT;
            case DataValue.NbtValue ignored -> EditorValueFormat.Kind.NBT;
            default -> EditorValueFormat.Kind.JSON;
        };
    }
    private static void bindExtensionReporter(FlowDraft candidate, InlineGraphEdits.Source source) {
        if (!(source instanceof InlineGraphEdits.ProcedureResult result) || !com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(result.procedure().key())) return;
        bindExtensionReporter(candidate, result.procedure().key());
    }
    private static void bindExtensionReporter(FlowDraft candidate, ModuleDefinition.Key key) {
        candidate.ensureTemplate(enabledExtensionReporter(candidate.snapshot(), key).template("extension"));
    }
    private Map<String, DataType> parkedTypes(ComputationGraph graph, FlowDocumentBody context) {
        var signatures = new LinkedHashMap<>(FlowProgram.valueProcedures(FlowProcedureTools.definitions(context)));
        for (var key : extensionReporterKeys(graph)) signatures.computeIfAbsent(key, missing -> {
            // Only a read-only signature is needed for editing, even if the extension is disabled.
            var binding = ScratchExtensions.binding(missing)
                .orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension: " + missing.id()));
            return new ProgramCompiler.ProcedureSignature(binding.command().inputs(), binding.command().outputs());
        });
        return inlineTypes(graph, signatures);
    }
    private static ScratchExtensions.Binding enabledExtensionReporter(FlowDocumentBody context, ModuleDefinition.Key key) {
        var binding = ScratchExtensions.binding(key).orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension: " + key.id()));
        ScratchExtensions.requireEnabled(binding.extension(), context.canvas().extensions());
        if (binding.command().shape() == com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape.COMMAND)
            throw new IllegalArgumentException("Not a Scratch extension reporter: " + key.id());
        return binding;
    }
    private DataType inlineType(ComputationGraph graph) { return inlineTypes(graph,draft.snapshot()).get("value"); }
    private Map<String,DataType> inlineTypes(ComputationGraph graph, FlowDocumentBody context) {
        return inlineTypes(graph, FlowProgram.valueProcedures(FlowProcedureTools.definitions(context)));
    }
    private Map<String,DataType> inlineTypes(ComputationGraph graph,
            Map<ModuleDefinition.Key, ProgramCompiler.ProcedureSignature> signatures) {
        var definition=new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),workspace.root().cells(),List.of(),List.of(),
            Map.of("inline",new ProgramDefinition.Entry(graph,List.of(),List.of())));
        return ProgramCompiler.compileFlow(definition,workspace.snapshot().archive().modules(),menu.response().orElseThrow().hardware(),
            signatures).select("inline").root().outputType().fields();
    }
    private void validateInline(FlowProgram.Step step, ComputationGraph graph, FlowCallBindings bindings) {
        validateInline(step, graph, bindings, draft.snapshot());
    }
    private void validateInline(FlowProgram.Step step, ComputationGraph graph, FlowCallBindings bindings, FlowDocumentBody context) {
        var outputs=inlineTypes(graph,context);
        if (step instanceof FlowProgram.Invoke call && call.operands().isPresent()) {
            var declared = FlowProcedureTools.definitions(context).stream().filter(p -> p.key().equals(call.procedure()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing procedure definition")).inputs();
            if (!declared.keySet().equals(outputs.keySet())) throw new IllegalArgumentException("Call arguments no longer match the definition");
            declared.forEach((port, type) -> {
                if (!type.compatibleWith(outputs.get(port))) throw new IllegalArgumentException("Argument " + port + " requires " + type);
            });
            return;
        }
        var named=FlowNamedLists.target(step,inlineGraph(step),expectedValue(step));
        if(named.isPresent()) {
            var old=named.orElseThrow();
            var next=old.shared()?FlowNamedLists.target(step,graph,expectedValue(step)):FlowNamedLists.target(graph,bindings);
            if(old.type()==null || next.filter(t->t.operation()==old.operation() && t.local().equals(old.local()) && old.type().equals(t.type())).isEmpty()
                    || !old.type().equals(outputs.get("value"))) throw new IllegalArgumentException(text("named_list_select").getString());
        } else if (!(step instanceof FlowProgram.Evaluate) && !acceptsInlineValue(step, outputs.get("value")))
            throw new IllegalArgumentException("Value slot requires "+expectedValue(step));
    }
    private boolean acceptsInlineValue(FlowProgram.Step step, DataType type) {
        return step instanceof FlowProgram.Sleep sleep ? sleep.accepts(type)
            : com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterAssignment.permits(expectedValue(step), type);
    }
    private boolean overPalette(double x,double y) {
        return x>=leftPos+paletteX && x<leftPos+paletteX+paletteWidth && y>=topPos+TOP && y<topPos+imageHeight-52;
    }
    private void closeReporterMenu() {
        openReaderPreset = null;
        reporterMenu.forEach(this::removeWidget);
        reporterMenu.clear();
    }
    private boolean movableReporter(ReporterDrag drag) {
        if (drag.parked == null) return true;
        var parent = draft.snapshot().canvas().values().parked().get(drag.parked.index());
        String node = drag.parked.slot().node();
        return parent.isRoot(node) || !(parent.graph().nodes().get(node) instanceof ComputationGraph.Constant
            || parent.graph().nodes().get(node) instanceof ComputationGraph.ListPosition);
    }

    private BlockMenuItem edgeDirectionItem(FlowBlockCanvas.Slot slot, ComputationGraph.Scalar edge) {
        return new BlockMenuItem(edge.operation().fallingEdge() ? "edge_to_rising" : "edge_to_falling", () -> {
            var step = draft.find(slot.call()).orElseThrow();
            var graph = inlineGraph(step);
            if (!edge.equals(graph.nodes().get(slot.node()))) return;
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put(slot.node(), new ComputationGraph.Scalar(edge.operation().oppositeEdge(), edge.arguments()));
            var changed = new ComputationGraph(graph.inputs(), nodes, graph.outputs());
            var bindings = FlowInlineValues.bindings(step);
            validateInline(step, changed, bindings);
            draft.editInline(step.sourceId(), changed, bindings, draft.snapshot().canvas().values()); publish();
        });
    }
    private void showReporterMenu(ReporterDrag drag) {
        if (!movableReporter(drag)) return;
        var items=blockMenu(()->{
            var layout=draft.snapshot().canvas().values();var parked=new ArrayList<>(layout.parked());
            parked.add(drag.fragment().at(canvas.displacedPosition(drag.x,drag.y)));draft.editValues(layout.withParked(parked));publish();
        },()->copyReporter(drag,false),()->copyReporter(drag,true),()->dropReporter(drag, drag.x, drag.y, ReporterDrop.DELETE));
        var graph=drag.parked!=null ? draft.snapshot().canvas().values().parked().get(drag.parked.index()).graph()
            : inlineGraph(draft.find(drag.source.call()).orElseThrow());
        var node=graph.nodes().get(drag.parked!=null ? drag.parked.slot().node() : drag.source.node());
        if (drag.parked == null && node instanceof ComputationGraph.Scalar edge && edge.operation().numericEdge())
            items.add(edgeDirectionItem(drag.source, edge));
        if(node instanceof ComputationGraph.ProcedureValue call && !ScratchExtensions.reserved(call.procedure()))
            items.add(new BlockMenuItem("procedure_go_definition",()->goToProcedure(call.procedure())));
        if(node instanceof ComputationGraph.ProcedureValue call) addUpgrade(items,call.procedure(),
            current->FlowProgram.supportsValueCall(current),
            (current,next)->ScratchCallUpgrade.valueDefaults(call,current,next),
            (current,next)->upgradeValue(drag,current,next));
        showClipboardMenu(drag.x,drag.y,items);
    }
    private void showStatementMenu(FlowProgram.Step step,double x,double y) {
        var items=blockMenu(()->{
            selected=draft.duplicateStatements(step.sourceId(),canvas.displacedPosition(x,y));canvas.selected(selected);publish();
        },()->copyStatement(step,false),()->copyStatement(step,true),()->{draft.removeAndReleaseUnusedExtensions(step.sourceId(), workspace);publish();});
        ScratchProcedureReferences.direct(step).filter(key->!ScratchExtensions.reserved(key))
            .ifPresent(key->items.add(new BlockMenuItem("procedure_go_definition",()->goToProcedure(key))));
        if(step instanceof FlowProgram.Invoke call) addUpgrade(items,call.procedure(),
            current->ScratchCallUpgrade.supports(draft,call,current),ScratchCallUpgrade::addedDefaults,(current,next)->{
                ScratchCallUpgrade.apply(draft,call,current,next);
                selected=call.sourceId();canvas.selected(selected);publish();canvas.focus(selected);
            });
        showClipboardMenu(x,y,items);
    }
    private ArrayList<BlockMenuItem> blockMenu(Runnable duplicate,Runnable copy,Runnable cut,Runnable delete) {
        return new ArrayList<>(List.of(new BlockMenuItem("duplicate_reporter",duplicate),new BlockMenuItem("clipboard_copy",copy),
            new BlockMenuItem("clipboard_cut",cut),new BlockMenuItem("delete_reporter",delete)));
    }

    private void copyStatement(FlowProgram.Step statement, boolean cut) {
        var id = statement.sourceId();
        workspace.copyScratch(cut ? draft.cutStatements(id) : draft.copyStatements(id));
        if (cut) { selected = null; canvas.selected(null); publish(); }
    }
    private void copyEventScript(String id, boolean cut) {
        workspace.copyScratch(cut ? draft.cutEventScript(id) : draft.copyEventScript(id));
        if (cut) { selected = null; canvas.selected(null); publish(); }
    }
    private void copyReporter(ReporterDrag drag, boolean cut) {
        var fragment = new ScratchClipboard.Value(drag.fragment());
        if (cut) dropReporter(drag, drag.x, drag.y, ReporterDrop.CUT);
        workspace.copyScratch(fragment);
    }
    private void copyUnderPointer(boolean cut) {
        var parked = canvas.parkedSlotHit(pointerX, pointerY);
        if (parked != null) {
            var drag = new ReporterDrag(null, parked, pointerX, pointerY);
            if (movableReporter(drag)) copyReporter(drag, cut);
            return;
        }
        var slot = canvas.inlineHit(pointerX, pointerY);
        if (slot != null && !slot.target()) {
            var node = inlineGraph(draft.find(slot.call()).orElseThrow()).nodes().get(slot.node());
            if (!(node instanceof ComputationGraph.Constant)) {
                copyReporter(new ReporterDrag(slot, null, pointerX, pointerY), cut);
                return;
            }
        }
        var pointed = canvas.blockAt(pointerX, pointerY);
        if (edgeDefinition == null && pointed != null && FlowDraft.clipboardEventHat(pointed.step())) {
            copyEventScript(pointed.step().sourceId(), cut);
            return;
        }
        var statement = canvas.statementAt(pointerX, pointerY);
        if (statement == null) statement = selectedStep()
            .filter(step -> !(step instanceof FlowProgram.Procedure) && !FlowDraft.delimiter(step)).orElse(null);
        if (statement != null) copyStatement(statement, cut);
    }
    private void pasteClipboard(double x, double y) {
        var fragment = workspace.scratchClipboard();
        if (fragment == null) return;
        var position = canvas.pastePosition(x, y);
        switch (fragment) {
            case ScratchClipboard.EventScript script -> {
                if (edgeDefinition == null) pasteEventScript(script, position);
                return;
            }
            case ScratchClipboard.Statements statements -> {
                var candidate = new FlowDraft(draft.snapshot());
                statements.graphs().values().forEach(graph -> bindExtensionReporters(candidate, graph));
                selected = candidate.pasteStatements(statements, position);
                draft.accept(candidate);
                canvas.selected(selected);
            }
            case ScratchClipboard.Value value -> {
                var candidate = new FlowDraft(draft.snapshot());
                extensionReporterKeys(value.reporter().graph()).forEach(key -> enabledExtensionReporter(candidate.snapshot(), key));
                var layout = candidate.snapshot().canvas().values();
                var parked = new ArrayList<>(layout.parked());
                parked.add(value.reporter().at(position));
                candidate.editValues(layout.withParked(parked));
                draft.accept(candidate);
            }
        }
        publish();
    }
    private void pasteEventScript(ScratchClipboard.EventScript fragment, ProgramDocument.Position position) {
        var candidate = new FlowDraft(draft.snapshot());
        fragment.script().graphs().values().forEach(graph -> bindExtensionReporters(candidate, graph));
        selected = candidate.pasteEventScript(fragment, position);
        draft.accept(candidate);
        canvas.selected(selected);
        publish();
    }
    private record BlockMenuItem(Component caption, Runnable action) {
        BlockMenuItem(String label, Runnable action) { this(text(label), action); }
    }
    private void showClipboardMenu(double x, double y, List<BlockMenuItem> items) {
        showBlockMenu(x, y, items, null, null, false);
    }
    private void showBlockMenu(double x, double y, List<BlockMenuItem> items, BlockMenuItem header, Component headerTip, boolean pending) {
        setFocused(null);
        int stride = 21, head = header == null ? 0 : 1;
        int rows = Math.max(1, Math.min(items.size(), (imageHeight - TOP - 52) / stride - head));
        int columns = Math.ceilDiv(items.size(), rows);
        int preferred = Math.max(88, items.stream().mapToInt(item -> font.width(item.caption())).max().orElse(0) + 12);
        if (header != null) preferred = Math.max(preferred, font.width(header.caption()) + 12);
        int menuWidth = Math.min(preferred, (imageWidth - 16) / columns);
        int mx = Math.clamp((int)x - leftPos, 8, imageWidth - columns * menuWidth - 8);
        int my = Math.clamp((int)y - topPos, TOP, Math.max(TOP, imageHeight - 52 - (rows + head) * stride));
        if (header != null) {
            var capture = button(mx, my, columns * menuWidth, header.caption(), header.action());
            capture.active = !pending;
            capture.setTooltip(Tooltip.create(headerTip));
            reporterMenu.add(capture);
            my += stride;
        }
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var control = button(mx + (i / rows) * menuWidth, my + (i % rows) * stride,
                menuWidth, item.caption(), () -> act(item.action()));
            control.setTooltip(Tooltip.create(control.getMessage()));
            reporterMenu.add(control);
        }
    }
    private record OpenReaderPreset(ExtensionPreset preset, double x, double y, long responseVersion) {}
    private OpenReaderPreset openReaderPreset;
    private void refreshReaderPreset(OpenReaderPreset popup) {
        closeReporterMenu();
        showExtensionPresets(popup.preset(), popup.x(), popup.y());
    }

    private record ExtensionPreset(FlowBlockCanvas.Slot slot, int parked, InlineGraphEdits.ConstructorDraft constructor) {
        boolean attached() { return parked < 0 && constructor == null; }
    }
    private static boolean presetHit(FlowBlockCanvas.Slot slot, double x) {
        return !slot.target() && slot.label().endsWith(" ▾") && x >= slot.x() + slot.width() - slot.height();
    }
    private ComputationGraph presetGraph(ExtensionPreset preset, FlowProgram.Step step) {
        if (preset.constructor() != null) return construction == preset.constructor() ? construction.editableGraph() : null;
        if (preset.attached()) return step == null ? null : inlineGraph(step);
        var parked = draft.snapshot().canvas().values().parked();
        return preset.parked() < parked.size() ? parked.get(preset.parked()).graph() : null;
    }
    private boolean showMathFunctions(ExtensionPreset context, double x, double y) {
        var step = context.attached() ? draft.find(context.slot().call()).orElse(null) : null;
        var graph = presetGraph(context, step);
        if (graph == null || !ScratchMathFunctions.hasMenu(graph.nodes().get(context.slot().node()))) return false;
        canvas.cancel();
        showClipboardMenu(x, y, ScratchMathFunctions.OPTIONS.stream()
            .map(op -> new BlockMenuItem(ScratchMathFunctions.key(op), () -> selectMathFunction(context, graph, op))).toList());
        return true;
    }
    private void selectMathFunction(ExtensionPreset context, ComputationGraph displayed, ScalarOperation operation) {
        closeReporterMenu();
        var step = context.attached() ? draft.find(context.slot().call()).orElse(null) : null;
        var graph = presetGraph(context, step);
        if (graph == null || !graph.equals(displayed)) return;
        var changed = ScratchMathFunctions.select(graph, context.slot().node(), operation);
        if (changed == graph) return;
        if (context.constructor() != null) {
            construction.accept(new InlineGraphEdits.Edit(changed, construction.bindings()));
            return;
        }
        if (context.attached()) {
            var bindings = FlowInlineValues.bindings(step);
            validateInline(step, changed, bindings);
            draft.editInline(step.sourceId(), changed, bindings);
        } else {
            var layout = draft.snapshot().canvas().values();
            var parked = new ArrayList<>(layout.parked());
            var before = parked.get(context.parked());
            parked.set(context.parked(), new ScratchValueLayout.Reporter(before.position(), changed, before.arguments(), before.shadows(), before.procedureScope()));
            draft.editValues(layout.withParked(parked));
        }
        publish();
    }
    private boolean showExtensionPresets(ExtensionPreset preset, double x, double y) {
        var step = preset.attached() ? draft.find(preset.slot().call()).orElse(null) : null;
        var graph = presetGraph(preset, step);
        if (graph == null) return false;
        String node = preset.slot().node();
        var baseline = ScratchExtensionMenus.at(step, graph, node);
        if (baseline.isEmpty()) return false;
        canvas.cancel();
        var reader = ScratchExtensionMenus.readerKey(step, graph, node);
        var choices = ScratchExtensionMenus.choices(baseline, reader, menu.capturedInputs());
        var items = choices.options().stream().map(option ->
            new BlockMenuItem(ScratchExtensionMenus.label(option), () -> selectExtensionPreset(preset, graph, option))).toList();
        if (reader.isEmpty()) showClipboardMenu(x, y, items);
        else {
            var popup = new OpenReaderPreset(preset, x, y, menu.controlResponseVersion());
            openReaderPreset = popup;
            boolean pending = menu.readerMenuCapturePending();
            var feedback = ScratchExtensionMenus.captureFeedback(choices, pending, menu.readerMenuCaptureFailure());
            var capture = new BlockMenuItem(feedback.caption(), () -> {
                menu.control(ComputerControlSession.Action.READ_PERIPHERALS, new DataValue.RecordValue(Map.of()));
                refreshReaderPreset(popup);
            });
            showBlockMenu(x, y, items, capture, feedback.tooltip(), pending);
        }
        return true;
    }
    private void selectExtensionPreset(ExtensionPreset preset, ComputationGraph displayed,
                                       ScratchExtension.MenuOption option) {
        String node = preset.slot().node();
        var step = preset.attached() ? draft.find(preset.slot().call()).orElse(null) : null;
        var graph = presetGraph(preset, step);
        if (graph == null || !graph.equals(displayed)) return;
        var options = ScratchExtensionMenus.suggestions(ScratchExtensionMenus.at(step, graph, node),
            ScratchExtensionMenus.readerKey(step, graph, node), menu.capturedInputs());
        if (!options.contains(option) || ((ComputationGraph.Constant)graph.nodes().get(node)).value().equals(option.value())) return;
        if (preset.constructor() != null) {
            construction.replaceNode(node, new InlineGraphEdits.Literal(option.value()));
            return;
        }
        if (!preset.attached()) {
            var layout = draft.snapshot().canvas().values();
            var parked = new ArrayList<>(layout.parked());
            parked.set(preset.parked(), parked.get(preset.parked()).withLiteral(node, option.value()));
            draft.editValues(layout.withParked(parked));
        } else {
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put(node, new ComputationGraph.Constant(option.value()));
            var changed = new ComputationGraph(graph.inputs(), nodes, graph.outputs());
            var bindings = FlowInlineValues.bindings(step);
            validateInline(step, changed, bindings);
            draft.editInline(step.sourceId(), changed, bindings);
        }
        publish();
    }
    private static Set<ModuleDefinition.Key> extensionReporterKeys(ComputationGraph graph) {
        return graph.nodes().values().stream().filter(ComputationGraph.ProcedureValue.class::isInstance)
            .map(ComputationGraph.ProcedureValue.class::cast).map(ComputationGraph.ProcedureValue::procedure)
            .filter(ScratchExtensions::reserved).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    private static void bindExtensionReporters(FlowDraft candidate, ComputationGraph graph) {
        extensionReporterKeys(graph).forEach(key -> bindExtensionReporter(candidate, key));
    }
    private record ReporterSnap(Object gesture, FlowDocumentBody draft, Object response,
                                FlowBlockCanvas.Slot slot, FlowBlockCanvas.ParkedSlot parked, boolean accepted) {}
    private ReporterSnap reporterSnap;

    /** Snap feedback is the same draft-only edit as release, but never publishes or measures displaced layout. */
    FlowBlockCanvas.Slot reporterSnapTarget(double x, double y) {
        Object gesture = reporterDrag != null ? reporterDrag : takingReporter;
        if (gesture == null || !canvas.contains(x, y)) return null;
        var parked = parkedSocket(x, y);
        var slot = parked != null ? parked.slot() : canvas.parkedHit(x, y) < 0 ? canvas.inlineHit(x, y) : null;
        if (slot == null || slot.target()) return null;
        var document = draft.snapshot();
        var response = menu.response().orElse(null);
        if (reporterSnap == null || reporterSnap.gesture() != gesture || reporterSnap.draft() != document
                || reporterSnap.response() != response || !slot.equals(reporterSnap.slot()) || !Objects.equals(parked, reporterSnap.parked())) {
            boolean accepted = false;
            try {
                if (reporterDrag != null) {
                    accepted = dropReporter(reporterDrag, x, y, ReporterDrop.MOVE, true);
                } else {
                    if (parked == null) replaceInline(slot, takingReporter, canvas.displacedPosition(x, y), true);
                    else insertParkedPrototype(parked, takingReporter, x, y, true);
                    accepted = true;
                }
            } catch (RuntimeException invalid) {
                // No snap outline. The existing release path still provides the rejection reason.
            }
            reporterSnap = new ReporterSnap(gesture, document, response, slot, parked, accepted);
        }
        return reporterSnap.accepted() ? slot : null;
    }
    private FlowBlockCanvas.ParkedSlot parkedSocket(double x, double y) {
        var hit = canvas.parkedSlotHit(x, y);
        return hit != null && !hit.slot().target()
            && !draft.snapshot().canvas().values().parked().get(hit.index()).isRoot(hit.slot().node()) ? hit : null;
    }
    private ScratchValueLayout.Reporter reporterPrototype(FlowDraft candidate, InlineGraphEdits.Source source, double x, double y) {
        var parameter = source instanceof InlineGraphEdits.ProcedureParameter p ? p : null;
        if (parameter != null) {
            if (!ownsParameter(candidate.snapshot(), parameter.owner().source(), parameter))
                throw new IllegalArgumentException(text("procedure_parameter_scope").getString());
            source = parameter.variable();
        }
        var edit = InlineGraphEdits.replace(zeroGraph(), new FlowCallBindings(Map.of(), Map.of()), "value", source);
        var reporter = new ScratchValueLayout.Reporter(canvas.reporterPosition(x, y), edit.graph(), edit.bindings().arguments(), Map.of());
        return parameter != null ? reporter.scoped(parameter.owner()) : scopeReporter(selected, reporter);
    }
    private void parkPaletteReporter(InlineGraphEdits.Source source, double x, double y) {
        var candidate = new FlowDraft(draft.snapshot());
        var reporter = reporterPrototype(candidate, source, x, y);
        var values = candidate.snapshot().canvas().values();
        var parked = new ArrayList<>(values.parked());
        parked.add(reporter);
        candidate.editValues(values.withParked(parked));
        draft.accept(candidate);
        publish(); init();
    }
    private void insertParkedPrototype(FlowBlockCanvas.ParkedSlot socket, InlineGraphEdits.Source source, double x, double y) {
        insertParkedPrototype(socket, source, x, y, false);
    }
    private void insertParkedPrototype(FlowBlockCanvas.ParkedSlot socket, InlineGraphEdits.Source source, double x, double y, boolean preview) {
        var candidate = new FlowDraft(draft.snapshot());
        var carried = reporterPrototype(candidate, source, x, y);
        attachParked(candidate, socket.index(), socket.slot().node(), carried, x, y, preview);
        if (preview) return;
        draft.accept(candidate);
        publish(); init();
    }
    private void attachParked(FlowDraft candidate, int index, String node, ScratchValueLayout.Reporter carried,
                              double x, double y, boolean preview) {
        var values = candidate.snapshot().canvas().values();
        var parked = new ArrayList<>(values.parked());
        var parent = parked.get(index);
        var joined = parent.attachChild(node, carried);
        var context = candidate.snapshot();
        var before = parkedTypes(parent.graph(), context).get("value");
        var after = parkedTypes(joined.graph(), context).get("value");
        if (!before.compatibleWith(after)) throw new IllegalArgumentException("Parked expression requires " + before);
        parked.set(index, joined);
        var replaced = parent.graph().nodes().get(node);
        if (!(replaced instanceof ComputationGraph.Constant || replaced instanceof ComputationGraph.ListPosition))
            parked.add(parent.subtree(node).at(canvas.displacedPosition(x, y)));
        candidate.editValues(values.withParked(parked));
        if (!preview && parked.size() > values.parked().size()) canvas.placeLastDisplacedReporter(candidate);
    }

    private void commitSlot() {
        try {
            if(editingParked!=null) {
                var layout=draft.snapshot().canvas().values();var parked=new ArrayList<>(layout.parked());int index=editingParked.index();
                var reporter=parked.get(index);String node=editingParked.slot().node();
                var old=(ComputationGraph.Constant)reporter.graph().nodes().get(node);
                parked.set(index,reporter.withLiteral(node,EditorValueFormat.parse(literalKind(old.value()),inlineField.getValue())));
                draft.editValues(layout.withParked(parked));cancelSlot();publish();init();return;
            }
            if (construction != null && constructionLiteralNode != null) {
                var value = ((ComputationGraph.Constant)construction.node(constructionLiteralNode)).value();
                var kind = value instanceof DataValue.IntegerValue?EditorValueFormat.Kind.INTEGER:value instanceof DataValue.DecimalValue?EditorValueFormat.Kind.DECIMAL:value instanceof DataValue.BooleanValue?EditorValueFormat.Kind.BOOLEAN:value instanceof DataValue.TextValue?EditorValueFormat.Kind.TEXT:EditorValueFormat.Kind.JSON;
                construction.replaceNode(constructionLiteralNode, new InlineGraphEdits.Literal(EditorValueFormat.parse(kind, inlineField.getValue())));
                cancelSlot();
                init();
                return;
            }
            var slot = editingSlot; var step=draft.find(slot.call()).orElseThrow(); var graph = inlineGraph(step);
            var bindings = FlowInlineValues.bindings(step);
            if (slot.target()) bindings = new FlowCallBindings(bindings.arguments(), Map.of("value", inlineField.getValue()));
            else {
                var value = ((ComputationGraph.Constant)graph.nodes().get(slot.node())).value();
                var kind = value instanceof DataValue.IntegerValue ? EditorValueFormat.Kind.INTEGER : value instanceof DataValue.DecimalValue ? EditorValueFormat.Kind.DECIMAL
                    : value instanceof DataValue.BooleanValue ? EditorValueFormat.Kind.BOOLEAN : value instanceof DataValue.TextValue ? EditorValueFormat.Kind.TEXT
                    : value instanceof DataValue.NbtValue ? EditorValueFormat.Kind.NBT : EditorValueFormat.Kind.JSON;
                var nodes = new LinkedHashMap<>(graph.nodes());
                boolean waitAmount = step instanceof FlowProgram.Sleep && slot.node().equals(graph.outputs().get("value"));
                nodes.put(slot.node(), new ComputationGraph.Constant(waitAmount ? waitNumber(inlineField.getValue())
                    : EditorValueFormat.parse(kind, inlineField.getValue())));
                graph = new ComputationGraph(graph.inputs(), nodes, graph.outputs());
                if (step instanceof FlowProgram.Invoke invoke && invoke.operands().isPresent()) {
                    FlowReporterEdits.requireUnshared(graph, slot.node());
                    validateInline(step, graph, bindings);
                }
            }
            draft.editInline(slot.call(), graph, bindings); cancelSlot(); publish(); init();
        } catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
    }
    private static DataValue waitNumber(String text) {
        try { return EditorValueFormat.parse(EditorValueFormat.Kind.INTEGER, text.strip()); }
        catch (NumberFormatException notInteger) { return EditorValueFormat.parse(EditorValueFormat.Kind.DECIMAL, text.strip()); }
    }
    @Override public void onClose() {
        reporterDrag=null;closeReporterMenu();
        closeVariableChoices();
        closeConstructor();
        constructionParents.clear();
        taking = null;
        takingReporter = null;
        takingProcedure = null;
        cancelSlot();
        canvas.cancel();
        close.run();
    }
    @Override public void removed() {}
    private EditBox field(int x, int y, int width, String key, String value, Consumer<String> changed) {
        var field = addRenderableWidget(new EditBox(font, leftPos + x, topPos + y, width, 18, text(key)));
        field.setMaxLength(ProgramEditorWire.MAX_BYTES + 1); field.setValue(value);
        field.setHint(Component.literal(font.plainSubstrByWidth(text(key).getString(), width - 8)));
        field.setTooltip(Tooltip.create(text(key))); field.setResponder(changed); return field;
    }
    private CreateTextButton button(int x, int y, int w, String key, Runnable action) { return button(x, y, w, text(key), action); }
    private CreateTextButton button(int x, int y, int w, Component text, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, text, action, true));
        button.setTooltip(Tooltip.create(text)); return button;
    }
    private static boolean delimiter(FlowProgram.Step step) {
        return FlowDraft.delimiter(step);
    }
    private static String inputLabel(FlowInput input, ComputationGraph inline) {
        return switch (input) {
            case FlowInput.Local local -> local.name();
            case FlowInput.Literal literal -> {
                try { yield EditorValueFormat.format(literal.value()); }
                catch (IllegalArgumentException invalid) { yield text("invalid_literal_label").getString(); }
            }
            case FlowInput.Expression expression -> {
                if (inline == null) yield expression.entry() + "." + expression.output();
                // Present the installed Scratch operand, never evaluate it or read live variable values.
                var output = inline.outputs().get(expression.output());
                var root = output == null ? null : inline.nodes().get(output);
                if (root instanceof ComputationGraph.Constant literal)
                    yield inputLabel(new FlowInput.Literal(literal.value()), null);
                if (root instanceof ComputationGraph.Input local) yield expression.argument(local.name());
                if (root instanceof ComputationGraph.ReadMemory cell)
                    yield text("inline_public", cell.cell()).getString();
                yield text("inline_expression_label").getString();
            }
        };
    }
    private static String broadcastChannel(FlowProgram.Step step) {
        return step instanceof FlowProgram.Broadcast send?send.channel():((FlowProgram.Procedure)step).event().orElseThrow();
    }
    private void replaceBroadcastChannel(FlowProgram.Step step,String channel) {
        if(step instanceof FlowProgram.Broadcast send) draft.replace(step.sourceId(),new FlowProgram.Broadcast(send.sourceId(),channel,send.await()));
        else { var p=(FlowProgram.Procedure)step; draft.replace(p.sourceId(),new FlowProgram.Procedure(p.sourceId(),p.key(),p.inputs(),p.outputs(),p.returns(),p.rules(),p.assembly(),Optional.of(channel),p.terminalKey(),p.startup())); }
    }
    private void editBroadcastChannel(FlowProgram.Step step, boolean create) {
        kind = step instanceof FlowProgram.Broadcast send ? send.await() ? Kind.BROADCAST_WAIT : Kind.BROADCAST : Kind.RECEIVE_BROADCAST;
        editing = step.sourceId(); local = create ? "" : broadcastChannel(step);
        creatingBroadcastChannel = create; parameters = true; init();
    }
    private static String stopModeKey(FlowProgram.StopMode mode) { return "stop_mode_" + mode.name().toLowerCase(Locale.ROOT); }
    private void showStopMenu(FlowProgram.StopScript stop, double x, double y) {
        showClipboardMenu(x, y, Arrays.stream(FlowProgram.StopMode.values()).map(mode ->
            new BlockMenuItem(stopModeKey(mode), () -> act(() -> {
                draft.replace(stop.sourceId(), new FlowProgram.StopScript(stop.sourceId(), mode), canvas.blockPosition(stop.sourceId()));
                publish();
            }))).toList());
    }
    private void showWaitUnitMenu(FlowProgram.Sleep sleep, double x, double y) {
        closeReporterMenu(); setFocused(null);
        var units = FlowProgram.WaitUnit.values();
        int width = Math.min(imageWidth - 16, Arrays.stream(units)
            .map(FlowInlineValues::waitUnitLabel).mapToInt(font::width).max().orElse(36) + 24);
        int mx = Math.clamp((int)x - leftPos, 8, imageWidth - width - 8);
        int my = Math.clamp((int)y - topPos, TOP, Math.max(TOP, imageHeight - 47 - units.length * 21));
        for (var unit : units) {
            var option = button(mx, my + unit.ordinal() * 21, width, FlowInlineValues.waitUnitLabel(unit), () -> {
                closeReporterMenu();
                act(() -> {
                    draft.replace(sleep.sourceId(), new FlowProgram.Sleep(sleep.sourceId(), sleep.ticks(), Optional.of(unit)));
                    publish();
                });
            });
            option.active = unit != sleep.displayedUnit();
            reporterMenu.add(option);
        }
    }
    private void showBroadcastMenu(FlowProgram.Step step, double x, double y, int page) {
        closeReporterMenu(); setFocused(null);
        var channels = FlowBlockPalette.eventChannels(draft);
        int rows = Math.max(2, Math.min(9, (imageHeight - TOP - 52) / 21));
        int pageSize = rows - 1;
        boolean paged = channels.size() > pageSize;
        if (paged) pageSize = Math.max(1, rows - 2);
        int start = page * pageSize, end = Math.min(start + pageSize, channels.size());
        int width = Math.min(imageWidth - 16, Math.max(92, font.width(text("broadcast_new_channel")) + 14));
        int mx = Math.clamp((int)x - leftPos, 8, imageWidth - width - 8);
        int my = Math.clamp((int)y - topPos, TOP, Math.max(TOP, imageHeight - 52 - (end - start + (paged ? 2 : 1)) * 21));
        for (int index = start; index < end; index++, my += 21) {
            var channel = channels.get(index);
            var option = button(mx, my, width, Component.literal(channel), () -> act(() -> {
                replaceBroadcastChannel(step, channel); publish();
            }));
            option.setTooltip(Tooltip.create(Component.literal(channel)));
            option.active = !channel.equals(broadcastChannel(step)); reporterMenu.add(option);
        }
        if (paged) {
            var previous = button(mx, my, width / 2 - 1, Component.literal("<"), () -> showBroadcastMenu(step, x, y, page - 1));
            var next = button(mx + width / 2, my, width - width / 2, Component.literal(">"), () -> showBroadcastMenu(step, x, y, page + 1));
            previous.active = start > 0; next.active = end < channels.size();
            reporterMenu.add(previous); reporterMenu.add(next); my += 21;
        }
        reporterMenu.add(button(mx, my, width, "broadcast_new_channel", () -> editBroadcastChannel(step, true)));
    }
    private void showKeyMenu(String current, double x, double y, Consumer<String> choose) {
        setFocused(null);
        var keys = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.CHOICES;
        int stride = 21, available = imageWidth - 16;
        int rows = Math.max(1, Math.min(7, (imageHeight - TOP - 52) / stride));
        int columns = Math.ceilDiv(keys.size(), rows);
        int cellWidth = Math.min(available / columns, 55);
        int mx = Math.clamp((int)x - leftPos, 8, imageWidth - columns * cellWidth - 8);
        int my = Math.clamp((int)y - topPos, TOP, Math.max(TOP, imageHeight - 52 - rows * stride));
        for (int i = 0; i < keys.size(); i++) {
            var key = keys.get(i);
            var option = button(mx + (i / rows) * cellWidth, my + (i % rows) * stride, cellWidth - 1, FlowBlockCanvas.keyLabel(key), () ->
                act(() -> choose.accept(key)));
            option.active = !current.equals(key);
            reporterMenu.add(option);
        }
    }
    private static Kind type(FlowProgram.Step step) {
        var declaration = com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockDeclarations.of(step);
        // Procedure declarations/calls use the signature editor, not the primitive parameter form.
        return switch (declaration) {
            case PROCEDURE, END_PROCEDURE, INVOKE, TRY_LAUNCH, TASK_START, TASK_JOIN -> Kind.EVALUATE;
            default -> Kind.valueOf(declaration.name());
        };
    }
    private static Component text(String key, Object... arguments) { return Component.translatable("create_biotech.biocomputing.flow_editor." + key, arguments); }
    private boolean serialKind() { return kind == Kind.SERIAL_SEND || kind == Kind.SERIAL_RECEIVE || kind == Kind.SERIAL_STATUS; }
}
