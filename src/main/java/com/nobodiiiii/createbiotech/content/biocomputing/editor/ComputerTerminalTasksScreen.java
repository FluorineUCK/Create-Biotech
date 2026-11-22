package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Frozen local task observation. Inspection is read-only; claiming explicitly releases a finished slot. */
public final class ComputerTerminalTasksScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final Inventory inventory;
    private final List<DataValue.RecordValue> tasks;
    private final IntConsumer claim;
    private final Runnable close;
    private Consumer<Screen> navigator=screen->minecraft.setScreen(screen);
    private int page, selected;
    ComputerTerminalTasksScreen(ComputerProgramMenu menu, Inventory inventory, Component title, DataValue.ListValue tasks,
            IntConsumer claim, Runnable close) {
        super(menu,inventory,title); this.inventory=inventory; this.claim=claim; this.close=close;
        this.tasks=tasks.values().stream().map(value->{
            if (!(value instanceof DataValue.RecordValue record) || !(record.fields().get("procedure") instanceof DataValue.TextValue)
                    || !(record.fields().get("revision") instanceof DataValue.IntegerValue) || !(record.fields().get("phase") instanceof DataValue.TextValue))
                throw new IllegalArgumentException("Invalid terminal task observation");
            return record;
        }).toList();
    }
    @Override protected void init() {
        setWindowSize(Math.min(540,width-12),Math.min(340,height-12)); super.init(); clearWidgets(); setFocused(null);
        int size=Math.max(1,(imageHeight-112)/24); page=Math.clamp(page,0,Math.max(0,(tasks.size()-1)/size));
        for(int index=page*size;index<tasks.size()&&index<(page+1)*size;index++) {
            final int row=index; var task=tasks.get(index);
            String name=((DataValue.TextValue)task.field("procedure")).value()+"@"+((DataValue.IntegerValue)task.field("revision")).value();
            String phase=((DataValue.TextValue)task.field("phase")).value();
            if (phase.equals("WAITING_ANSWER")) phase=text("waiting_answer").getString();
            if (phase.equals("WAITING_TASK")) phase=text("waiting_task").getString();
            var control=button(8,34+(index-page*size)*24,imageWidth-16,
                Component.literal((selected==index?"> ":"")+(index+1)+" · "+name+" · "+phase),()->{selected=row;init();});
            control.setTooltip(Tooltip.create(text("task_row_hint",name,phase)));
        }
        int half=(imageWidth-20)/2;
        button(8,imageHeight-73,half,text("inspect_task"),this::inspect).active=!tasks.isEmpty();
        var release=button(12+half,imageHeight-73,half,text("claim_task"),()->claim.accept(selected));
        release.active=!tasks.isEmpty()&&Set.of("COMPLETE","FAILED").contains(((DataValue.TextValue)tasks.get(selected).field("phase")).value());
        release.setTooltip(Tooltip.create(text("claim_task_hint")));
        button(8,imageHeight-27,56,text("previous"),()->{page--;init();}).active=page>0;
        button(68,imageHeight-27,56,text("next"),()->{page++;init();}).active=(page+1)*size<tasks.size();
        button(imageWidth-76,imageHeight-27,68,text("back"),this::onClose);
    }
    private void inspect() {
        var fields=new LinkedHashMap<>(tasks.get(selected).fields()); fields.remove("handle");
        navigator.accept(new ComputerValueTreeScreen(menu,inventory,title,this,new DataValue.RecordValue(fields),null,
            text("inspect_task"),text("frozen"),()->navigator.accept(this)));
    }
    private CreateTextButton button(int x,int y,int width,Component label,Runnable action) {
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,20,label,action,true));
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,text("tasks",tasks.size()),leftPos+10,topPos+10,0xffeeeeee,false);
        g.drawString(font,font.plainSubstrByWidth(text(tasks.isEmpty()?"tasks_empty":"tasks_frozen").getString(),imageWidth-16),leftPos+8,topPos+imageHeight-45,0xffaaccee,false);
    }
    @Override public void onClose(){close.run();}
    @Override public void removed(){}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my){}
    private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.terminal."+key,args);}
}
