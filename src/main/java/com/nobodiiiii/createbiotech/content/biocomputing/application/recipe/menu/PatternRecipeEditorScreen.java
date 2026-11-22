package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.util.*;
import java.util.function.Consumer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasInteraction;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGaugeLayout;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import net.createmod.catnip.gui.element.GuiGameElement;
import com.nobodiiiii.createbiotech.foundation.gui.PatternIconButton;
import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Local graph authoring over the book-backed draft; no second catalog or scheduling semantics. */
public final class PatternRecipeEditorScreen extends AbstractSimiContainerScreen<PatternStorageCoreMenu> {
    private static final int NODE_W=PatternRecipeGraph.NODE_WIDTH, NODE_H=PatternRecipeGraph.NODE_HEIGHT, OVERLAY_W=254, OVERLAY_H=202, VISIBLE=8;
    private final PatternStorageCoreScreen parent;
    private final Inventory inventory;
    private final PatternRecord original;
    private final PatternRecipeDraft draft;
    private PatternRecipeGraph graph;
    private final CanvasViewport viewport=new CanvasViewport();
    private final CanvasInteraction<Long> interaction=new CanvasInteraction<>(viewport);
    private AddressEditBox address;
    private EditBox priority;
    private int canvasWidth,canvasHeight,overlayStage=-1,inputOffset,outputOffset,pickerOutputIndex=-1;
    private boolean advanced,overlay,picker,pickerOutput,waiting,refreshing,viewInitialized;
    private int refreshTicks;
    private PatternBrowserPage refreshObserved;
    private Integer connectingStage;
    private long selected=-1,responseVersion;
    private double mouseWorldX,mouseWorldY;
    private Component error;
    private UUID previewIntent;
    private net.minecraft.resources.ResourceLocation previewRecipe;
    private Component previewHint;
    private static final List<PatternRecipeGraph.Kind> NODE_ROLES=List.of(PatternRecipeGraph.Kind.MATERIAL,PatternRecipeGraph.Kind.OUTPUT,PatternRecipeGraph.Kind.STAGE);
    private long editingNode=-1;
    private int nodeRoleIndex,nodeCount=1;
    private PatternIngredient nodeMaterial;
    private ItemStack nodeStack=ItemStack.EMPTY;
    private String nodeStageAddress="";
    private AddressEditBox nodeAddress;
    private ScrollInput nodeRole,nodeAmount;

