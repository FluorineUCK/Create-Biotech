package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerNodePalette.Kind;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Generic named-port canvas; reuses Pattern skins/viewport without its material/stage semantics. */
public final class ComputerProgramScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> implements ProgramSourceEditor {
    private static final int NODE_WIDTH = ComputerGraphPorts.WIDTH, NODE_HEIGHT = ComputerGraphPorts.BODY_HEIGHT;
    private final CanvasViewport viewport = new CanvasViewport();
    private final CanvasInteraction<String> interaction = new CanvasInteraction<>(viewport);
    private int gestureButton = -1;
    private double gestureX, gestureY;
    private ProgramDraft draft;
    private ProgramWorkspace workspace;
    private final Inventory editorInventory;
    private ModuleDefinition.Key activeGraph, selectedModule, selectedWhenTrue, selectedWhenFalse;
    private String activeEntry;
    private String panel, itemPort = "", resultPort = "";
    private EditBox moduleName, entryName, declarationName, declarationValue, declarationWrite;
    private String moduleText = "", entryText = "", declarationText = "", initialText = "0", writeText = "";
    private int listPage, declarationKind;
    private boolean memoryDeclarations;
    private boolean nodeBindings, declarationList;
    private int toolbarX, toolbarY, computedCanvasTop;
    private DataType retainedType;
    private ProgramCompiler.InstalledProgram preview;
    private String editing, connectingTarget, connectingPort;
    private EditBox parameter, outputName;
    private String parameterText = "0", outputText = "";
    private int kind, portPage;
    private TextOperation textOperation = TextOperation.LENGTH;
    private ScalarOperation scalarOperation = ScalarOperation.ADD_INTEGER;
    private boolean requested, waiting, resetMemory;
    private long responseVersion;
    private java.util.function.Consumer<Screen> navigator = screen -> minecraft.setScreen(screen);
    private Component notice = text("loading"), validation = Component.empty();
    private boolean paletteOpen;
    private ComputerNodePalette.Category paletteCategory = ComputerNodePalette.Category.ALL;
    private String paletteQuery = "";
    private int palettePage;
    private EditBox paletteSearch;
    private List<CreateTextButton> paletteRows = new ArrayList<>();
    private Kind palettePressed;
    private boolean paletteDragged;
    private ComputerInlineParameterEditor inline;
    private ComputerGraphPorts.Socket wireFrom, wirePress;
    private NodeTypeHints portTypes;
    private ComputerGraphPorts.Socket wireHover;
    private boolean wireHoverRejected;
    private ComputerCanvasExecutionPanel execution;
    private boolean executionOpen;
    private long executionResponseVersion;
    private enum DeclarationKind {
        INTEGER(DataType.Scalar.INTEGER, EditorValueFormat.Kind.INTEGER),
        DECIMAL(DataType.Scalar.DECIMAL, EditorValueFormat.Kind.DECIMAL),
        TEXT(DataType.Scalar.TEXT, EditorValueFormat.Kind.TEXT),
        BOOLEAN(DataType.Scalar.BOOLEAN, EditorValueFormat.Kind.BOOLEAN),
        LIST(new DataType.ListType(DataType.Scalar.ANY), EditorValueFormat.Kind.JSON),
        ANY(DataType.Scalar.ANY, EditorValueFormat.Kind.JSON);
        final DataType type; final EditorValueFormat.Kind literal;
        DeclarationKind(DataType type, EditorValueFormat.Kind literal) { this.type = type; this.literal = literal; }
    }

