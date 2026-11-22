package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Selects a fixed definition in the editable Scratch workspace, without running or installing it. */
public final class ComputerTerminalTargetScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final List<FlowProgram.Procedure> definitions;
    private final Consumer<ModuleDefinition.Key> selected;
    private final Runnable close;
    private int page;
    ComputerTerminalTargetScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
            List<FlowProgram.Procedure> definitions, Consumer<ModuleDefinition.Key> selected, Runnable close) {
        super(menu, inventory, title); this.definitions=List.copyOf(definitions); this.selected=selected; this.close=close;
    }
    @Override protected void init() {
        setWindowSize(Math.min(540,width-12),Math.min(340,height-12)); super.init(); clearWidgets(); setFocused(null);
        int size=Math.max(1,(imageHeight-92)/24); page=Math.clamp(page,0,Math.max(0,(definitions.size()-1)/size));
        for(int index=page*size;index<definitions.size()&&index<(page+1)*size;index++) {
            var definition=definitions.get(index); var key=definition.key();
            var control=button(8,34+(index-page*size)*24,imageWidth-16,
                Component.literal(key.id()+"@"+key.revision()+" · "+definition.inputs().keySet()),()->selected.accept(key));
            control.setTooltip(Tooltip.create(text("task_signature",definition.inputs().toString(),definition.outputs().toString())));
        }
        button(8,imageHeight-27,56,text("previous"),()->{page--;init();}).active=page>0;
        button(68,imageHeight-27,56,text("next"),()->{page++;init();}).active=(page+1)*size<definitions.size();
        button(imageWidth-76,imageHeight-27,68,text("cancel"),this::onClose);
    }
    private CreateTextButton button(int x,int y,int width,Component label,Runnable action) {
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,20,label,action,true));
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,text("task_target_title"),leftPos+10,topPos+10,0xffeeeeee,false);
        g.drawString(font,font.plainSubstrByWidth(text(definitions.isEmpty()?"task_definitions_empty":"task_target_hint").getString(),imageWidth-16),leftPos+8,topPos+imageHeight-45,0xffaaccee,false);
    }
    @Override public void onClose(){close.run();}
    @Override public void removed(){}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my){}
    private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.terminal."+key,args);}
}