    public PatternRecipeEditorScreen(PatternStorageCoreScreen parent,PatternStorageCoreMenu menu,Inventory inventory,PatternRecord original) {
        super(menu,inventory,tr("title")); this.parent=parent;this.inventory=inventory;this.original=original;
        draft=new PatternRecipeDraft(original);graph=new PatternRecipeGraph(draft);
    }
    public PatternRecipeEditorScreen(PatternStorageCoreScreen parent,PatternStorageCoreMenu menu,Inventory inventory) {
        super(menu,inventory,tr("title")); this.parent=parent;this.inventory=inventory;original=null;
        draft=new PatternRecipeDraft();graph=new PatternRecipeGraph(draft);
    }
    private int cx() { return leftPos+8; }
    private int cy() { return topPos+48; }
    private int cw() { return canvasWidth-16; }
    private int ch() { return canvasHeight-70; }
    private int ox() { return leftPos+(canvasWidth-OVERLAY_W)/2; }
    private int oy() { return topPos+(canvasHeight-OVERLAY_H)/2; }
    private int gx() { return ox(); }
    private int gy() { return topPos+(canvasHeight-PatternGaugeLayout.HEIGHT)/2; }
    private boolean inCanvas(double x,double y) { return x>=cx()&&x<cx()+cw()&&y>=cy()&&y<cy()+ch(); }
    private double wx(double x) { return viewport.worldX(x-cx()); }
    private double wy(double y) { return viewport.worldY(y-cy()); }
    @Override protected void init() {
        if(!overlay||overlayStage>=draft.stages().size())overlayStage=-1;
        String pendingAddress=address==null?draft.address(overlayStage):address.getValue();
        String pendingPriority=priority==null?draft.priority().stream().mapToObj(Integer::toString).findFirst().orElse(""):priority.getValue();
        canvasWidth=Math.min(560,Math.max(280,width-20));canvasHeight=Math.min(360,Math.max(230,height-20));
        setWindowSize(canvasWidth,canvasHeight);super.init();clearWidgets();
        if(picker) {
            icon(ox()+224,oy()+8,AllIcons.I_CONFIG_DISCARD,"back_canvas",()->{picker=false;init();});
        } else if(editingNode>=0) {
            initNodeEditor();
        } else if(overlay) {
            initGaugeOverlay(pendingAddress,pendingPriority);
            icon(advanced?ox()+200:gx()+9,advanced?oy()+179:gy()+135,AllIcons.I_CONFIG_DISCARD,"cancel",this::onClose);
        } else {
            address=null;priority=null;
            icon(leftPos+8,topPos+25,AllIcons.I_ADD,"add_node",()->openNode(graph.addNode(wx(cx()+cw()/2d),wy(cy()+ch()/2d))));
            icon(leftPos+32,topPos+25,AllIcons.I_CONFIG_PREV,"reset_view",this::resetView);
            if(net.neoforged.fml.ModList.get().isLoaded("jei")) icon(leftPos+56,topPos+25,AllIcons.I_OPEN_FOLDER,"jei.choose",()->{
                if(!canInitializeRecipe()) error=tr("jei.empty_draft");
                else if(!com.nobodiiiii.createbiotech.compat.jei.PatternRecipeTransfer.openChooser()) error=tr("jei.unavailable");
            });
            icon(leftPos+canvasWidth-54,topPos+25,AllIcons.I_CONFIG_DISCARD,"cancel",this::onClose);
            icon(leftPos+canvasWidth-30,topPos+25,AllIcons.I_CONFIRM,"save",this::save);
        }
        if(!viewInitialized) {resetView();viewInitialized=true;}
        updateWidgetState();
    }
    private void initGaugeOverlay(String pendingAddress,String pendingPriority) {
        inputOffset=Math.min(inputOffset/9*9,Math.max(0,(draft.inputs(overlayStage).size()-1)/9*9));
        outputOffset=Math.min(outputOffset/VISIBLE*VISIBLE,draft.outputs().size()/VISIBLE*VISIBLE);
        address=new AddressEditBox(this,new NoShadowFontWrapper(font),gx()+36,gy()+109,108,10,false);
        address.setBordered(false);address.setMaxLength(25);address.setValue(pendingAddress);address.setTextColor(0x555555);
        address.active=!draft.sequenced()||overlayStage>=0;
        if(advanced) {
            priority=addRenderableWidget(new EditBox(font,ox()+66,oy()+109,96,18,tr("priority")));
            priority.setMaxLength(11);priority.setValue(pendingPriority);priority.setHint(tr("priority"));
            icon(ox()+172,oy()+109,AllIcons.I_TRASH,"clear_priority",()->priority.setValue(""));
            icon(ox()+12,oy()+80,AllIcons.I_CONFIG_PREV,"previous_outputs",()->{outputOffset=Math.max(0,outputOffset-VISIBLE);init();});
            icon(ox()+36,oy()+80,AllIcons.I_CONFIG_NEXT,"next_outputs",()->{outputOffset=Math.min(draft.outputs().size()/VISIBLE*VISIBLE,outputOffset+VISIBLE);init();});
            var choices=new ArrayList<Component>();choices.add(tr("common"));
            for(int i=0;i<draft.stages().size();i++)choices.add(tr("stage",i+1));
            addRenderableWidget(new SelectionScrollInput(ox()+12,oy()+140,230,18).forOptions(choices)
                .setState(overlayStage+1).calling(value->{
                    if(flushOverlay()){openOverlay(value-1);advanced=true;init();}
                    else init();
                }).titled(tr("stage_settings")));
            if(overlayStage>=0) {
                icon(ox()+176,oy()+5,AllIcons.I_CONFIG_PREV,"stage_up",()->moveStage(-1));
                icon(ox()+198,oy()+5,AllIcons.I_CONFIG_NEXT,"stage_down",()->moveStage(1));
                icon(ox()+220,oy()+5,AllIcons.I_TRASH,"remove_stage",()->{if(flushOverlay()){graph.removeStage(overlayStage);closeOverlay();}});
            }
            icon(ox()+224,oy()+179,AllIcons.I_CONFIRM,"recipe_settings",()->{advanced=false;init();});
        } else {
            addRenderableWidget(address);
            icon(gx()+31,gy()+47,AllIcons.I_ADD,"add_input_canvas",()->{if(flushOverlay())beginConnection(overlayStage);});
            icon(gx()+31,gy()+67,AllIcons.I_MOVE_GAUGE,"relocate_node",()->{if(flushOverlay()){selected=graph.sink(overlayStage).id;closeOverlay();}});
            if(draft.shaped()) icon(gx()+7,gy()+67,AllIcons.I_CONFIG_DISCARD,"detach_grid",()->{draft.detachGrid();init();});
            if(draft.inputs(overlayStage).size()>9) {
                icon(gx()+7,gy()+27,AllIcons.I_CONFIG_PREV,"previous_inputs",()->{inputOffset=Math.max(0,inputOffset-9);init();});
                icon(gx()+7,gy()+67,AllIcons.I_CONFIG_NEXT,"next_inputs",()->{inputOffset+=9;init();});
            }
            icon(gx()+31,gy()+135,AllIcons.I_CONFIG_OPEN,"advanced_settings",()->{advanced=true;init();});
            icon(gx()+145,gy()+135,AllIcons.I_TRASH,"clear_inputs",()->{
                for(var edge:List.copyOf(graph.edges()))if(edge.stage()==overlayStage)graph.disconnect(edge.materialId(),edge.stage());
                init();
            });
            icon(gx()+167,gy()+135,AllIcons.I_CONFIRM,"back_canvas",()->{if(flushOverlay())closeOverlay();});
        }
    }
    private IconButton icon(int x,int y,AllIcons icon,String tip,Runnable action) {
        var widget=addRenderableWidget(new PatternIconButton(x,y,icon));widget.setToolTip(tr(tip));
        widget.withCallback(()->{if(!waiting&&!refreshing)action.run();});return widget;
    }
    private void updateWidgetState() {
        for(var child:children()) if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)widget.active=!waiting&&!refreshing;
        if(address!=null)address.active=!waiting&&!refreshing&&(!draft.sequenced()||overlayStage>=0);
        if(editingNode>=0&&nodeRole!=null)nodeRole.active=!waiting&&!refreshing&&graph.get(editingNode).kind==PatternRecipeGraph.Kind.UNASSIGNED;
    }
    private PatternRecipeGraph.Kind nodeRole() { return NODE_ROLES.get(nodeRoleIndex); }
    private void openNode(long id) {
        var node=graph.get(id);if(node==null)return;
        if(node.kind==PatternRecipeGraph.Kind.COMMON||node.kind==PatternRecipeGraph.Kind.STAGE) {openOverlay(node.kind==PatternRecipeGraph.Kind.STAGE?node.index:-1);return;}
        editingNode=id;selected=id;overlay=false;picker=false;overlayStage=-1;address=null;priority=null;
        connectingStage=null;interaction.cancel();nodeAddress=null;nodeRole=null;error=null;
        nodeRoleIndex=node.kind==PatternRecipeGraph.Kind.OUTPUT?1:0;nodeCount=1;nodeMaterial=node.material();nodeStack=ItemStack.EMPTY;nodeStageAddress=draft.address(-1);
        if(node.kind==PatternRecipeGraph.Kind.MATERIAL) {nodeCount=nodeMaterial.count();nodeStack=PatternRecipeView.ingredient(nodeMaterial);}
        if(node.kind==PatternRecipeGraph.Kind.OUTPUT) {var output=draft.outputs().get(node.index);nodeStack=output.stack().stack();nodeCount=output.count();}
        init();
    }
    private void initNodeEditor() {
        if(nodeAddress!=null)nodeStageAddress=nodeAddress.getValue();
        nodeAddress=null;
        nodeRole=addRenderableWidget(new SelectionScrollInput(ox()+66,oy()+36,176,18)
            .forOptions(List.of(tr("material"),tr("output"),tr("ordered_stage"))).setState(nodeRoleIndex)
            .calling(value->{nodeRoleIndex=value;init();}).titled(tr("node_role")));
        if(nodeRole()==PatternRecipeGraph.Kind.STAGE) {
            nodeAddress=addRenderableWidget(new AddressEditBox(this,font,ox()+66,oy()+78,176,18,false));
            nodeAddress.setMaxLength(25);nodeAddress.setValue(nodeStageAddress);
        } else {
            nodeAmount=addRenderableWidget(new ScrollInput(ox()+66,oy()+116,176,18)
                .withRange(1,BigItemStack.INF+1).setState(nodeCount).calling(value->nodeCount=value).titled(tr("quantity")));
        }
        icon(ox()+12,oy()+179,AllIcons.I_TRASH,"remove_node",()->{graph.removeNode(editingNode);closeNode();});
        if(nodeRole()!=PatternRecipeGraph.Kind.MATERIAL)
            icon(ox()+36,oy()+179,AllIcons.I_ADD,"add_input_canvas",()->{
                if(!applyNode())return;
                var node=graph.get(editingNode);int stage=node.kind==PatternRecipeGraph.Kind.STAGE?node.index:draft.sequenced()?draft.stages().size()-1:-1;
                closeNode();beginConnection(stage);
            });
        icon(ox()+200,oy()+179,AllIcons.I_CONFIG_DISCARD,"cancel_node",this::closeNode);
        icon(ox()+224,oy()+179,AllIcons.I_CONFIRM,"apply_node",()->{if(applyNode())closeNode();});
    }
    private boolean applyNode() {
        try {
            switch(nodeRole()) {
                case MATERIAL -> {
                    if(nodeMaterial==null) {error=tr("node_item_required");return false;}
                    graph.configureMaterial(editingNode,new PatternIngredient(nodeMaterial.itemId(),nodeMaterial.tagId(),nodeCount,nodeMaterial.components()));
                }
                case OUTPUT -> {
                    if(nodeStack.isEmpty()) {error=tr("node_item_required");return false;}
                    graph.configureOutput(editingNode,new PatternOutput(new StackKey(nodeStack),nodeCount));
                }
                case STAGE -> graph.configureStage(editingNode,RecipeAddressRules.requireNormalized(nodeAddress.getValue()));
                default -> throw new IllegalStateException("Not a configurable role");
            }
            error=null;return true;
        } catch(IllegalArgumentException invalid) {error=tr("invalid");return false;}
    }
    private void closeNode() {editingNode=-1;nodeAddress=null;nodeRole=null;nodeAmount=null;picker=false;init();}
    private Rect2i nodeItemArea() {return new Rect2i(ox()+66,oy()+76,18,18);}
    private void openOverlay(int stage) {
        editingNode=-1;nodeAddress=null;nodeRole=null;
        advanced=false;overlay=true;picker=false;overlayStage=stage;inputOffset=0;outputOffset=0;address=null;priority=null;
        connectingStage=null;interaction.cancel();init();
    }
    private void closeOverlay() {overlay=false;picker=false;overlayStage=-1;address=null;priority=null;init();}
    private boolean flushOverlay() {
        if(!overlay)return true;
        try {
            if(address.active)draft.setAddress(overlayStage,RecipeAddressRules.requireNormalized(address.getValue()));
            if(priority!=null)draft.setPriority(priority.getValue().isBlank()?OptionalInt.empty():OptionalInt.of(Integer.parseInt(priority.getValue())));
            error=null;return true;
        } catch(IllegalArgumentException invalid) {error=tr("invalid");return false;}
    }
    private void beginConnection(int stage) {closeOverlay();connectingStage=stage;}
    private void openPicker(boolean output) {picker=true;pickerOutput=output;pickerOutputIndex=-1;init();}
    private void moveStage(int delta) {
        int next=overlayStage+delta;
        if(next<0||next>=draft.stages().size()||!flushOverlay())return;
        boolean wasAdvanced=advanced;graph.moveStage(overlayStage,next);openOverlay(next);advanced=wasAdvanced;init();
    }
    private void save() {
        if(waiting||refreshing||!flushOverlay())return;
        if(graph.nodes().stream().anyMatch(node->node.kind==PatternRecipeGraph.Kind.UNASSIGNED)) {error=tr("unconfigured_nodes");return;}
        try {
            var snapshot=draft.snapshot();responseVersion=menu.editResponseVersion();
            if(original==null)menu.submitCreate(snapshot);else menu.submitEdit(original,snapshot);
            waiting=true;updateWidgetState();
        } catch(IllegalArgumentException invalid) {error=tr("invalid");}
    }
    public boolean canInitializeRecipe() {
        return original==null && !draft.shaped() && !waiting && !refreshing && !overlay && !picker && editingNode<0 && connectingStage==null
            && graph.nodes().size()==1 && !draft.sequenced() && draft.inputs(-1).isEmpty() && draft.outputs().isEmpty();
    }
    public void requestRecipePreview(net.minecraft.resources.ResourceLocation recipe) {
        if (!canInitializeRecipe()) return;
        previewRecipe=recipe; previewIntent=menu.requestPreview(recipe); error=null; previewHint=tr("jei.loading",recipe.toString());
    }
    private void consumeRecipePreview() {
        if (previewIntent==null) return;
        var reply=menu.takePreview(previewIntent);
        if (reply==null) return;
        previewIntent=null; previewHint=null;
        if (reply.outcome()!=RecipePreviewPackets.Outcome.READY) {
            error=tr("jei."+reply.outcome().name().toLowerCase(Locale.ROOT)); return;
        }
        if (!canInitializeRecipe()) { error=tr("jei.empty_draft"); return; }
        try { draft.initialize(RecipeDraftPreview.decode(reply.json(),inventory.player.registryAccess())); }
        catch (RuntimeException unrepresentable) { error=tr("jei.unsupported_output"); return; }
        graph=new PatternRecipeGraph(draft); resetView();
        error=null; previewHint=tr("jei.preview",previewRecipe.toString());
    }
    @Override protected void containerTick() {
        consumeRecipePreview();
        super.containerTick();if(address!=null&&overlay&&!picker)address.tick();
        if(nodeAddress!=null&&editingNode>=0&&!picker)nodeAddress.tick();
        tickBrowserRefresh();
        if(waiting&&menu.editResponseVersion()!=responseVersion) {
            waiting=false;updateWidgetState();
            if("success".equals(menu.editResult())) {parent.refreshAfterEdit();onClose();}
            else {
                error=tr("result."+menu.editResult());
                if("stale_generation".equals(menu.editResult())) {refreshing=true;requestBrowserRefresh();}
            }
        }
    }
    private void requestBrowserRefresh() {
        refreshTicks=0;refreshObserved=menu.browser().page().orElse(null);
        var request=menu.browser().request(PatternBrowserFilter.EMPTY,0,Optional.empty());
        if(!request.sendIfBounded(inventory.player.registryAccess(),CBPackets::sendToServer)) {
            refreshing=false;error=tr("result.too_large");
        }
        updateWidgetState();
    }
    private void tickBrowserRefresh() {
        if(!refreshing)return;
        var latest=menu.browser().page().orElse(null);
        if(latest!=null&&latest!=refreshObserved) {
            refreshObserved=latest;
            if(latest.status()==PatternBrowserPage.Status.OK&&!latest.reindexing()) {
                refreshing=false;error=tr("retry_save");updateWidgetState();return;
            }
            if(latest.status()!=PatternBrowserPage.Status.STALE&&latest.status()!=PatternBrowserPage.Status.OK) {
                refreshing=false;error=tr(latest.status()==PatternBrowserPage.Status.DENIED?"result.denied":"result.conflict");
                updateWidgetState();return;
            }
        }
        if(menu.browser().needsRefresh()||++refreshTicks>=menu.browser().refreshAfterTicks())requestBrowserRefresh();
    }
    private void resetView() {
        viewport.reset();
        double minX=graph.nodes().stream().mapToDouble(n->n.x).min().orElse(0)-12;
        double minY=graph.nodes().stream().mapToDouble(n->n.y).min().orElse(0)-12;
        double maxX=graph.nodes().stream().mapToDouble(n->n.x+NODE_W).max().orElse(NODE_W)+12;
        double maxY=graph.nodes().stream().mapToDouble(n->n.y+NODE_H).max().orElse(NODE_H)+12;
        for(int step=0;step<16&&((maxX-minX)*viewport.scale()>cw()||(maxY-minY)*viewport.scale()>ch());step++)viewport.zoomAt(0,0,-1);
        viewport.pan(cw()/2d-(minX+maxX)/2*viewport.scale(),ch()/2d-(minY+maxY)/2*viewport.scale());
    }
    @Override public void onClose() {minecraft.setScreen(parent);}
    @Override public void removed() { /* The parent and child intentionally share the server menu. */ }
    private ItemStack carried() {
        ItemStack stack=menu.getCarried();return stack.isEmpty()?inventory.player.getMainHandItem():stack;
    }
    private static PatternIngredient ingredient(ItemStack stack) {
        return new PatternIngredient(BuiltInRegistries.ITEM.getKey(stack.getItem()),null,Math.max(1,stack.getCount()),
            DataComponentPredicate.allOf(stack.getComponentsPatch().split().added()));
    }
    private void addGhost(boolean output,ItemStack stack,double x,double y) {
        if(waiting||refreshing||stack.isEmpty())return;
        if(editingNode>=0) {
            if(nodeRole()==PatternRecipeGraph.Kind.STAGE)return;
            nodeMaterial=ingredient(stack);nodeStack=stack.copy();nodeCount=Math.max(1,stack.getCount());picker=false;init();return;
        }
        if(output) {int index=picker&&pickerOutputIndex>=0?pickerOutputIndex:draft.outputs().size();draft.setOutput(index,new PatternOutput(new StackKey(stack),Math.max(1,stack.getCount())));graph.syncOutputs();}
        else selected=graph.addMaterial(ingredient(stack),x,y);
        picker=false;if(output)resetView();init();
    }
    public record GhostTarget(Rect2i area,Consumer<ItemStack> accept) {}
    public List<GhostTarget> ghostTargets() {
        if(waiting||refreshing)return List.of();
        var targets=new ArrayList<GhostTarget>();
        if(picker) {
            targets.add(new GhostTarget(new Rect2i(ox()+12,oy()+35,180,80),stack->addGhost(pickerOutput,stack,wx(cx()+cw()/2d),wy(cy()+ch()/2d))));
        } else if(editingNode>=0) {
            if(nodeRole()!=PatternRecipeGraph.Kind.STAGE)targets.add(new GhostTarget(nodeItemArea(),stack->addGhost(nodeRole()==PatternRecipeGraph.Kind.OUTPUT,stack,0,0)));
        } else if(overlay) {
            for(int i=0;i<slotCount(true)&&slotOffset(true)+i<=draft.outputs().size();i++) {
                int index=slotOffset(true)+i;
                targets.add(new GhostTarget(slotArea(true,i),stack->replaceOutput(index,stack)));
            }
        } else {
            targets.add(new GhostTarget(new Rect2i(leftPos+8,topPos+25,18,18),stack->addGhost(false,stack,wx(cx()+cw()/2d),wy(cy()+ch()/2d))));
            targets.add(new GhostTarget(new Rect2i(cx(),cy(),cw(),ch()),stack->addGhost(false,stack,wx(cx()+cw()/2d),wy(cy()+ch()/2d))));
        }
        return List.copyOf(targets);
    }
    private void replaceOutput(int index,ItemStack stack) {
        if(waiting||refreshing||stack.isEmpty()||index>draft.outputs().size())return;
        draft.setOutput(index,new PatternOutput(new StackKey(stack),Math.max(1,stack.getCount())));graph.syncOutputs();
    }
    private PatternRecipeGraph.Node hit(double x,double y) {return graph.nodeAt(x,y);}
    private int slotCount(boolean output) {return advanced?(output?VISIBLE:0):(output?1:9);}
    private int slotOffset(boolean output) {return output?(advanced?outputOffset:0):inputOffset;}
    private Rect2i slotArea(boolean output,int slot) {
        if(!advanced)return output?PatternGaugeLayout.output(gx(),gy()):PatternGaugeLayout.input(gx(),gy(),slot);
        return new Rect2i(ox()+12+slot*28,oy()+52,18,18);
    }
    private int slotAt(boolean output,double x,double y) {
        if(!output && draft.shaped() && !advanced) {
            for(int i=0;i<9;i++) if(slotArea(false,i).contains((int)x,(int)y)) return draft.inputAtCell(i);
            return -1;
        }
        int total=output?draft.outputs().size():draft.inputs(overlayStage).size();
        int offset=slotOffset(output);
        for(int i=0;i<slotCount(output)&&offset+i<total+(output?1:0);i++)if(slotArea(output,i).contains((int)x,(int)y))return offset+i;
        return -1;
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(waiting||refreshing)return true;
        if(picker) {
            for(int i=0;i<36;i++) {
                Rect2i area=new Rect2i(ox()+12+(i%9)*20,oy()+35+(i/9)*20,18,18);
                if(area.contains((int)x,(int)y)) {if(button==0)addGhost(pickerOutput,inventory.getItem(i).copy(),wx(cx()+cw()/2d),wy(cy()+ch()/2d));return true;}
            }
            super.mouseClicked(x,y,button);return true;
        }
        if(editingNode>=0) {
            if(nodeRole()!=PatternRecipeGraph.Kind.STAGE&&nodeItemArea().contains((int)x,(int)y)) {
                if(button==0)openPicker(nodeRole()==PatternRecipeGraph.Kind.OUTPUT);
                return true;
            }
            super.mouseClicked(x,y,button);return true;
        }
        if(overlay) {
            for(boolean output:new boolean[]{false,true}) {
                int index=slotAt(output,x,y);if(index<0)continue;
                if(button==1||hasShiftDown()) {
                    if(output&&index<draft.outputs().size())graph.removeOutput(index);
                    else if(!output) {var edge=edgeAtInput(overlayStage,index);if(edge!=null)graph.disconnect(edge.materialId(),edge.stage());}
                    init();
                } else if(output&&button==0) {
                    ItemStack stack=carried();
                    if(!stack.isEmpty())replaceOutput(index,stack);else {openPicker(true);pickerOutputIndex=index;}
                }
                return true;
            }
            super.mouseClicked(x,y,button);return true;
        }
        if(!inCanvas(x,y))return super.mouseClicked(x,y,button);
        double worldX=wx(x),worldY=wy(y);
        if(button==2) {interaction.beginPan();return true;}
        if(button!=0&&button!=1)return true;
        var node=hit(worldX,worldY);
        if(connectingStage!=null) {
            if(button==0&&node!=null&&node!=graph.sink(connectingStage)&&!graph.connect(node.id,connectingStage))error=tr("invalid_slot");
            connectingStage=null;return true;
        }
        if(hasShiftDown()||button==1) {
            if(node!=null) {
                if(node.kind==PatternRecipeGraph.Kind.COMMON)openOverlay(-1);else graph.removeNode(node.id);
            } else {var edge=graph.edgeAt(worldX,worldY,5/viewport.scale());if(edge!=null)graph.disconnect(edge.materialId(),edge.stage());}
            return true;
        }
        if(node!=null) {selected=node.id;interaction.beginNode(node.id);return true;}
        if(!carried().isEmpty()) {addGhost(false,carried(),worldX,worldY);return true;}
        interaction.beginPan();return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(waiting||refreshing)return true;
        if(overlay||picker||editingNode>=0) {super.mouseDragged(x,y,button,dx,dy);return true;}
        if(interaction.drag(dx,dy,(id,worldDx,worldDy)->{var node=graph.get(id);if(node!=null)graph.move(node.id,node.x+worldDx,node.y+worldDy);}))return true;
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(overlay||picker||editingNode>=0) {super.mouseReleased(x,y,button);return true;}
        var release=interaction.release();
        if(release.active()) {if(!waiting&&release.clickedNode()!=null)openNode(release.clickedNode());return true;}
        return super.mouseReleased(x,y,button);
    }
    private PatternRecipeGraph.Edge edgeAtInput(int stage,int index) {
        return graph.edges().stream().filter(e->e.stage()==stage&&graph.inputIndex(e)==index).findFirst().orElse(null);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(waiting||refreshing||picker)return true;
        if(editingNode>=0) {super.mouseScrolled(x,y,horizontal,vertical);return true;}
        if(overlay) {
            for(boolean output:new boolean[]{false,true}) {
                int index=slotAt(output,x,y);int size=output?draft.outputs().size():draft.inputs(overlayStage).size();
                if(index<0||index>=size)continue;
                if(!output && draft.shaped()) {error=tr("grid_unit");return true;}
                int old=output?draft.outputs().get(index).count():draft.inputs(overlayStage).get(index).count();
                int value=Math.clamp((long)old+(int)Math.signum(vertical)*(hasControlDown()?10:1),1,BigItemStack.INF);
                if(output)draft.setOutputCount(index,value);else graph.setInputCount(edgeAtInput(overlayStage,index),value);
                return true;
            }
            super.mouseScrolled(x,y,horizontal,vertical);return true;
        }
        if(inCanvas(x,y)) {viewport.zoomAt(x-cx(),y-cy(),(int)Math.signum(vertical));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private static void line(GuiGraphics g,double ax,double ay,double bx,double by,int color) {
        int x1=(int)Math.round(ax),y1=(int)Math.round(ay),x2=(int)Math.round(bx),y2=(int)Math.round(by);
        g.fill(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2)+1,Math.max(y1,y2)+1,color);
    }
    private void wire(GuiGraphics g,double ax,double ay,double bx,double by,int color) {
        double mid=(ax+bx)/2;line(g,ax,ay,mid,ay,color);line(g,mid,ay,mid,by,color);line(g,mid,by,bx,by,color);
        g.fill((int)bx-3,(int)by-2,(int)bx+1,(int)by+3,color);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,canvasWidth,canvasHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos,topPos,canvasWidth,21);
        g.drawString(font,title,leftPos+10,topPos+6,0xffeeeeee,false);
        g.fill(cx(),cy(),cx()+cw(),cy()+ch(),0xff202831);
        mouseWorldX=wx(mouseX);mouseWorldY=wy(mouseY);
        g.enableScissor(cx(),cy(),cx()+cw(),cy()+ch());
        g.pose().pushPose();g.pose().translate(cx()+viewport.screenX(0),cy()+viewport.screenY(0),0);
        g.pose().scale((float)viewport.scale(),(float)viewport.scale(),1);
        int gx=(int)Math.floor(viewport.worldX(0)/24)*24,gy=(int)Math.floor(viewport.worldY(0)/24)*24;
        for(int x=gx;x<viewport.worldX(cw());x+=24)for(int y=gy;y<viewport.worldY(ch());y+=24)g.fill(x,y,x+1,y+1,0xff46515b);
        for(var edge:graph.edges()) {
            var source=graph.get(edge.materialId());var sink=graph.sink(edge.stage());
            wire(g,source.x+NODE_W,source.y+NODE_H/2d,sink.x,sink.y+NODE_H/2d,0xff83c8b3);
        }
        // Ordered stages and output links are schema structure, not user-editable DAG edges.
        for(int stage=0;stage<draft.stages().size();stage++) {
            var from=graph.sink(stage-1);var to=graph.sink(stage);
            wire(g,from.x+NODE_W,from.y+NODE_H/2d,to.x,to.y+NODE_H/2d,0xffc8a86c);
        }
        var result=graph.sink(draft.sequenced()?draft.stages().size()-1:-1);
        for(var node:graph.nodes())if(node.kind==PatternRecipeGraph.Kind.OUTPUT)
            wire(g,result.x+NODE_W,result.y+NODE_H/2d,node.x,node.y+NODE_H/2d,0xffb5a4da);
        if(connectingStage!=null) {var sink=graph.sink(connectingStage);if(sink!=null)wire(g,mouseWorldX,mouseWorldY,sink.x,sink.y+NODE_H/2d,0xfffbd47b);}
        for(var node:graph.nodes())renderNode(g,node);
        g.pose().popPose();g.disableScissor();
        if(overlay)renderOverlay(g);
        if(editingNode>=0)renderNodeEditor(g);
        if(picker)renderPicker(g);
        Component status=waiting?tr("saving"):refreshing?tr("refreshing"):error!=null?error:connectingStage!=null?tr("connect_hint"):previewHint!=null?previewHint:tr("canvas_hint");
        g.drawString(font,font.plainSubstrByWidth(status.getString(),canvasWidth-16),leftPos+8,topPos+canvasHeight-15,error==null?0xffc8d4dd:0xffff8888,false);
    }
    private void renderNode(GuiGraphics g,PatternRecipeGraph.Node node) {
        int x=(int)node.x,y=(int)node.y;
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,x,y,NODE_W,NODE_H);
        g.fill(x,y,x+NODE_W,y+2,node.id==selected?0xfffbd47b:node.kind==PatternRecipeGraph.Kind.MATERIAL?0xff83c8b3:0xffb5a4da);
        ItemStack stack=ItemStack.EMPTY;int count=1;Component label;
        switch(node.kind) {
            case UNASSIGNED -> label=tr("unconfigured_node");
            case MATERIAL -> {stack=PatternRecipeView.ingredient(node.material());count=node.material().count();label=node.material().tagId()!=null?Component.literal("#"+node.material().tagId()):stack.getHoverName();}
            case OUTPUT -> {var output=draft.outputs().get(node.index);stack=output.stack().stack();count=output.count();label=stack.getHoverName();}
            case STAGE -> label=tr("stage",node.index+1);
            default -> label=tr("common");
        }
        PatternRecipeView.slot(g,font,x+5,y+10,stack,count,node.id==selected);
        if(stack.isEmpty())g.drawString(font,node.kind==PatternRecipeGraph.Kind.STAGE?Integer.toString(node.index+1):"+",x+10,y+15,0xffeeeeee,false);
        g.drawString(font,font.plainSubstrByWidth(label.getString(),69),x+26,y+9,0xffeeeeee,false);
        String second=node.kind==PatternRecipeGraph.Kind.UNASSIGNED?tr("configure_node").getString():node.kind==PatternRecipeGraph.Kind.STAGE?draft.address(node.index):node.kind==PatternRecipeGraph.Kind.COMMON?(draft.sequenced()?tr("ordered_stages").getString():draft.address(-1)):tr(node.kind==PatternRecipeGraph.Kind.MATERIAL?"material":"output").getString();
        g.drawString(font,font.plainSubstrByWidth(second,69),x+26,y+22,0xffaabbc8,false);
    }
    private void renderNodeEditor(GuiGraphics g) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,ox(),oy(),OVERLAY_W,OVERLAY_H);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,ox(),oy(),OVERLAY_W,27);
        g.drawString(font,tr("configure_node"),ox()+12,oy()+10,0xffeeeeee,false);
        g.drawString(font,tr("node_role"),ox()+12,oy()+41,0xffeeeeee,false);
        g.drawString(font,tr(nodeRole()==PatternRecipeGraph.Kind.STAGE?"ordered_stage":nodeRole()==PatternRecipeGraph.Kind.OUTPUT?"output":"material"),ox()+68,oy()+41,0xffeeeeee,false);
        if(nodeRole()==PatternRecipeGraph.Kind.STAGE)g.drawString(font,tr("address"),ox()+12,oy()+83,0xffeeeeee,false);
        else {
            g.drawString(font,tr("node_item"),ox()+12,oy()+81,0xffeeeeee,false);
            PatternRecipeView.slot(g,font,nodeItemArea().getX(),nodeItemArea().getY(),nodeStack,nodeCount,false);
            if(nodeStack.isEmpty())g.drawString(font,"+",ox()+72,oy()+81,0xffeeeeee,false);
            g.drawString(font,tr("quantity"),ox()+12,oy()+121,0xffeeeeee,false);
            g.drawString(font,Integer.toString(nodeCount),ox()+68,oy()+121,0xffeeeeee,false);
            if(nodeRole()==PatternRecipeGraph.Kind.MATERIAL)g.drawString(font,tr("node_amount_hint"),ox()+12,oy()+151,0xffaabbc8,false);
        }
    }
    private void renderOverlay(GuiGraphics g) {
        g.fill(leftPos,topPos,leftPos+canvasWidth,topPos+canvasHeight,0xb0000000);
        if(advanced) {renderExtensions(g);return;}
        PatternGaugeLayout.render(g,gx(),gy());
        Component heading=Component.translatable("create.gui.factory_panel.title_as_recipe");
        g.drawString(font,heading,gx()+97-font.width(heading)/2,gy()+4,0x3d3c48,false);
        var materials=draft.inputs(overlayStage);
        for(int i=0;i<9;i++) {
            int index=draft.shaped()?draft.inputAtCell(i):inputOffset+i;
            if(index<0||index>=materials.size())continue;
            var input=materials.get(index);
            renderGaugeItem(g,slotArea(false,i),PatternRecipeView.ingredient(input),input.count());
        }
        var output=draft.outputs().isEmpty()?null:draft.outputs().getFirst();
        renderGaugeItem(g,slotArea(true,0),output==null?ItemStack.EMPTY:output.stack().stack(),output==null?1:output.count());
        if(draft.inputs(overlayStage).size()>9)g.drawString(font,(inputOffset/9+1)+"/"+((draft.inputs(overlayStage).size()+8)/9),gx()+6,gy()+51,0xffeeeeee,false);
        Component label=draft.shaped()?tr("workbench_grid"):overlayStage<0?tr("common"):tr("stage",overlayStage+1);
        g.drawString(font,font.plainSubstrByWidth(label.getString(),78),gx()+59,gy()+140,0x3d3c48,false);
        GuiGameElement.of(AllBlocks.FACTORY_GAUGE.asStack()).scale(4).at(0,0,-200).render(g,gx()+195,gy()+115);
        if(output!=null)GuiGameElement.of(output.stack().stack()).scale(1.625).at(0,0,100).render(g,gx()+214,gy()+128);
    }
    private void renderGaugeItem(GuiGraphics g,Rect2i area,ItemStack stack,int amount) {
        if(stack.isEmpty()) {g.drawString(font,"+",area.getX()+6,area.getY()+5,0xffeeeeee,false);return;}
        g.renderItem(stack,area.getX()+1,area.getY()+1);
        g.renderItemDecorations(font,stack,area.getX()+1,area.getY()+1,Integer.toString(amount));
    }
    private void renderExtensions(GuiGraphics g) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,ox(),oy(),OVERLAY_W,OVERLAY_H);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,ox(),oy(),OVERLAY_W,27);
        g.drawString(font,tr("advanced_settings"),ox()+12,oy()+10,0xffeeeeee,false);
        g.drawString(font,tr("outputs"),ox()+12,oy()+36,0xffeeeeee,false);
        g.drawString(font,tr("priority"),ox()+12,oy()+114,0xffeeeeee,false);
        g.drawString(font,overlayStage<0?tr("common"):tr("stage",overlayStage+1),ox()+16,oy()+145,0xffeeeeee,false);
        g.drawString(font,(outputOffset/VISIBLE+1)+"/"+(draft.outputs().size()/VISIBLE+1),ox()+64,oy()+85,0xffeeeeee,false);
        for(int i=0;i<VISIBLE&&outputOffset+i<=draft.outputs().size();i++) {
            int index=outputOffset+i;boolean empty=index==draft.outputs().size();
            ItemStack stack=empty?ItemStack.EMPTY:draft.outputs().get(index).stack().stack();
            int count=empty?1:draft.outputs().get(index).count();Rect2i area=slotArea(true,i);
            PatternRecipeView.slot(g,font,area.getX(),area.getY(),stack,count,false);
            if(empty)g.drawString(font,"+",area.getX()+6,area.getY()+5,0xffeeeeee,false);
        }
    }
    private void renderPicker(GuiGraphics g) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,ox(),oy(),OVERLAY_W,132);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,ox(),oy(),OVERLAY_W,27);
        g.drawString(font,tr(pickerOutput?"pick_output":"pick_material"),ox()+12,oy()+10,0xffeeeeee,false);
        for(int i=0;i<36;i++) {ItemStack stack=inventory.getItem(i);PatternRecipeView.slot(g,font,ox()+12+i%9*20,oy()+35+i/9*20,stack,stack.getCount(),false);}
        g.drawString(font,tr("ghost_only"),ox()+12,oy()+119,0xffaabbc8,false);
    }
    @Override protected void renderForeground(GuiGraphics g,int mx,int my,float partialTick) {
        super.renderForeground(g,mx,my,partialTick);
        if(picker) {
            for(int i=0;i<36;i++)if(new Rect2i(ox()+12+i%9*20,oy()+35+i/9*20,18,18).contains(mx,my)&&!inventory.getItem(i).isEmpty())g.renderTooltip(font,inventory.getItem(i),mx,my);
            return;
        }
        if(editingNode>=0) {
            if(nodeRole()!=PatternRecipeGraph.Kind.STAGE&&nodeItemArea().contains(mx,my)) {
                if(nodeRole()==PatternRecipeGraph.Kind.MATERIAL&&nodeMaterial!=null)ingredientTooltip(g,nodeMaterial,mx,my);
                else if(!nodeStack.isEmpty())g.renderTooltip(font,nodeStack,mx,my);
            }
            return;
        }
        if(overlay) {
            int input=slotAt(false,mx,my),output=slotAt(true,mx,my);
            if(input>=0)ingredientTooltip(g,draft.inputs(overlayStage).get(input),mx,my);
            if(output>=0&&output<draft.outputs().size())g.renderTooltip(font,draft.outputs().get(output).stack().stack(),mx,my);
            return;
        }
        if(inCanvas(mx,my)) {
            var node=hit(wx(mx),wy(my));if(node==null)return;
            if(node.kind==PatternRecipeGraph.Kind.MATERIAL)ingredientTooltip(g,node.material(),mx,my);
            else if(node.kind==PatternRecipeGraph.Kind.OUTPUT)g.renderTooltip(font,draft.outputs().get(node.index).stack().stack(),mx,my);
        }
    }
    private void ingredientTooltip(GuiGraphics g,PatternIngredient input,int x,int y) {
        var stack=PatternRecipeView.ingredient(input);var lines=new ArrayList<Component>();
        lines.add(input.tagId()!=null?Component.literal("#"+input.tagId()):stack.getHoverName());lines.add(tr("amount",input.count()));
        if(!input.components().equals(DataComponentPredicate.EMPTY))lines.add(tr("components_kept"));
        lines.add(tr(overlay && draft.shaped() ? "grid_slot_hint" : "slot_wheel_hint"));
        g.renderComponentTooltip(font,lines,x,y);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(waiting||refreshing)return true;
        if(key==GLFW.GLFW_KEY_ESCAPE) {
            if(picker) {picker=false;init();} else if(editingNode>=0)closeNode();else if(overlay) {if(advanced){advanced=false;init();}else if(flushOverlay())closeOverlay();} else if(connectingStage!=null)connectingStage=null;else onClose();return true;
        }
        if(overlay||picker||editingNode>=0) {if(getFocused()!=null)getFocused().keyPressed(key,scan,modifiers);return true;}
        if(key==GLFW.GLFW_KEY_C) {var node=hit(mouseWorldX,mouseWorldY);if(node!=null&&node.kind!=PatternRecipeGraph.Kind.MATERIAL&&node.kind!=PatternRecipeGraph.Kind.UNASSIGNED)beginConnection(node.kind==PatternRecipeGraph.Kind.STAGE?node.index:draft.sequenced()&&node.kind==PatternRecipeGraph.Kind.OUTPUT?draft.stages().size()-1:-1);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    static net.minecraft.network.chat.MutableComponent tr(String key,Object... args) {return Component.translatable("create_biotech.pattern_editor."+key,args);}
}



