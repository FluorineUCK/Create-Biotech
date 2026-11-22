package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A Scratch-style definition/signature dialog; edits only the caller-owned draft on explicit apply. */
public final class ComputerFlowProcedureScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final class Row {
        String name, local, sample = "0";
        ProcedureSignatureTypes.Choice kind = ProcedureSignatureTypes.Choice.INTEGER;
        DataType retained;
        Row(String name, DataType type, String local) {
            this.name = name; this.retained = type; this.local = local;
            kind = ProcedureSignatureTypes.choice(type);
            sample = kind.literal()?defaultSample(EditorValueFormat.Kind.valueOf(kind.name())):"";
        }
        DataType type() { return retained != null ? retained : !kind.literal()?ProcedureSignatureTypes.fixed(kind):DataType.typeOf(EditorValueFormat.parse(EditorValueFormat.Kind.valueOf(kind.name()),sample)); }
        void cycle() {kind=kind.next();sample=kind.literal()?defaultSample(EditorValueFormat.Kind.valueOf(kind.name())):"";retained=null;}
        void sample(String value){if(kind.literal()){sample=value;retained=null;}}
        String hint(){if(kind.name().startsWith("REF_"))return "reference_hint";return kind==ProcedureSignatureTypes.Choice.RECIPE||kind==ProcedureSignatureTypes.Choice.OPTIONAL_RECIPE||kind==ProcedureSignatureTypes.Choice.RECIPE_CHECK?"recipe_signature_hint":"procedure_fixed_signature_hint";}
    }
    private static final class LabelRow {
        int before;
        String text;
        LabelRow(int before, String text) { this.before = before; this.text = text; }
    }
    private record TypeHintTarget(Row row, Component help) {}
    private final Map<net.minecraft.client.gui.components.AbstractWidget, TypeHintTarget> typeHints = new LinkedHashMap<>();
    private final List<Row> arguments = new ArrayList<>(), returns = new ArrayList<>();
    private final BiConsumer<FlowProgram.Step, List<ScratchProcedureLabel>> apply;
    private final boolean editableLabels;
    private boolean labelsView;
    private final List<LabelRow> labels = new ArrayList<>();
    private final Runnable close;
    private final String source;
    private final Map<String,ProgramDefinition.Cell> linkedCells;
    private final boolean call, task, join;
    private final Optional<FlowProgram.InvokeOperands> operands;
    private String handle, outcome;
    private String name, revision;
    private boolean output;
    private int page;
    private int previewOffset;
    private double previewGrab = -1;
    private Component notice;
    private final Set<String> forbidInside = new LinkedHashSet<>(), forbidContexts = new LinkedHashSet<>();
    private final List<FlowProgram.Procedure> definitions;
    private boolean rulesView, contextRules, assemblyView, assemblyEnabled;
    private String assemblyText="REG one INTEGER\nCONST one 1\nADD_INTEGER value value one\nRET";
    private int rulePage;
    ComputerFlowProcedureScreen(ComputerProgramMenu menu, Inventory inventory, Component title, String source,
            FlowProgram.Procedure definition, FlowProgram.Step invocation, Consumer<FlowProgram.Step> apply, Runnable close) {
        this(menu, inventory, title, source, definition, invocation, apply, close, List.of());
    }
    ComputerFlowProcedureScreen(ComputerProgramMenu menu, Inventory inventory, Component title, String source,
            FlowProgram.Procedure definition, FlowProgram.Step invocation, Consumer<FlowProgram.Step> apply, Runnable close, List<FlowProgram.Procedure> definitions) {
        this(menu,inventory,title,source,definition,invocation,apply,close,definitions,Map.of());
    }
    ComputerFlowProcedureScreen(ComputerProgramMenu menu,Inventory inventory,Component title,String source,
            FlowProgram.Procedure definition,FlowProgram.Step invocation,Consumer<FlowProgram.Step> apply,Runnable close,List<FlowProgram.Procedure> definitions,Map<String,ProgramDefinition.Cell> cells) {
        this(menu, inventory, title, source, definition, invocation, (step, labels) -> apply.accept(step), close, definitions, cells, false, List.of());
    }
    ComputerFlowProcedureScreen(ComputerProgramMenu menu, Inventory inventory, Component title, String source,
            FlowProgram.Procedure definition, FlowProgram.Step invocation, BiConsumer<FlowProgram.Step,List<ScratchProcedureLabel>> apply,
            Runnable close, List<FlowProgram.Procedure> definitions, Map<String,ProgramDefinition.Cell> cells, boolean editableLabels,
            List<ScratchProcedureLabel> existingLabels) {
        super(menu,inventory,title);
        this.editableLabels = editableLabels;
        existingLabels.forEach(label -> labels.add(new LabelRow(label.before(), label.text())));
        this.linkedCells=Map.copyOf(cells); this.source=source; this.apply=apply; this.close=close; call=invocation!=null; task=invocation instanceof FlowProgram.TaskStart; join=invocation instanceof FlowProgram.TaskJoin;
        operands = invocation instanceof FlowProgram.Invoke sync ? sync.operands() : Optional.empty();
        handle=join?((FlowProgram.TaskJoin)invocation).handle():task?((FlowProgram.TaskStart)invocation).handle():"task_handle";
        outcome=join?((FlowProgram.TaskJoin)invocation).outcome():"task_result";
        var bindings=invocation instanceof FlowProgram.Invoke sync?sync.bindings():task?new FlowCallBindings(((FlowProgram.TaskStart)invocation).arguments(),Map.of()):null;
        this.definitions = List.copyOf(definitions);
        name=definition==null?"procedure":definition.key().id(); revision=definition==null?"1":Long.toString(definition.key().revision());
        if (definition==null) { arguments.add(new Row("value",DataType.Scalar.INTEGER,"")); returns.add(new Row("result",DataType.Scalar.INTEGER,"value")); }
        else if (!join) {
            definition.assembly().ifPresent(value->{assemblyEnabled=true;assemblyText=value.text();});
            forbidInside.addAll(definition.rules().forbidInside()); forbidContexts.addAll(definition.rules().forbidContexts());
            definition.inputs().forEach((port,type)->arguments.add(new Row(port,type,call?operands.isPresent()?port:bindings.arguments().getOrDefault(port,""):"")));
            definition.outputs().forEach((port,type)->returns.add(new Row(port,type,call?bindings.returns().getOrDefault(port,""):definition.returns().get(port))));
        }
        notice=text(join?"task_join_hint":task?"task_start_hint":call?"procedure_call_hint":"procedure_signature_hint");
    }
    @Override protected void init() {
        setWindowSize(Math.min(540,width-12),Math.min(340,height-12)); super.init(); clearWidgets(); typeHints.clear(); setFocused(null); previewGrab = -1;
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.ASSEMBLY));
        int nameY = previewVisible() ? 68 : 35;
        field(8,nameY,imageWidth-78,"procedure_name",name,v->name=v).setEditable(!call);
        field(imageWidth-64,nameY,56,"procedure_revision",revision,v->revision=v).setEditable(!call);
        int half=(imageWidth-20)/2;
        if (join) {
            field(8,104,imageWidth-16,"task_handle_input",handle,v->handle=v);
            field(8,145,imageWidth-16,"task_outcome",outcome,v->outcome=v);
            button(8,imageHeight-27,half,"procedure_apply",this::commit);
            button(12+half,imageHeight-27,half,"cancel",this::onClose); return;
        }
        int tab = call ? half : editableLabels ? (imageWidth - 32) / 5 : (imageWidth - 28) / 4;
        int tabY = previewVisible() ? 94 : 63;
        button(8,tabY,tab,output || rulesView || labelsView || assemblyView?"procedure_inputs":"procedure_inputs_selected",()->{labelsView=false;rulesView=false;assemblyView=false;output=false;page=0;init();});
        button(12+tab,tabY,tab,task?"task_handle":output && !rulesView && !labelsView && !assemblyView?"procedure_outputs_selected":"procedure_outputs",()->{labelsView=false;rulesView=false;assemblyView=false;output=true;page=0;init();});
        if (!call) button(16+2*tab,tabY,tab,"procedure_rules",()->{labelsView=false;rulesView=true;assemblyView=false;rulePage=0;init();});
        if(!call)button(20+3*tab,tabY,tab,"procedure_implementation",()->{labelsView=false;assemblyView=true;rulesView=false;init();});
        if (!call && editableLabels) button(24+4*tab,tabY,tab,"procedure_labels",()->{
            rulesView=false; assemblyView=false; labelsView=true; output=false; page=0; init();
        }).active = !labelsView;
        if (labelsView) {
            labelEditor();
            button(8,imageHeight-27,half,"procedure_apply",this::commit);
            button(12+half,imageHeight-27,half,"cancel",this::onClose);
            return;
        }
        if(assemblyView) {
            var box=addRenderableWidget(new MultiLineEditBox(font,leftPos+8,topPos+104,imageWidth-16,Math.max(36,imageHeight-188),text("assembly_source"),text("assembly_source")));
            box.setCharacterLimit(ProgramEditorWire.MAX_BYTES+1);box.setValue(assemblyText);box.setValueListener(value->assemblyText=value);
            button(8,imageHeight-71,half,assemblyEnabled?"implementation_assembly":"implementation_scratch",()->{
                assemblyEnabled=!assemblyEnabled;init();});
            var syntax = text("assembly_instruction_sets_hint").copy().append("\n").append(text("assembly_syntax_hint"))
                .append("\n").append(text("assembly_random_range_hint"))
                .append("\n").append(text("assembly_mod_hint"))
                .append("\n").append(text("assembly_mathop_hint"))
                .append("\n").append(text("assembly_text_letter_hint")).append("\n").append(text("assembly_text_contains_hint")).append("\n").append(text("assembly_edge_hint")).append("\n").append(text("assembly_sample_edge_hint"));
            var help=button(12+half,imageHeight-71,half,"assembly_syntax",()->notice=syntax);
            help.setTooltip(Tooltip.create(syntax));
            notice=text("assembly_edit_hint");
            button(8,imageHeight-27,half,"procedure_apply",this::commit);
            button(12+half,imageHeight-27,half,"cancel",this::onClose);return;
        }
        if (rulesView) {
            ruleEditor();
            button(8,imageHeight-27,half,"procedure_apply",this::commit);
            button(12+half,imageHeight-27,half,"cancel",this::onClose);
            return;
        }
        if (task && output) {
            field(8,104,imageWidth-16,"task_handle",handle,v->handle=v);
            button(8,imageHeight-27,half,"procedure_apply",this::commit);
            button(12+half,imageHeight-27,half,"cancel",this::onClose); return;
        }
        var rows=rows(); int count=pageSize(); page=Math.clamp(page,0,Math.max(0,(rows.size()-1)/count));
        for(int index=page*count;index<rows.size()&&index<(page+1)*count;index++) {
            var row=rows.get(index); int y=rowTop()+(index-page*count)*rowHeight(); int typeWidth=94;
            var nameField=field(8,y,imageWidth-typeWidth-44,"procedure_field_name",row.name,v->row.name=v);nameField.setEditable(!call);typeHint(nameField,row,text("procedure_field_name"));
            var type=button(imageWidth-typeWidth-32,y,typeWidth,"procedure_type_"+row.kind.name().toLowerCase(Locale.ROOT),()->{row.cycle();init();});
            type.active=!call; typeHint(type,row,row.kind.literal()?Component.literal(row.retained==null?row.kind.name():ComputerGraphPorts.typeLabel(row.retained)):Component.literal(ComputerGraphPorts.typeLabel(row.type())).append("\n").append(text(row.hint())));
            var remove=button(imageWidth-28,y,20,"procedure_remove_field",()->{removeRow(row);init();}); remove.active=!call;
            if(call) {
                var binding = field(8,y+21,imageWidth-16,"procedure_binding",row.local,v->row.local=v);
                binding.setEditable(output || operands.isEmpty());
                typeHint(binding,row,text("procedure_binding"));
            }
            else {
                var sample=field(8,y+23,output?half:imageWidth-16,"procedure_type_sample",row.sample,row::sample);
                sample.active=row.kind.literal();sample.setEditable(row.kind.literal());
                typeHint(sample,row,text(row.kind.literal()?"procedure_type_sample_hint":row.hint()));
                if(output) field(12+half,y+23,half,"procedure_return_local",row.local,v->row.local=v);
            }
        }
        int columns = !call && !output ? 5 : 3;
        int cell = (imageWidth - 16 - (columns - 1) * 4) / columns, y = pagerY();
        button(8,y,cell,"previous",()->{page--;init();}).active=page>0;
        button(8+cell+4,y,cell,"procedure_add_field",()->addRow(output?"result":"value", DataType.Scalar.INTEGER)).active=!call;
        if (columns == 5) {
            button(8+2*(cell+4),y,cell,"procedure_add_text",()->addRow("text",DataType.Scalar.TEXT));
            button(8+3*(cell+4),y,cell,"procedure_add_boolean",()->addRow("condition",DataType.Scalar.BOOLEAN));
        }
        button(8+(columns-1)*(cell+4),y,cell,"next",()->{page++;init();}).active=(page+1)*count<rows.size();
        button(8,imageHeight-27,half,"procedure_apply",this::commit);
        button(12+half,imageHeight-27,half,"cancel",this::onClose);
    }
    private List<Row> rows(){return output?returns:arguments;}
    private int rowHeight(){return call?44:52;}
    private boolean previewVisible() { return !call && !rulesView && !assemblyView && imageHeight >= 228; }
    private int rowTop() { return previewVisible() ? 120 : 104; }
    private int pagerY() { return imageHeight - (previewVisible() ? 64 : 71); }
    private int pageSize() { return Math.max(1,(pagerY()-rowTop()-6)/rowHeight()); }

    private void addRow(String prefix, DataType type) {
        int n = 1;
        var names = new HashSet<String>();
        rows().forEach(row -> names.add(row.name));
        while (names.contains(prefix+n)) n++;
        rows().add(new Row(prefix+n,type,output?"value":""));
        page=(rows().size()-1)/pageSize();
        if (!output) previewOffset = Integer.MAX_VALUE;
        init();
    }

    ScratchSignaturePreview signaturePreview() {
        var parameters = arguments.stream().map(row -> new ScratchSignaturePreview.Parameter(
            row.name,row.kind==ProcedureSignatureTypes.Choice.BOOLEAN)).toList();
        return ScratchSignaturePreview.layout(font, name+" @ "+revision, parameters, labelParts());
    }
    private List<ScratchProcedureLabel> labelParts() {
        return labels.stream().filter(row -> !row.text.isBlank())
            .map(row -> new ScratchProcedureLabel(row.before, row.text)).toList();
    }
    private void removeRow(Row row) {
        if (!output) {
            int index = arguments.indexOf(row);
            // Boundaries on either side now coincide. Preserve their on-screen order.
            labels.sort(Comparator.comparingInt(label -> label.before));
            for (var label : labels) if (label.before > index) label.before--;
        }
        rows().remove(row);
    }

    private int labelPageSize() { return Math.max(1,(pagerY()-rowTop()-4)/24); }
    private void labelEditor() {
        int count = labelPageSize();
        page = Math.clamp(page, 0, Math.max(0, (labels.size()-1)/count));
        int positionWidth = (imageWidth-52)/3;
        for (int index=page*count;index<labels.size() && index<(page+1)*count;index++) {
            var part = labels.get(index);
            int y = rowTop() + (index-page*count)*24;
            var position = button(8,y,positionWidth,"procedure_label_position",()-> {
                part.before = (part.before + 1) % (arguments.size() + 1);
                previewOffset = part.before == arguments.size() ? Integer.MAX_VALUE : 0;
                init();
            });
            Component label = text("procedure_label_position", part.before == arguments.size() ? text("procedure_label_end")
                : text("procedure_label_before", arguments.get(part.before).name));
            position.setMessage(label);
            position.setTooltip(Tooltip.create(label));
            field(12+positionWidth,y,imageWidth-positionWidth-44,"procedure_label_text",part.text,v->part.text=v)
                .setMaxLength(ScratchProcedureLabel.MAX_CHARS);
            button(imageWidth-28,y,20,"procedure_remove_label",()->{labels.remove(part);init();});
        }
        int third = (imageWidth-24)/3, y = pagerY();
        button(8,y,third,"previous",()->{page--;init();}).active=page>0;
        button(12+third,y,third,"procedure_add_label",()->{
            labels.add(new LabelRow(arguments.size(), text("procedure_label_default").getString()));
            page=(labels.size()-1)/count; previewOffset=Integer.MAX_VALUE; init();
        }).active = labels.size() < ScratchProcedureLabel.MAX_PARTS;
        button(16+2*third,y,third,"next",()->{page++;init();}).active=(page+1)*count<labels.size();
        notice=text("procedure_label_hint");
    }

    private int previewOverflow(ScratchSignaturePreview preview) {
        return Math.max(0, preview.width() - (imageWidth-16));
    }

    private record PreviewTrack(int x, int y, int width, int thumbX, int thumbWidth, int overflow) {
        private static final int HEIGHT = 5;
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + HEIGHT;
        }
        void draw(GuiGraphics g, boolean dragging) {
            g.fill(x, y, x + width, y + HEIGHT, 0xff6e6e6e);
            g.fill(thumbX, y, thumbX + thumbWidth, y + HEIGHT, dragging ? 0xffffddaa : 0xffcccccc);
        }
    }

    private PreviewTrack previewTrack(int overflow) {
        if (overflow <= 0 || imageWidth <= 17) return null;
        int x = leftPos + 8;
        int width = imageWidth - 16;
        int thumbWidth = Math.clamp((int)((long)width * width / (width + overflow)), Math.min(24, width - 1), width - 1);
        int thumbX = x + (int)Math.round((width - thumbWidth) * (double)Math.min(previewOffset, overflow) / overflow);
        return new PreviewTrack(x, topPos + 63, width, thumbX, thumbWidth, overflow);
    }

    private PreviewTrack previewTrack() {
        return previewVisible() ? previewTrack(previewOverflow(signaturePreview())) : null;
    }

    private void movePreviewThumb(PreviewTrack track, double pointerX) {
        double fraction = Math.clamp((pointerX - previewGrab - track.x()) / (track.width() - track.thumbWidth()), 0, 1);
        previewOffset = (int)Math.round(track.overflow() * fraction);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        var track = button == 0 ? previewTrack() : null;
        if (track != null && track.contains(x, y)) {
            setFocused(null);
            previewGrab = x >= track.thumbX() && x < track.thumbX() + track.thumbWidth()
                ? x - track.thumbX() : track.thumbWidth() / 2d;
            movePreviewThumb(track, x);
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && previewGrab >= 0) {
            var track = previewTrack();
            if (track != null) movePreviewThumb(track, x);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && previewGrab >= 0) {
            var track = previewTrack();
            if (track != null) movePreviewThumb(track, x);
            previewGrab = -1;
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (previewVisible() && x >= leftPos+8 && x < leftPos+imageWidth-8 && y >= topPos+32 && y < topPos+64) {
            int max = previewOverflow(signaturePreview());
            if (max > 0) {
                int delta = (int)Math.round((horizontal != 0 ? horizontal : -vertical)*24);
                previewOffset = Math.clamp((long)Math.min(previewOffset,max)+delta,0,max);
                return true;
            }
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void commit(){
        try {
            var key=new ModuleDefinition.Key(name,Long.parseLong(revision));
            FlowProgram.Step result;
            if(join) result=new FlowProgram.TaskJoin(source,key,handle,outcome);
            else if(call){
                var inputs=new LinkedHashMap<String,String>(); var outputs=new LinkedHashMap<String,String>();
                if (operands.isEmpty()) arguments.forEach(row->{if(row.local.isBlank())throw new IllegalArgumentException("Missing argument: "+row.name);inputs.put(row.name,row.local);});
                returns.forEach(row->{if(!row.local.isBlank())outputs.put(row.name,row.local);});
                result=task?new FlowProgram.TaskStart(source,key,inputs,handle):new FlowProgram.Invoke(source,key,new FlowCallBindings(inputs,outputs),operands);
            }else{
                var sources=new LinkedHashMap<String,String>(); returns.forEach(row->sources.put(row.name,row.local));
                var implementation=assemblyEnabled?Optional.of(new AssemblySource(assemblyText)):Optional.<AssemblySource>empty();
                if(implementation.isPresent())ProcedureAssembler.assemble(implementation.orElseThrow(),types(arguments),types(returns),sources,
                    ProgramEditorWire.MAX_BYTES,menu.response().map(value->value.hardware().programUnits()).orElse(Long.MAX_VALUE),linkedCells);
                result=new FlowProgram.Procedure(source,key,types(arguments),types(returns),sources,new FlowBlockRules(forbidInside,forbidContexts),implementation);
            }
            apply.accept(result, labelParts());close.run();
        }catch(IllegalArgumentException|IllegalStateException|ComputationException invalid){notice=text("invalid",String.valueOf(invalid.getMessage()));}
    }
    private static Map<String,DataType> types(List<Row> rows){
        var result=new LinkedHashMap<String,DataType>();
        for(var row:rows)if(result.putIfAbsent(row.name,row.type())!=null)throw new IllegalArgumentException("Duplicate port: "+row.name);
        return result;
    }
    private void ruleEditor() {
        var choices = new LinkedHashMap<String, Component>();
        for (String group : contextRules ? List.of("loop", "custom") : List.of("device_wait", "transaction_control", "loop", "custom"))
            choices.put(group, text("rule_selector_" + group));
        for (var kind : FlowBlockDeclarations.Kind.values()) {
            if (kind.delimiter() || kind == FlowBlockDeclarations.Kind.PROCEDURE || kind == FlowBlockDeclarations.Kind.INVOKE || contextRules && !kind.header()) continue;
            choices.put(kind.id(), text("add_" + kind.id()));
        }
        definitions.forEach(def -> choices.put(FlowBlockDeclarations.procedure(def.key()), text("block_invoke", def.key().id(), def.key().revision())));
        try {
            var key = new ModuleDefinition.Key(name, Long.parseLong(revision));
            choices.put(FlowBlockDeclarations.procedure(key), text("block_invoke", key.id(), key.revision()));
        } catch (IllegalArgumentException incomplete) { /* The signature can still be edited before it is valid. */ }
        var selected = contextRules ? forbidContexts : forbidInside;
        selected.stream().sorted().forEach(id -> choices.putIfAbsent(id, Component.literal(id))); // Stale selectors remain visible, never silently removed.
        int count = Math.max(1, (imageHeight - 178) / 22);
        var entries = new ArrayList<>(choices.entrySet()); rulePage = Math.clamp(rulePage, 0, Math.max(0, (entries.size()-1)/count));
        for (int i = rulePage*count; i < Math.min(entries.size(), (rulePage+1)*count); i++) {
            var entry = entries.get(i); var label = text(selected.contains(entry.getKey()) ? "rule_forbidden" : "rule_allowed", entry.getValue());
            var toggle = addRenderableWidget(new CreateTextButton(font,leftPos+8,topPos+104+(i-rulePage*count)*22,imageWidth-16,18,label,()->{
                if (!selected.remove(entry.getKey())) selected.add(entry.getKey()); init();
            }));
            toggle.setTooltip(Tooltip.create(label));
        }
        int third = (imageWidth-24)/3, y = imageHeight-71;
        button(8,y,third,"previous",()->{rulePage--;init();}).active=rulePage>0;
        button(12+third,y,third,contextRules?"rules_contexts":"rules_inside",()->{contextRules=!contextRules;rulePage=0;init();});
        button(16+third*2,y,third,"next",()->{rulePage++;init();}).active=(rulePage+1)*count<entries.size();
        notice=text("rules_hint");
    }
    private static String defaultSample(EditorValueFormat.Kind kind){return switch(kind){case INTEGER,DECIMAL->"0";case BOOLEAN->"false";case TEXT->"";case JSON,NBT->"{}";};}
    private EditBox field(int x,int y,int width,String key,String value,Consumer<String> changed){
        var box=addRenderableWidget(new EditBox(font,leftPos+x,topPos+y,width,18,text(key)));
        box.setMaxLength(ProgramEditorWire.MAX_BYTES+1);box.setValue(value);box.setResponder(changed);box.setTooltip(Tooltip.create(text(key)));return box;
    }
    private CreateTextButton button(int x,int y,int width,String key,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,18,text(key),action));}
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my){
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,font.plainSubstrByWidth(text(join?"task_join_title":task?"task_start_title":call?"procedure_call_title":"procedure_definition_title").getString(),imageWidth-90),leftPos+10,topPos+10,0xffeeeeee,false);
        if (join) {
            g.drawString(font,text("task_handle_input"),leftPos+8,topPos+88,0xffcccccc,false);
            g.drawString(font,text("task_outcome"),leftPos+8,topPos+129,0xffcccccc,false);
        } else if (previewVisible()) {
            var preview = signaturePreview();
            int x = leftPos+8, y = topPos+32, right = leftPos+imageWidth-8;
            int overflow = previewOverflow(preview);
            previewOffset = Math.clamp(previewOffset, 0, overflow);
            g.enableScissor(x,y,right,y+31);
            try { preview.draw(g,font,x-previewOffset,y+2); }
            finally { g.disableScissor(); }
            var track = previewTrack(overflow);
            if (track != null) track.draw(g,previewGrab >= 0);
            if (overflow > 0 && mx >= x && mx < right && my >= y && my < y+31)
                g.renderTooltip(font,text("procedure_preview_scroll"),mx,my);
        } else g.drawString(font,text(assemblyView ? "assembly_source" : rulesView ? (contextRules?"rules_context_heading":"rules_inside_heading") : call?"procedure_binding_heading":output?"procedure_return_heading":"procedure_input_heading"),leftPos+8,topPos+88,0xffcccccc,false);
        g.drawString(font,font.plainSubstrByWidth(notice.getString(),imageWidth-16),leftPos+8,topPos+imageHeight-45,0xffffddaa,false);
        if(my>=topPos+imageHeight-48&&my<topPos+imageHeight-29)g.renderTooltip(font,font.split(notice,imageWidth-20),mx,my);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        var tooltip=typeTooltip(mx,my,hasShiftDown());
        if(tooltip!=null)g.renderTooltip(font,FlowTypeDetails.visibleLines(font, tooltip, width, height),mx,my);
    }
    private void typeHint(net.minecraft.client.gui.components.AbstractWidget widget, Row row, Component help) {
        widget.setTooltip(null); // The shared render route owns help, advice, and Shift details.
        typeHints.put(widget, new TypeHintTarget(row, help));
    }
    Component typeTooltip(int mx,int my,boolean shift) {
        for(var entry:typeHints.entrySet()) {
            var widget = entry.getKey();
            if (!widget.visible || mx < widget.getX() || mx >= widget.getRight() || my < widget.getY() || my >= widget.getBottom()) continue;
            var target = entry.getValue();
            DataType type=null;
            try {type=target.row().type();} catch(IllegalArgumentException incomplete) { /* No sample-derived fake ANY. */ }
            return FlowTypeDetails.tooltip(target.help(),List.of(FlowTypeDetails.declared(target.row().name,type)),shift,menu.nativeIsa1b());
        }
        return null;
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
    @Override public void onClose(){close.run();}
    @Override public void removed(){}
    private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.flow_editor."+key,args);}
}