    public ComputerProgramScreen(ComputerProgramMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); editorInventory=inventory; }

    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init();
        setFocused(null); clearWidgets();
        cancelGesture();
        wireFrom = wirePress = null;
        wireHover = null; wireHoverRejected = false;
        portTypes = null;
        palettePressed = null; paletteDragged = false;
        toolbarX=8;toolbarY=29;
        toolbar(74, text("add"), this::openPalette).active = draft != null && !waiting;
        toolbar(74, text("reset_view"), viewport::reset);
        toolbar(120, text(resetMemory ? "reset_memory" : "preserve_memory"), () -> { resetMemory = !resetMemory; init(); });
        toolbar(78, text("entries"), () -> { panel = "entries"; listPage = 0; init(); }).active = workspace != null && !waiting;
        toolbar(86, text("modules"), () -> { panel = "modules"; listPage = 0; init(); }).active = workspace != null && !waiting;
        toolbar(96, text("declarations"), () -> { panel = "declarations"; listPage = 0; memoryDeclarations = false; declarationList=false; init(); }).active = draft != null && !waiting;
        toolbar(74, text("sends"), () -> navigator.accept(new ComputerSendScreen(menu, editorInventory, title, this, draft, this::changed, activeEntry, workspace::entryNames)))
            .active = draft != null && activeGraph == null && !waiting;
        var debugger = toolbar(94, text("debugger"), this::toggleExecution);
        debugger.active = menu.response().isPresent() && !waiting;
        debugger.green = executionVisible();
        toolbar(70, Component.translatable("create_biotech.biocomputing.terminal.edit"), () -> navigator.accept(
            new ComputerTerminalEditorScreen(menu, editorInventory, title, this, workspace, this::changed, () -> navigator.accept(this))))
            .active = workspace != null && !waiting;
        toolbar(108, text("subgraph_package"), () -> navigator.accept(new ComputerSubgraphScreen(
            menu, editorInventory, title, workspace, activeGraph, this::changed, () -> navigator.accept(this))))
            .active = workspace != null && menu.response().isPresent() && !waiting;
        toolbar(108, text("program_package"), () -> navigator.accept(new ComputerProgramDocumentScreen(
            menu, editorInventory, title, () -> workspace.snapshot(), this::importDocument, () -> navigator.accept(this))))
            .active = workspace != null && menu.response().isPresent() && !waiting;
        computedCanvasTop=toolbarY+28;
        if (paletteOpen) { clearWidgets(); computedCanvasTop=55; initPalette(); return; }
        if (panel != null) {
            clearWidgets();
            if (panel.equals("modules")) initModules(); else if (panel.equals("entries")) initEntries(); else initDeclarations();
            return;
        }
        if (editing != null) { clearWidgets(); initNode(); return; }
        button(8, imageHeight - 27, 84, text("flow"), this::openBlocks)
            .active = workspace != null && menu.response().isPresent() && !waiting;
        button(imageWidth - 152, imageHeight - 27, 70, text("install"), this::install).active = draft != null && !waiting;
        button(imageWidth - 78, imageHeight - 27, 70, text("close"), this::onClose);
        if (executionVisible()) {
            execution.layout(leftPos + imageWidth - 214, canvasY(), 206, canvasHeight());
            execution.widgets().forEach(this::addRenderableWidget);
        }
        if (inline != null) {
            inline.moveTo(draft.node(inline.node())); inline.widgets().forEach(this::addWidget); setFocused(inline.field());
        }
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label,
            () -> { if (applyInline()) action.run(); }, true));
    }
    private CreateTextButton toolbar(int width, Component label, Runnable action) {
        if(toolbarX+width>imageWidth-8){toolbarX=8;toolbarY+=24;}
        var button=button(toolbarX,toolbarY,width,label,action);toolbarX+=width+4;return button;
    }
    private int canvasTop() {
        return computedCanvasTop;
    }
    private int paletteWidth() { return Math.min(154, (imageWidth - 20) / 2); }
    private int canvasX() { return leftPos + (paletteOpen ? paletteWidth() + 12 : 8); }
    private int canvasY() { return topPos + canvasTop(); }
    private int canvasWidth() { return imageWidth - (paletteOpen ? paletteWidth() + 20 : 16) - (executionVisible() ? 214 : 0); }
    private int canvasHeight() { return imageHeight - canvasTop() - 65; }
    private boolean executionFits() { return imageWidth >= 480 && canvasHeight() >= 150; }
    private boolean executionVisible() { return executionOpen && execution != null && executionFits() && !paletteOpen && editing == null && panel == null; }
    private void openExecutionInputs() { navigator.accept(new ComputerExecutionScreen(menu, editorInventory, title, this)); }
    private void openBlocks() {
        navigator.accept(new ComputerFlowScreen(menu, editorInventory, title, workspace, this::importDocument, this::changed,
            () -> navigator.accept(this), () -> navigator.accept(new ComputerProgramDocumentScreen(menu, editorInventory, title,
                () -> workspace.snapshot(), this::importDocument, () -> navigator.accept(this))), this::openExecutionInputs));
    }
    private void toggleExecution() {
        if (!executionFits()) { openExecutionInputs(); return; }
        executionOpen = !executionOpen;
        if (executionOpen && execution == null)
            execution = new ComputerCanvasExecutionPanel(font, title, action -> menu.control(action, menu.debuggerInputs()), this::openExecutionInputs);
        init();
        if (executionOpen) {
            executionResponseVersion = menu.controlResponseVersion();
            execution.request(ComputerControlSession.Action.REFRESH);
        }
    }
    private boolean inCanvas(double x, double y) { return x >= canvasX() && x < canvasX() + canvasWidth() && y >= canvasY() && y < canvasY() + canvasHeight(); }
    private int modalWidth() { return Math.min(268, imageWidth - 16); }
    private int modalHeight() { return Math.min(340, imageHeight - 12); }
    private int mx() { return (imageWidth - modalWidth()) / 2; }
    private int my() { return (imageHeight - modalHeight()) / 2; }
    private boolean compactModal() { return modalHeight()<310; }
    private int portsPerPage() { return Math.max(1, (modalHeight() - (compactModal()?132:242)) / 22); }

    private void openPalette() {
        paletteOpen = true; paletteCategory = ComputerNodePalette.Category.ALL; paletteQuery = ""; palettePage = 0;
        init(); setFocused(paletteSearch);
    }
    private void closePalette() { paletteOpen = false; init(); }
    private int paletteRowCount() { return Math.max(1, (imageHeight - 114) / 22); }
    private void initPalette() {
        var categories = ComputerNodePalette.Category.values();
        int width = (imageWidth - 16 - (categories.length - 1) * 4) / categories.length;
        for (int i = 0; i < categories.length; i++) {
            var category = categories[i];
            var tab = button(8 + i * (width + 4), 29, width, category.label(), () -> {
                paletteCategory = category; palettePage = 0; init();
            });
            tab.green = category == paletteCategory; tab.setTooltip(Tooltip.create(category.label()));
        }
        paletteSearch = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 55, paletteWidth(), 18, ComputerNodePalette.text("search")));
        // Search rebuilds rows after the footer. Preserve visual keyboard order independently.
        paletteSearch.setTabOrderGroup(1);
        paletteSearch.setMaxLength(ProgramEditorWire.MAX_BYTES); paletteSearch.setValue(paletteQuery);
        paletteSearch.setHint(Component.literal(font.plainSubstrByWidth(ComputerNodePalette.text("search").getString(), paletteWidth() - 8)));
        paletteSearch.setTooltip(Tooltip.create(ComputerNodePalette.text("search")));
        paletteSearch.setResponder(query -> { paletteQuery = query; palettePage = 0; refreshPaletteRows(); });
        paletteRows = new ArrayList<>(); refreshPaletteRows();
        button(imageWidth - 78, imageHeight - 27, 70, text("back"), this::closePalette).setTabOrderGroup(4);
    }
    private void refreshPaletteRows() {
        paletteRows.forEach(this::removeWidget); paletteRows.clear();
        var matches = ComputerNodePalette.search(paletteCategory, paletteQuery); int rows = paletteRowCount();
        palettePage = Math.clamp(palettePage, 0, Math.max(0, (matches.size() - 1) / rows));
        for (int row = 0; row < rows && palettePage * rows + row < matches.size(); row++) {
            var choice = matches.get(palettePage * rows + row);
            var entry = button(8, 79 + row * 22, paletteWidth(), choice.label(), () -> selectPaletteKind(choice));
            entry.setTabOrderGroup(2);
            entry.setTooltip(Tooltip.create(choice.label())); paletteRows.add(entry);
        }
        var previous = button(8, imageHeight - 27, 32, Component.literal("<"), () -> { palettePage--; refreshPaletteRows(); });
        previous.setTabOrderGroup(3);
        previous.active = palettePage > 0; paletteRows.add(previous);
        var next = button(44, imageHeight - 27, 32, Component.literal(">"), () -> { palettePage++; refreshPaletteRows(); });
        next.setTabOrderGroup(3);
        next.active = (palettePage + 1) * rows < matches.size(); paletteRows.add(next);
    }
    private void selectPaletteKind(Kind choice) {
        selectPaletteKind(choice, viewport.worldX(canvasWidth() / 2d), viewport.worldY(canvasHeight() / 2d));
    }
    private void selectPaletteKind(Kind choice, double x, double y) {
        if (waiting || draft == null) return;
        if (editing == null) {
            paletteOpen = false; openNode(draft.add(null, x, y));
            changed();
        } else paletteOpen = false;
        kind = choice.ordinal(); parameterText = choice.initialParameter(); init();
    }
    private Kind paletteHit(double x, double y) {
        var matches = ComputerNodePalette.search(paletteCategory, paletteQuery);
        for (int i = 0; i < paletteRowCount() && palettePage * paletteRowCount() + i < matches.size(); i++)
            if (paletteRows.get(i).isMouseOver(x, y)) return matches.get(palettePage * paletteRowCount() + i);
        return null;
    }

    private void switchGraph(ModuleDefinition.Key key) {
        inline = null;
        activeGraph = key; draft = key == null ? activeEntry == null ? workspace.root() : workspace.entry(activeEntry) : workspace.graph(key);
        editing = connectingTarget = connectingPort = panel = null;
        memoryDeclarations = false; retainedType = null; viewport.reset(); changed(); init();
    }
    private void switchEntry(String name) {
        inline = null;
        activeEntry = name; activeGraph = null; draft = name == null ? workspace.root() : workspace.entry(name);
        editing = connectingTarget = connectingPort = panel = null;
        memoryDeclarations = false; retainedType = null; viewport.reset(); changed(); init();
    }
    /** Replace only the local draft after preparing the complete document; installation stays explicit. */
    void importDocument(ProgramDocument document) {
        if (workspace == null || waiting || menu.response().isEmpty()) throw new IllegalStateException("Program draft is unavailable");
        var candidate = new ProgramWorkspace(document);
        candidate.snapshot();
        workspace = candidate;
        activeEntry = null; selectedModule = selectedWhenTrue = selectedWhenFalse = null;
        listPage = portPage = 0; nodeBindings = declarationList = false;
        switchGraph(null);
    }
    @Override public Component valuePathActionTitle() {
        return Component.translatable("create_biotech.biocomputing.tree."
            + (workspace != null && workspace.flow().isPresent() ? "generate_expression" : "generate"));
    }
    /** Local editing sugar: event root or a new Flow expression, never an install or world read. */
    @Override public void importValuePath(DataValue.RecordValue frozenInputs, ValuePath path) {
        if (workspace == null || waiting) throw new IllegalStateException("Program draft is unavailable");
        if (workspace.flow().isPresent()) {
            var inserted = workspace.createPathExpression(frozenInputs, path, 20, 20);
            switchEntry(inserted.entry());
            var last = draft.node(inserted.node());
            viewport.pan(20 - last.x(), 20 - last.y());
            notice = text("path_expression_generated", inserted.entry(), inserted.output());
            return;
        }
        var root = workspace.root();
        double y = root.nodes().stream().mapToDouble(ProgramDraft.Node::y).max().orElse(-60) + 80;
        var inserted = PathNodeBuilder.insert(root, frozenInputs, path, 20, y);
        activeEntry = null; switchGraph(null);
        var last = root.node(inserted.node());
        viewport.pan(20 - last.x(), 20 - last.y());
        notice = text("path_generated", inserted.output());
    }
    private EditBox field(int x, int y, int width, String key, String value, java.util.function.Consumer<String> responder) {
        var box = addRenderableWidget(new EditBox(font, leftPos+x, topPos+y, width,18,text(key)));
        box.setMaxLength(Math.max(ProgramEditorWire.MAX_BYTES, value == null ? 0 : value.length())); box.setValue(value == null ? "" : value); box.setHint(text(key)); box.setResponder(responder); return box;
    }
    private void initModules() {
        int x=mx()+10,y=my()+24,w=modalWidth()-20;
        moduleName=field(x,y,w,"module_name",moduleText,v->moduleText=v);
        button(x,y+24,w,text("create_module"),()->{
            try { var key=workspace.createModule(moduleText); moduleText=""; switchGraph(key); }
            catch(IllegalArgumentException | ComputationException error) { validation=text("invalid_parameter",error.getMessage()); }
        });
        if (activeEntry == null) button(x,y+48,w,text("root_graph"),()->switchEntry(null));
        else {
            int returnWidth=(w-4)/2;
            button(x,y+48,returnWidth,text("return_entry"),()->switchGraph(null));
            button(x+returnWidth+4,y+48,w-returnWidth-4,text("root_graph"),()->switchEntry(null));
        }
        var keys=workspace.keys(); int rows=Math.max(1,(modalHeight()-132)/22);
        listPage=Math.min(listPage,Math.max(0,(keys.size()-1)/rows));
        for(int row=0;row<rows;row++) {
            int index=listPage*rows+row;if(index>=keys.size())break;
            var key=keys.get(index);
            button(x,y+74+row*22,w,Component.literal(key.id()+" @ "+key.revision()),()->switchGraph(key));
        }
        panelFooter(x,w,rows,keys.size());
    }
    private void initEntries() {
        int x=mx()+10,y=my()+24,w=modalWidth()-20;
        entryName=field(x,y,w,"entry_name",entryText,v->entryText=v);
        button(x,y+24,w,text("create_entry"),()->{
            try { workspace.createEntry(entryText); var name=entryText; entryText=""; switchEntry(name); }
            catch(IllegalArgumentException | ComputationException error) { validation=text("invalid_parameter",error.getMessage()); }
        });
        button(x,y+48,w,text("root_graph"),()->switchEntry(null));
        var names=workspace.entryNames(); int rows=Math.max(1,(modalHeight()-132)/22);
        listPage=Math.min(listPage,Math.max(0,(names.size()-1)/rows));
        for(int row=0;row<rows;row++) {
            int index=listPage*rows+row;if(index>=names.size())break;String name=names.get(index);
            button(x,y+74+row*22,w,Component.literal(name),()->switchEntry(name));
        }
        panelFooter(x,w,rows,names.size());
    }
    private void panelFooter(int x,int w,int rows,int count) {
        int bottom=my()+modalHeight()-26;
        button(x,bottom,28,Component.literal("<"),()->{listPage--;init();}).active=listPage>0;
        button(x+32,bottom,28,Component.literal(">"),()->{listPage++;init();}).active=(listPage+1)*rows<count;
        button(x+w-78,bottom,78,text("back"),()->{panel=null;init();});
    }
    private void initDeclarations() {
        int x=mx()+10,y=my()+24,w=modalWidth()-20;
        button(x,y,w,text("declaration_mode",text(memoryDeclarations?"memory_cells":"named_inputs")),()->{
            memoryDeclarations=!memoryDeclarations; listPage=0; declarationText=""; retainedType=null; init();
        }).active=activeGraph==null;
        declarationName=field(x,y+24,w,"declaration_name",declarationText,v->declarationText=v);
        button(x,y+46,w,text("declaration_type",retainedType==null?DeclarationKind.values()[declarationKind].name():retainedType.toString()),()->{
            retainedType=null; declarationKind=(declarationKind+1)%DeclarationKind.values().length;
            initialText=switch(DeclarationKind.values()[declarationKind]) {case INTEGER,DECIMAL->"0";case TEXT->"";case BOOLEAN->"false";case LIST->"[]";case ANY->"null";};init();
        });
        declarationValue=field(x,y+70,w,"initial_value",initialText,v->initialText=v); declarationValue.setEditable(memoryDeclarations);
        declarationWrite=field(x,y+94,w,"write_output",writeText,v->writeText=v); declarationWrite.setEditable(memoryDeclarations);
        button(x,y+116,w-82,text("save_declaration"),()->{
            try {
                var type=retainedType==null?DeclarationKind.values()[declarationKind].type:retainedType;
                if(memoryDeclarations) {
                    var value=EditorValueFormat.parse(retainedType==null?DeclarationKind.values()[declarationKind].literal:EditorValueFormat.Kind.JSON,initialText);
                    if(!writeText.isBlank())new ProgramDefinition.Write(declarationText,writeText);
                    draft.putCell(declarationText,type,value); draft.setWrite(declarationText,writeText.isBlank()?null:writeText);
                } else draft.putInput(declarationText,type);
                changed();init();
            } catch(IllegalArgumentException | ComputationException error) { validation=text("invalid_parameter",error.getMessage()); }
        });
        button(x+w-78,y+116,78,text("delete"),()->{
            if(memoryDeclarations)draft.removeCell(declarationText);else draft.removeInput(declarationText);changed();init();
        });
        if(compactModal() && declarationList)children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).forEach(widget->widget.visible=false);
        var names=new ArrayList<>(memoryDeclarations?draft.cells().keySet():draft.inputs().keySet());
        int rows=Math.max(1,(modalHeight()-(compactModal()?98:216))/22);
        listPage=Math.min(listPage,Math.max(0,(names.size()-1)/rows));
        for(int row=0;row<rows && (!compactModal() || declarationList);row++) {
            int index=listPage*rows+row;if(index>=names.size())break; String name=names.get(index);
            button(x,y+(compactModal()?0:140)+row*22,w,Component.literal(name),()->selectDeclaration(name));
        }
        panelFooter(x,w,rows,compactModal()&&!declarationList?0:names.size());
        if(compactModal())button(x+64,my()+modalHeight()-26,88,text(declarationList?"edit_values":"list_values"),()->{declarationList=!declarationList;init();});
    }
    private void selectDeclaration(String name) {
        declarationList=false;
        declarationText=name;
        var cell=draft.cells().get(name); var type=memoryDeclarations?cell.type():draft.inputs().get(name);
        retainedType=type; declarationKind=DeclarationKind.ANY.ordinal();
        for(var candidate:DeclarationKind.values())if(candidate.type.equals(type)) { declarationKind=candidate.ordinal();retainedType=null;break; }
        if(memoryDeclarations) {
            try {
                initialText=cell.initial() instanceof DataValue.TextValue t && retainedType==null && declarationKind==DeclarationKind.TEXT.ordinal()?t.value():EditorValueFormat.format(cell.initial());
            } catch(IllegalArgumentException error) { initialText="";validation=text("invalid_parameter",error.getMessage()); }
            writeText=draft.writers(name).stream().findFirst().orElse("");
            notice=text("cell_references",String.join(", ",draft.readers(name)),String.join(", ",draft.writers(name)));
        }
        init();
    }

    private void openNode(String id) {
        editing = id; portPage = 0; nodeBindings=false;
        var definition = draft.node(id).definition();
        kind = kindOf(definition); parameterText = parameterOf(definition);
        if(definition instanceof ComputationGraph.Text text)textOperation=text.operation();
        if(definition instanceof ComputationGraph.Scalar scalar)scalarOperation=scalar.operation();
        selectedModule = definition instanceof ComputationGraph.Call call ? call.module() : definition instanceof ComputationGraph.Iterate loop ? loop.module() : null;
		if(definition instanceof ComputationGraph.Branch branch){selectedWhenTrue=branch.whenTrue();selectedWhenFalse=branch.whenFalse();}
		else {selectedWhenTrue=null;selectedWhenFalse=null;}
        if(definition instanceof ComputationGraph.Iterate loop) {itemPort=loop.itemPort();resultPort=loop.resultPort();}
        else {itemPort="";resultPort="";}
        outputText = draft.outputs().entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse("");
        init();
    }
    private void initNode() {
        int x = mx() + 10, y = my() + 24, w = modalWidth() - 20;
        boolean setupVisible=!compactModal()||!nodeBindings, bindingsVisible=!compactModal()||nodeBindings;
        var kindButton=button(x, y, w, kind < 0 ? text("retained_node") : text("kind", text("kind." + Kind.values()[kind].name().toLowerCase(Locale.ROOT))), this::openPalette);
        kindButton.active=kind>=0;kindButton.visible=setupVisible;
        parameter = addRenderableWidget(new EditBox(font, leftPos + x, topPos + y + 24, w, 18, text("parameter")));
        parameter.setMaxLength(Math.max(ProgramEditorWire.MAX_BYTES, parameterText.length())); parameter.setValue(parameterText); parameter.setResponder(value -> parameterText = value);
        parameter.setHint(text(kind == Kind.RECORD.ordinal() ? "record_key" : kind == Kind.LIST.ordinal() ? "list_count" : "parameter")); parameter.setEditable(kind >= 0);
        if (kind == Kind.LIST.ordinal()) parameter.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("list_count")));
        parameter.visible=setupVisible;
        if(kind==Kind.SCALAR.ordinal()||kind==Kind.TEXT_OPERATION.ordinal()||kind==Kind.SORT.ordinal()||kind==Kind.CONCAT_LISTS.ordinal()||kind==Kind.DYNAMIC_INDEX.ordinal()||kind==Kind.DISTINCT.ordinal()||kind==Kind.VALUE_EQUALS.ordinal()||kind==Kind.DYNAMIC_FIELD.ordinal()||kind==Kind.HAS_FIELD.ordinal()||kind==Kind.KEYS.ordinal())parameter.visible=false;
        if (kind >= 0 && Kind.values()[kind].listOperation() != null) {
            parameter.visible = false;
            kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text(Kind.values()[kind].id() + "_hint")));
        }
        if (kind == Kind.VALUE_EQUALS.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("value_equals_hint")));
        if (kind == Kind.DISTINCT.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("distinct_hint")));
        if (kind == Kind.DYNAMIC_INDEX.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("dynamic_index_hint")));
        if (kind == Kind.DYNAMIC_FIELD.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("dynamic_field_hint")));
        if (kind == Kind.HAS_FIELD.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("has_field_hint")));
        if (kind == Kind.KEYS.ordinal()) kindButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("keys_hint")));
        if(isModuleKind()) {
            parameter.visible=false; parameter.setEditable(false);
            if(selectedModule==null && !workspace.keys().isEmpty())selectedModule=workspace.keys().getFirst();
            button(x,y+24,w,text("module_selection",selectedModule==null?"?":selectedModule.id()),()->{
                var keys=workspace.keys();if(keys.isEmpty())return;
                selectedModule=keys.get((keys.indexOf(selectedModule)+1)%keys.size()); itemPort="";resultPort="";init();
            }).visible=setupVisible;
        }
		Component configureLabel=kind==Kind.TEXT_OPERATION.ordinal()?Component.translatable("create_biotech.biocomputing.text_operation.choose",
            Component.translatable("create_biotech.biocomputing.text_operation.name."+textOperation.name().toLowerCase(Locale.ROOT)))
            :kind==Kind.SCALAR.ordinal()?Component.translatable("create_biotech.biocomputing.operation.choose",
			Component.translatable("create_biotech.biocomputing.operation.name."+scalarOperation.name().toLowerCase(Locale.ROOT)))
			:kind==Kind.BRANCH.ordinal()?Component.translatable("create_biotech.biocomputing.branch.title")
            :kind==Kind.FOLD.ordinal()?Component.translatable("create_biotech.biocomputing.fold.title")
            :kind==Kind.SORT.ordinal()?Component.translatable("create_biotech.biocomputing.sort.title"):text("configure");
        var configureButton=button(x, y + 46, w, configureLabel,
			kind==Kind.TEXT_OPERATION.ordinal()?this::openTextOperationChooser:kind==Kind.SCALAR.ordinal()?this::openOperationChooser:kind==Kind.BRANCH.ordinal()?this::openBranchChooser
                :kind==Kind.FOLD.ordinal()?this::openFoldChooser:kind==Kind.SORT.ordinal()?this::openSortChooser:this::configure);
		configureButton.active=kind>=0;configureButton.visible=setupVisible;
        int outputY=y+(compactModal()?0:70);
        outputName = addRenderableWidget(new EditBox(font, leftPos + x, topPos + outputY, w - 82, 18, text("output_name")));
        outputName.setMaxLength(256); outputName.setValue(outputText); outputName.setResponder(value -> outputText = value); outputName.setHint(text("output_name"));
        outputName.visible=bindingsVisible;
        button(x + w - 78, outputY-1, 78, text("bind_output"), () -> {
            try { draft.output(outputText, editing); changed(); } catch (IllegalArgumentException error) { notice = Component.literal(error.getMessage()); validation = notice; }
        }).visible=bindingsVisible;
        button(x, outputY+22, isModuleKind()&&!compactModal()?w-94:w, text("remove_output"), () -> { draft.removeOutput(outputText); changed(); }).visible=bindingsVisible;
        if(isModuleKind()) {
            var open=button(x+w-90,y+(compactModal()?116:92),90,text("open_subgraph"),()->switchGraph(selectedModule));
            open.active=selectedModule!=null && workspace.keys().contains(selectedModule);open.visible=setupVisible;
        }
        if(isLoopKind() && selectedModule!=null && workspace.keys().contains(selectedModule)) {
            var module=workspace.graph(selectedModule);
            itemPort=choose(module.inputs().keySet(),itemPort);resultPort=choose(module.outputs().keySet(),resultPort);
            button(x,y+(compactModal()?70:116),w,text("item_port",itemPort),()->{itemPort=cycle(module.inputs().keySet(),itemPort);init();}).visible=setupVisible;
            button(x,y+(compactModal()?94:140),w,text("result_port",resultPort),()->{resultPort=cycle(module.outputs().keySet(),resultPort);init();}).visible=setupVisible;
        }
        var ports = new ArrayList<>(draft.node(editing).ports());
        portPage = Math.min(portPage, Math.max(0, (ports.size() - 1) / portsPerPage()));
        for (int row = 0; row < portsPerPage() && bindingsVisible; row++) {
            int index = portPage * portsPerPage() + row; if (index >= ports.size()) break;
            String port = ports.get(index), source = draft.node(editing).connections().get(port);
            button(x, y + (compactModal()?48:164) + row * 22, w - 24, Component.literal(port + " ← " + (source == null ? "?" : source)), () -> {
                connectingTarget = editing; connectingPort = port; editing = null;
                notice = text("choose_source", connectingTarget + "." + connectingPort); init();
            });
            button(x + w - 20, y + (compactModal()?48:164) + row * 22, 20, Component.literal("×"), () -> { draft.disconnect(editing, port); changed(); init(); });
        }
        int bottom = my() + modalHeight() - 26;
        var previous=button(x, bottom, 28, Component.literal("<"), () -> { portPage--; init(); });previous.active=portPage>0;previous.visible=bindingsVisible;
        var next=button(x + 32, bottom, 28, Component.literal(">"), () -> { portPage++; init(); });next.active=(portPage+1)*portsPerPage()<ports.size();next.visible=bindingsVisible;
        if(compactModal())button(x+64,bottom,88,text(nodeBindings?"node_setup":"node_bindings"),()->{nodeBindings=!nodeBindings;init();});
        button(x + w - 78, bottom, 78, text("back"), () -> { editing = null; init(); });
    }
    private boolean isModuleKind() { return kind==Kind.CALL.ordinal() || isLoopKind(); }
    private boolean isLoopKind() { return kind==Kind.MAP.ordinal() || kind==Kind.FILTER.ordinal(); }
    private void openTextOperationChooser() {
        navigator.accept(new ComputerTextOperationScreen(menu,editorInventory,title,textOperation,operation->{
            textOperation=operation;
            var arguments=new LinkedHashMap<String,String>();operation.inputs().keySet().forEach(name->arguments.put(name,name));
            draft.configure(editing,new ComputationGraph.Text(operation,arguments));changed();init();
        },()->navigator.accept(this)));
    }
    private void openOperationChooser() {
        navigator.accept(new ComputerOperationScreen(menu,editorInventory,title,scalarOperation,this::selectScalarOperation,()->navigator.accept(this)));
    }
	private void openBranchChooser() {
		navigator.accept(new ComputerBranchScreen(menu,editorInventory,title,workspace.keys(),selectedWhenTrue,selectedWhenFalse,(whenTrue,whenFalse)->{
			var trueGraph=workspace.graph(whenTrue);var falseGraph=workspace.graph(whenFalse);
			var names=new LinkedHashSet<String>();names.addAll(trueGraph.inputs().keySet());names.addAll(falseGraph.inputs().keySet());
			var arguments=new LinkedHashMap<String,String>();names.forEach(name->arguments.put(name,name));
			draft.configure(editing,new ComputationGraph.Branch("condition",whenTrue,whenFalse,arguments));
			selectedWhenTrue=whenTrue;selectedWhenFalse=whenFalse;changed();navigator.accept(this);init();
		},key->{navigator.accept(this);switchGraph(key);},()->navigator.accept(this)));
	}
    private void openFoldChooser() {
        var definition = draft.node(editing).definition();
        var selected = definition instanceof ComputationGraph.Fold fold
            ? new ComputerFoldScreen.Selection(fold.module(), fold.itemPort(), fold.accumulatorPort(), fold.resultPort())
            : null;
        navigator.accept(new ComputerFoldScreen(menu, editorInventory, title, workspace, selected, selection -> {
            var captures = new LinkedHashMap<String, String>();
            workspace.graph(selection.module()).inputs().keySet().stream()
                .filter(name -> !name.equals(selection.item()) && !name.equals(selection.accumulator()))
                .forEach(name -> captures.put(name, name));
            draft.configure(editing, new ComputationGraph.Fold("source", "initial", selection.module(),
                selection.item(), selection.accumulator(), captures, selection.result()));
            changed();
            navigator.accept(this);
            init();
        }, key -> {
            navigator.accept(this);
            switchGraph(key);
        }, () -> navigator.accept(this)));
    }

    private void openSortChooser() {
        var definition = draft.node(editing).definition();
        var selection = definition instanceof ComputationGraph.Sort sort
            ? new ComputerSortScreen.Selection(sort.module(), sort.itemPort(), sort.resultPort(), sort.descending()) : null;
        navigator.accept(new ComputerSortScreen(menu, editorInventory, title, workspace, selection, selected -> {
            var captures = new LinkedHashMap<String, String>();
            workspace.graph(selected.module()).inputs().keySet().stream().filter(name -> !name.equals(selected.item()))
                .forEach(name -> captures.put(name, name));
            draft.configure(editing, new ComputationGraph.Sort("source", selected.module(), selected.item(), captures, selected.result(), selected.descending()));
            changed(); navigator.accept(this); init();
        }, key -> {
            navigator.accept(this); switchGraph(key);
        }, () -> navigator.accept(this)));
    }

    private void selectScalarOperation(ScalarOperation operation) {
        scalarOperation=Objects.requireNonNull(operation);
        var arguments=new LinkedHashMap<String,String>();operation.inputs().keySet().forEach(name->arguments.put(name,name));
        draft.configure(editing,new ComputationGraph.Scalar(operation,arguments));changed();init();
    }
    private static String choose(Set<String> names,String current) { return names.contains(current)?current:names.stream().findFirst().orElse(""); }
    private static String cycle(Set<String> names,String current) { var list=new ArrayList<>(names);return list.isEmpty()?"":list.get((list.indexOf(current)+1)%list.size()); }
    private void configure() {
        if (kind < 0) return;
        try {
            ComputationGraph.Node definition = switch (Kind.values()[kind]) {
                case INTEGER, DECIMAL, TEXT, BOOLEAN, FIELD, INDEX, INPUT, LIST -> NodeParameter.parse(Kind.values()[kind], parameterText);
                case ADD, LESS, EQUAL -> new ComputationGraph.Binary(switch (Kind.values()[kind]) {
                    case ADD -> ComputationGraph.BinaryOp.ADD_INTEGER; case LESS -> ComputationGraph.BinaryOp.LESS_INTEGER; default -> ComputationGraph.BinaryOp.EQUAL_INTEGER;
                }, "left", "right");
                case MEMORY -> {
                    if(activeGraph!=null)throw new IllegalArgumentException("Subgraphs are pure; pass state through a named input");
                    yield NodeParameter.parse(Kind.MEMORY, parameterText);
                }
                case JSON -> new ComputationGraph.Constant(EditorValueFormat.parse(EditorValueFormat.Kind.JSON,parameterText));
                case NBT -> new ComputationGraph.Constant(EditorValueFormat.parse(EditorValueFormat.Kind.NBT,parameterText));
                case CONCAT_LISTS -> new ComputationGraph.ConcatLists("left", "right");
                case DYNAMIC_INDEX -> new ComputationGraph.DynamicIndex("source", "index");
                case DYNAMIC_FIELD -> new ComputationGraph.DynamicField("source", "name");
                case HAS_FIELD -> new ComputationGraph.HasField("source", "name");
                case KEYS -> new ComputationGraph.Keys("source");
                case VALUE_EQUALS -> new ComputationGraph.ValueEquals("left", "right");
                case DISTINCT -> new ComputationGraph.Distinct("source");
                case LIST_REPLACE, LIST_INSERT, LIST_REMOVE, LIST_SLICE, LIST_APPEND -> {
                    var operation = Kind.values()[kind].listOperation();
                    var arguments = new LinkedHashMap<String, String>();
                    operation.inputs().keySet().forEach(name -> arguments.put(name, name));
                    yield new ComputationGraph.ListEdit(operation, arguments);
                }
                case CALL, MAP, FILTER -> {
                    if(selectedModule==null || !workspace.keys().contains(selectedModule))throw new IllegalArgumentException("Choose or create an internal graph first");
                    var module=workspace.graph(selectedModule);var arguments=new LinkedHashMap<String,String>();
                    module.inputs().keySet().forEach(name->arguments.put(name,"input"));
                    if(kind==Kind.CALL.ordinal())yield new ComputationGraph.Call(selectedModule,arguments);
                    itemPort=choose(module.inputs().keySet(),itemPort); resultPort=choose(module.outputs().keySet(),resultPort);
                    if(itemPort.isEmpty() || resultPort.isEmpty())throw new IllegalArgumentException("Internal graph needs an item input and a result output");
                    arguments.remove(itemPort);
                    yield new ComputationGraph.Iterate(kind==Kind.MAP.ordinal()?ComputationGraph.Iteration.MAP:ComputationGraph.Iteration.FILTER,"source",selectedModule,itemPort,arguments,resultPort);
                }
                case RECORD -> {
                    var fields = new LinkedHashMap<String, String>();
                    if (draft.node(editing).definition() instanceof ComputationGraph.MakeRecord record) fields.putAll(record.fields());
                    fields.put(parameterText, "value"); yield new ComputationGraph.MakeRecord(fields);
                }
                case SCALAR -> {
                    var arguments=new LinkedHashMap<String,String>();scalarOperation.inputs().keySet().forEach(name->arguments.put(name,name));
                    yield new ComputationGraph.Scalar(scalarOperation,arguments);
                }
				case TEXT_OPERATION, BRANCH, FOLD, SORT -> throw new IllegalStateException("Module chooser is required");
            };
            draft.configure(editing, definition); changed(); init();
        } catch (IllegalArgumentException | ComputationException error) { notice = text("invalid_parameter", String.valueOf(error.getMessage())); validation = notice; }
    }
    private static int kindOf(ComputationGraph.Node node) {
        if (node == null) return 0;
        return switch (node) {
            case ComputationGraph.Constant c -> switch (c.value()) {
                case DataValue.IntegerValue ignored -> Kind.INTEGER.ordinal(); case DataValue.DecimalValue ignored -> Kind.DECIMAL.ordinal();
                case DataValue.TextValue ignored -> Kind.TEXT.ordinal(); case DataValue.BooleanValue ignored -> Kind.BOOLEAN.ordinal();
                case DataValue.NbtValue ignored -> Kind.NBT.ordinal();
                case DataValue.ListValue ignored -> Kind.JSON.ordinal(); case DataValue.RecordValue ignored -> Kind.JSON.ordinal(); case DataValue.NullValue ignored -> Kind.JSON.ordinal(); default -> -1;
            };
            case ComputationGraph.Binary b -> switch (b.operation()) { case ADD_INTEGER -> Kind.ADD.ordinal(); case LESS_INTEGER -> Kind.LESS.ordinal(); case EQUAL_INTEGER -> Kind.EQUAL.ordinal(); };
			case ComputationGraph.Text ignored -> Kind.TEXT_OPERATION.ordinal();
			case ComputationGraph.Scalar ignored -> Kind.SCALAR.ordinal();
            case ComputationGraph.Field ignored -> Kind.FIELD.ordinal(); case ComputationGraph.MakeRecord ignored -> Kind.RECORD.ordinal();
            case ComputationGraph.DynamicField ignored -> Kind.DYNAMIC_FIELD.ordinal();
            case ComputationGraph.HasField ignored -> Kind.HAS_FIELD.ordinal();
            case ComputationGraph.Keys ignored -> Kind.KEYS.ordinal();
            case ComputationGraph.Index ignored -> Kind.INDEX.ordinal();
            case ComputationGraph.DynamicIndex ignored -> Kind.DYNAMIC_INDEX.ordinal();
            case ComputationGraph.ListPosition ignored -> -1; // Internal Scratch selector, not a graph UI entry.
            case ComputationGraph.RandomRange ignored -> -1; // Draws belong to the executing ISA task.
            case ComputationGraph.Timer ignored -> -1; // This reporter requires the ISA Computer.
            case ComputationGraph.Answer ignored -> -1;
            case ComputationGraph.ListSearch ignored -> -1; // Scratch reporter, never a graph runtime node.
            case ComputationGraph.ListEdit edit -> switch (edit.operation()) {
                case APPEND -> Kind.LIST_APPEND.ordinal();
                case REPLACE -> Kind.LIST_REPLACE.ordinal(); case INSERT -> Kind.LIST_INSERT.ordinal();
                case REMOVE -> Kind.LIST_REMOVE.ordinal(); case SLICE -> Kind.LIST_SLICE.ordinal();
            };
            case ComputationGraph.MakeList ignored -> Kind.LIST.ordinal();
            case ComputationGraph.ConcatLists ignored -> Kind.CONCAT_LISTS.ordinal();
            case ComputationGraph.Input ignored -> Kind.INPUT.ordinal();case ComputationGraph.ReadMemory ignored -> Kind.MEMORY.ordinal();
            case ComputationGraph.ProcedureValue ignored -> Kind.CALL.ordinal(); // Retained for inspection, not a new graph UI.
            case ComputationGraph.Call ignored -> Kind.CALL.ordinal();case ComputationGraph.Iterate loop -> loop.mode()==ComputationGraph.Iteration.MAP?Kind.MAP.ordinal():Kind.FILTER.ordinal();
			case ComputationGraph.Branch ignored -> Kind.BRANCH.ordinal();
            case ComputationGraph.Fold ignored -> Kind.FOLD.ordinal();
            case ComputationGraph.Sort ignored -> Kind.SORT.ordinal();
            case ComputationGraph.KeyDown ignored -> -1;
            case ComputationGraph.ValueEquals ignored -> Kind.VALUE_EQUALS.ordinal();
            case ComputationGraph.Distinct ignored -> Kind.DISTINCT.ordinal();
        };
    }
    private static String parameterOf(ComputationGraph.Node node) {
        if (node == null) return "0";
        if (NodeParameter.kind(node) != null) return NodeParameter.text(node);
        return switch (node) {
            case ComputationGraph.Constant c -> {try {yield EditorValueFormat.format(c.value());}catch(IllegalArgumentException error){yield "";}}
            case ComputationGraph.ProcedureValue call -> call.procedure().id();
            case ComputationGraph.Call call -> call.module().id();
            case ComputationGraph.Iterate loop -> loop.module().id();default -> "";
        };
    }
    private void changed() {
        portTypes = null;
        wireHover = null; wireHoverRejected = false;
        preview = null; validation = Component.empty();
        if (draft == null || menu.response().isEmpty()) return;
        try { var document = workspace.snapshot(); document.validate(menu.response().orElseThrow().hardware(), menu.response().orElseThrow().pins());
            preview = document.flow().map(body -> body.program(document.archive()).expressions()).orElse(document.archive())
                .compile(menu.response().orElseThrow().hardware()); }
        catch (ComputationException error) { validation = Component.literal(error.code() + " @ " + error.location() + ": " + error.getMessage()); }
        catch (IllegalStateException | IllegalArgumentException error) { validation = Component.literal(error.getMessage()); }
    }
    private void install() {
        if (draft == null || waiting) return;
        try {
            var document = workspace.snapshot();
            document.validate(menu.response().orElseThrow().hardware(), menu.response().orElseThrow().pins());
            menu.submit(document, resetMemory ? ComputerRuntime.MemoryPolicy.RESET : ComputerRuntime.MemoryPolicy.PRESERVE);
            if (execution != null) execution.invalidate();
            waiting = true; notice = text("installing"); cancelGesture(); init();
        } catch (ComputationException | IllegalArgumentException | IllegalStateException error) { notice = text("invalid_parameter", String.valueOf(error.getMessage())); }
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; menu.requestInitial(); }
        if (responseVersion != menu.responseVersion()) {
            responseVersion = menu.responseVersion();
            if (execution != null) execution.invalidate();
            menu.response().ifPresent(reply -> {
                waiting = false;
                notice = text("result." + reply.code());
                if (!reply.detail().isEmpty()) notice = notice.copy().append(": " + reply.detail());
                boolean initial = draft == null && reply.code().equals("ready");
                if (initial) {
                    try {
                        workspace = reply.archive().length == 0 ? new ProgramWorkspace() : new ProgramWorkspace(ProgramDocumentWire.decode(reply.archive(), reply.archiveLimits()));
                        if (reply.archive().length == 0) workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
                        draft = workspace.root();
                    }
                    catch (IllegalArgumentException error) { notice = text("invalid_parameter", error.getMessage()); }
                }
                changed(); init();
                // Existing event documents are not silently converted or stripped of effects.
                if (initial && workspace != null && workspace.flow().isPresent()) openBlocks();
            });
        }
        if (execution != null && !waiting && executionResponseVersion != menu.controlResponseVersion()) {
            executionResponseVersion = menu.controlResponseVersion();
            menu.controlResponse().ifPresent(reply -> {
                if (menu.response().isPresent() && reply.revision() < menu.response().orElseThrow().revision()) execution.invalidate();
                else execution.accept(reply, menu.status());
            });
        }
    }
    private ProgramDraft.Node hit(double x, double y) {
        if (draft == null) return null;
        var nodes = draft.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var n = nodes.get(i);
            if (x >= n.x() && x < n.x() + NODE_WIDTH && y >= n.y() && y < n.y() + ComputerGraphPorts.height(n)) return n;
        }
        return null;
    }
    private NodeTypeHints portTypes() {
        if (portTypes == null) portTypes = new NodeTypeHints(workspace, draft, activeGraph != null, preview);
        return portTypes;
    }
    private boolean connect(String target, String port, String source) {
        try {
            portTypes().check(target, port, source); draft.connect(target, port, source); changed();
            notice = text("result.ready"); return true;
        } catch (ComputationException error) {
            notice = text("invalid_parameter", error.code() + " @ " + error.location() + ": " + error.getMessage()); return false;
        }
    }
    private void beginInline(ProgramDraft.Node node) {
        inline = new ComputerInlineParameterEditor(font, node, this::applyInline, this::cancelInline);
        notice = text("configure"); init();
    }
    private boolean applyInline() {
        if (inline == null) return true;
        try {
            var node = draft.node(inline.node());
            draft.configure(node.id(), NodeParameter.parse(NodeParameter.kind(node.definition()), inline.value()));
            inline = null; notice = text("result.ready"); changed(); init(); return true;
        } catch (IllegalArgumentException | ComputationException error) {
            notice = text("invalid_parameter", String.valueOf(error.getMessage())); inline.error(notice); return false;
        }
    }
    private void cancelInline() { inline = null; notice = text("result.ready"); changed(); init(); }
    private void beginGesture(String node, double x, double y, int button) {
        if (node == null) interaction.beginPan(); else interaction.beginNode(node);
        gestureX = x; gestureY = y; gestureButton = button;
    }
    private void cancelGesture() { interaction.cancel(); gestureButton = -1; }
    private boolean dragGesture(double x, double y, int button) {
        if (gestureButton != button) return false;
        boolean handled = interaction.drag(x - gestureX, y - gestureY, draft::moveBy);
        if (handled) { gestureX = x; gestureY = y; }
        return handled;
    }
    private CanvasInteraction.Release<String> releaseGesture(double x, double y, int button) {
        if (gestureButton != button) return new CanvasInteraction.Release<>(false, null);
        // Release is authoritative even when the platform coalesces intermediate motion callbacks.
        dragGesture(x, y, button);
        gestureButton = -1;
        return interaction.release();
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        // Installed-program controls must not apply or discard an unfinished draft parameter.
        if (executionVisible() && execution.contains(x, y)) return dispatchWidgetClick(x, y, button);
        if (inline != null) {
            if (button == 2 && inCanvas(x, y)) { beginGesture(null, x, y, button); return true; }
            double worldX = viewport.worldX(x - canvasX()), worldY = viewport.worldY(y - canvasY());
            var widget = inCanvas(x, y) ? inline.hit(worldX, worldY) : null;
            if (widget != null) {
                var session = inline;
                if (widget.mouseClicked(worldX, worldY, button) && inline == session) {
                    setFocused(session.field()); setDragging(button == 0);
                }
                return true;
            }
            if (!applyInline()) return true;
        }
        if (paletteOpen) {
            if (button == 0 && (palettePressed = paletteHit(x, y)) != null) {
                paletteDragged = false; setFocused(null); return true;
            }
            if (inCanvas(x, y)) { if (button == 0 || button == 2) beginGesture(null, x, y, button); return true; }
            dispatchWidgetClick(x, y, button); return true;
        }
        if (editing != null || panel != null) { dispatchWidgetClick(x, y, button); return true; }
        if (!inCanvas(x, y)) return dispatchWidgetClick(x, y, button);
        if (waiting || draft == null) return true;
        if (button == 2) { beginGesture(null, x, y, button); return true; }
        var node = hit(viewport.worldX(x - canvasX()), viewport.worldY(y - canvasY()));
        if (connectingTarget != null) {
            String target = connectingTarget;
            if (button == 0 && node != null && !connect(target, connectingPort, node.id())) return true;
            connectingTarget = connectingPort = null; openNode(target); return true;
        }
        var socket = ComputerGraphPorts.hit(draft, viewport.worldX(x - canvasX()), viewport.worldY(y - canvasY()));
        if (socket != null) {
            if (button == 1) {
                wireFrom = wirePress = null;
                if (!socket.output()) { draft.disconnect(socket.node(), socket.input()); changed(); }
            } else if (button == 0) {
                if (wireFrom == null || wireFrom.output() == socket.output()) {
                    wireFrom = socket; wireHover = null; wireHoverRejected = false;
                }
                wirePress = socket; cancelGesture();
                notice = socket.output() ? Component.literal(socket.label() + " → ?") : text("choose_source", socket.label());
            }
            return true;
        }
        if (wireFrom != null) { wireFrom = wirePress = null; notice = text("result.ready"); return true; }
        if (button == 1) { if (node != null) { draft.remove(node.id()); changed(); } return true; }
        if (button == 0 && node != null && NodeParameter.kind(node.definition()) != null
            && viewport.worldY(y - canvasY()) >= node.y() + 17 && viewport.worldY(y - canvasY()) < node.y() + 36) {
            beginInline(node); return true;
        }
        if (button == 0) beginGesture(node == null ? null : node.id(), x, y, button);
        return true;
    }
    private boolean dispatchWidgetClick(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        // Vanilla sets focus after invoking the callback, which may already have rebuilt the widgets.
        if (getFocused() != null && !children().contains(getFocused())) setFocused(paletteOpen ? paletteSearch : null);
        return handled;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (wirePress != null && button == 0) return true;
        if (inline != null) {
            if (!dragGesture(x, y, button) && isDragging())
                inline.field().mouseDragged(viewport.worldX(x - canvasX()), viewport.worldY(y - canvasY()), button,
                    dx / viewport.scale(), dy / viewport.scale());
            return true;
        }
        if (paletteOpen) {
            if (palettePressed != null) { paletteDragged = true; return true; }
            dragGesture(x, y, button); return true;
        }
        if (editing != null || panel != null) { super.mouseDragged(x, y, button, dx, dy); return true; }
        if (waiting || draft == null) return true;
        return dragGesture(x, y, button) || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (wirePress != null && button == 0) {
            var end = inCanvas(x, y) ? ComputerGraphPorts.hit(draft, viewport.worldX(x - canvasX()), viewport.worldY(y - canvasY())) : null;
            if (end != null && end.output() != wireFrom.output()) {
                var source = wireFrom.output() ? wireFrom : end; var target = wireFrom.output() ? end : wireFrom;
                if (connect(target.node(), target.input(), source.node())) wireFrom = null;
            } else if (!Objects.equals(end, wirePress)) { wireFrom = null; notice = text("result.ready"); }
            wirePress = null; return true;
        }
        if (inline != null) { releaseGesture(x, y, button); setDragging(false); return true; }
        if (paletteOpen) {
            if (button == 0 && palettePressed != null) {
                var choice = palettePressed; palettePressed = null;
                // Palette and canvas are disjoint: this release itself proves a drag,
                // even if the platform coalesced intermediate mouse-move callbacks.
                if (inCanvas(x, y) && editing == null)
                    selectPaletteKind(choice, viewport.worldX(x - canvasX()), viewport.worldY(y - canvasY()));
                // Motion within a row is still a click; only the release target decides.
                else if (choice == paletteHit(x, y)) selectPaletteKind(choice);
                paletteDragged = false; return true;
            }
            releaseGesture(x, y, button); super.mouseReleased(x, y, button); return true;
        }
        if (editing != null || panel != null) { super.mouseReleased(x, y, button); return true; }
        var release = releaseGesture(x, y, button);
        if (release.clickedNode() != null && !waiting) openNode(release.clickedNode());
        return release.active() || super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (paletteOpen) {
            if (palettePressed != null) return true;
            if (inCanvas(x, y)) viewport.zoomAt(x - canvasX(), y - canvasY(), (int)Math.signum(vertical));
            else if (x >= leftPos + 8 && x < leftPos + 8 + paletteWidth() && y >= topPos + 79 && y < topPos + imageHeight - 30) {
                palettePage -= (int)Math.signum(vertical); refreshPaletteRows();
            }
            return true;
        }
        if (editing != null || panel != null) { super.mouseScrolled(x, y, horizontal, vertical); return true; }
        if (inCanvas(x, y)) { viewport.zoomAt(x - canvasX(), y - canvasY(), (int)Math.signum(vertical)); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256 && wireFrom != null) { wireFrom = wirePress = null; notice = text("result.ready"); return true; }
        if (inline != null) {
            if (key == 256) cancelInline();
            else if (key == 257 || key == 335) applyInline();
            else inline.field().keyPressed(key, scan, modifiers);
            return true;
        }
        if (paletteOpen) {
            if (key == 256) closePalette();
            else dispatchModalKey(key, scan, modifiers);
            return true;
        }
        if (key == 256 && (editing != null || connectingTarget != null || panel != null)) { editing = connectingTarget = connectingPort = panel = null; init(); return true; }
        if (editing != null || panel != null) { dispatchModalKey(key, scan, modifiers); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    private void dispatchModalKey(int key, int scan, int modifiers) {
        // Follow the terminal's vanilla focus path without forwarding container/inventory hotkeys.
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            var event = new FocusNavigationEvent.TabNavigation((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0);
            var path = nextFocusPath(event);
            if (path == null) { clearFocus(); path = nextFocusPath(event); }
            if (path != null) changeFocus(path);
        } else if (getFocused() instanceof CreateTextButton control
            && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)) {
            // Create's pointer callbacks do not implement keyboard activation.
            if (children().contains(control) && control.active && control.visible)
                control.runCallback(control.getX(), control.getY());
        } else if (getFocused() != null && children().contains(getFocused())) {
            getFocused().keyPressed(key, scan, modifiers);
        }
        // A callback can rebuild the modal or replace the directory rows.
        if (getFocused() != null && !children().contains(getFocused())) setFocused(paletteOpen ? paletteSearch : null);
    }
    @Override protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);
        if ((paletteOpen || editing != null || panel != null) && getFocused() instanceof CreateTextButton control
            && children().contains(control) && control.active && control.visible)
            graphics.renderOutline(control.getX(), control.getY(), control.getWidth(), control.getHeight(), 0xffaaccee);
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        g.drawString(font, title, leftPos + 10, topPos + 10, 0xffeeeeee, false);
        String graphTitle=activeGraph!=null?activeGraph.id():activeEntry!=null?activeEntry:text("root_graph").getString();
        g.drawString(font,font.plainSubstrByWidth(graphTitle,imageWidth/2),leftPos+imageWidth/2,topPos+10,0xffaaccee,false);
        g.enableScissor(canvasX(), canvasY(), canvasX() + canvasWidth(), canvasY() + canvasHeight());
        g.pose().pushPose(); g.pose().translate(canvasX() + viewport.screenX(0), canvasY() + viewport.screenY(0), 0);
        float scale = (float)viewport.scale(); g.pose().scale(scale, scale, 1);
        if (draft != null) {
            for (var node : draft.nodes()) for (var entry : node.connections().entrySet()) {
                ComputerGraphPorts.wire(g, ComputerGraphPorts.position(draft, new ComputerGraphPorts.Socket(entry.getValue(), null)),
                    ComputerGraphPorts.position(draft, new ComputerGraphPorts.Socket(node.id(), entry.getKey())),
                    portTypes().mismatched(node.id(), entry.getKey(), entry.getValue()) ? 0xffff7777 : ComputerGraphPorts.color(portTypes().output(entry.getValue())));
            }
            for (var node : draft.nodes()) {
                int x = (int)node.x(), y = (int)node.y(), k = kindOf(node.definition());
                boolean inlineEditing = inline != null && inline.node().equals(node.id());
                PatternGuiSkin.blit(g, node.id().equals(editing) || inlineEditing ? PatternGuiSkin.SLOT_SELECTED : PatternGuiSkin.SLOT, x, y, NODE_WIDTH, ComputerGraphPorts.height(node));
                Component label = node.definition() instanceof ComputationGraph.Text text
                    ? Component.translatable("create_biotech.biocomputing.text_operation.name."+text.operation().name().toLowerCase(Locale.ROOT))
                    : node.definition() instanceof ComputationGraph.Scalar scalar
                    ? Component.translatable("create_biotech.biocomputing.operation.name."+scalar.operation().name().toLowerCase(Locale.ROOT))
                    : node.definition() == null ? text("unconfigured") : k < 0 ? Component.literal(node.definition().getClass().getSimpleName()) : text("kind." + Kind.values()[k].name().toLowerCase(Locale.ROOT));
                g.drawString(font, font.plainSubstrByWidth(node.id() + " · " + label.getString(), NODE_WIDTH - 8), x + 4, y + 5, 0xffeeeeee, false);
                String parameters = parameterOf(node.definition());
                if (parameters.isEmpty()) parameters = String.join(", ", node.ports());
                if (!inlineEditing) {
                    if (NodeParameter.kind(node.definition()) != null) g.fill(x + 3, y + 17, x + NODE_WIDTH - 3, y + 35, 0x44000000);
                    g.drawString(font, font.plainSubstrByWidth(parameters, NODE_WIDTH - 8), x + 4, y + 20, 0xffcccccc, false);
                }
                String outputs = String.join(", ", draft.outputs().entrySet().stream().filter(e -> e.getValue().equals(node.id())).map(Map.Entry::getKey).toList());
                String type = ComputerGraphPorts.typeLabel(portTypes().output(node.id()));
                g.drawString(font, font.plainSubstrByWidth(outputs.isEmpty() ? type : "→ " + outputs, NODE_WIDTH - 8), x + 4, y + (inlineEditing ? 40 : 35), 0xffaaccee, false);
                ComputerGraphPorts.render(g, font, draft, node, portTypes());
            }
        }
        if (inline != null) inline.render(g, (int)viewport.worldX(mouseX - canvasX()), (int)viewport.worldY(mouseY - canvasY()), partial);
        var hovered = !paletteOpen && editing == null && panel == null && inCanvas(mouseX, mouseY)
            ? ComputerGraphPorts.hit(draft, viewport.worldX(mouseX - canvasX()), viewport.worldY(mouseY - canvasY())) : null;
        if (wireFrom != null) {
            if (!Objects.equals(hovered, wireHover)) {
                wireHover = hovered; wireHoverRejected = false;
                if (hovered != null && hovered.output() != wireFrom.output()) {
                    var source = wireFrom.output() ? wireFrom : hovered; var target = wireFrom.output() ? hovered : wireFrom;
                    try { portTypes().check(target.node(), target.input(), source.node()); }
                    catch (ComputationException error) { wireHoverRejected = true; }
                }
            }
            ComputerGraphPorts.wire(g, ComputerGraphPorts.position(draft, wireFrom),
                new ComputerGraphPorts.Point(viewport.worldX(mouseX - canvasX()), viewport.worldY(mouseY - canvasY())), wireHoverRejected ? 0xffff7777 : 0xffffcc66);
        }
        g.pose().popPose(); g.disableScissor();
        if (executionVisible()) execution.render(g, mouseX, mouseY);
        if (hovered != null && inline == null)
            g.renderTooltip(font, font.split(ComputerGraphPorts.tooltip(draft, hovered, portTypes()), Math.min(260, imageWidth - 16)), mouseX, mouseY);
        if (inline != null && inCanvas(mouseX, mouseY)) {
            var tooltip = inline.tooltip(viewport.worldX(mouseX - canvasX()), viewport.worldY(mouseY - canvasY()));
            if (tooltip != null) g.renderTooltip(font, font.split(tooltip, Math.min(260, imageWidth - 16)), mouseX, mouseY);
        }
        if (paletteOpen) {
            if (ComputerNodePalette.search(paletteCategory, paletteQuery).isEmpty())
                g.drawString(font, font.plainSubstrByWidth(ComputerNodePalette.text("empty").getString(), paletteWidth()), leftPos + 8, topPos + 81, 0xffcccccc, false);
            if (palettePressed != null && paletteDragged && editing == null && inCanvas(mouseX, mouseY)) {
                PatternGuiSkin.blit(g, PatternGuiSkin.SLOT_SELECTED, mouseX, mouseY, NODE_WIDTH, NODE_HEIGHT);
                g.drawString(font, font.plainSubstrByWidth(palettePressed.label().getString(), NODE_WIDTH - 8), mouseX + 4, mouseY + 5, 0xffeeeeee, false);
            }
        }
        Component status = paletteOpen ? ComputerNodePalette.text("hint") : notice;
        int statusX = paletteOpen ? canvasX() : leftPos + 8, statusWidth = paletteOpen ? canvasWidth() : imageWidth - 16;
        g.drawString(font, font.plainSubstrByWidth(status.getString(), statusWidth), statusX, topPos + imageHeight - 59, 0xffeeeecc, false);
        Component resources = resourceStatus();
        g.drawString(font, font.plainSubstrByWidth(resources.getString(), statusWidth), statusX, topPos + imageHeight - 45, 0xffaaccdd, false);
        if (!paletteOpen && (editing != null || panel != null)) {
            g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0x66000000);
            PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos + mx(), topPos + my(), modalWidth(), modalHeight());
            g.drawString(font, panel==null?text("node", editing):text(panel), leftPos + mx() + 10, topPos + my() + 9, 0xffeeeeee, false);
            if("declarations".equals(panel) && memoryDeclarations && declarationText!=null && !declarationText.isBlank())
                g.drawString(font,font.plainSubstrByWidth(text("cell_references",String.join(",",draft.readers(declarationText)),String.join(",",draft.writers(declarationText))).getString(),modalWidth()-20),leftPos+mx()+10,topPos+my()+modalHeight()-54,0xffaaccdd,false);
            // The modal covers the canvas footer; keep its validation visible above the Back row.
            g.drawString(font, font.plainSubstrByWidth(validation.getString(), modalWidth() - 20),
                leftPos + mx() + 10, topPos + my() + modalHeight() - 42, 0xffffccaa, false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) { }
    /** Switching to the debugger preserves this menu and its unsaved workspace. */
    @Override public void removed() { }
    Component resourceStatus() {
        if (preview == null) return validation;
        var usage = preview.usage(); var limits = preview.limits();
        var document = workspace.snapshot();
        var executable = document.flow().map(body -> body.program(document.archive()));
        long programUnits = usage.programUnits() + executable.map(program -> program.steps().size()).orElse(0);
        long inputConstants = executable.map(program -> com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.inputConstantUnits(program.steps())).orElse(0L);
        return text("resources", programUnits, limits.programUnits(), usage.constantUnits() + usage.initialMemoryUnits() + inputConstants, limits.memoryUnits(), limits.workPerSlice());
    }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.editor." + key, args); }
}
