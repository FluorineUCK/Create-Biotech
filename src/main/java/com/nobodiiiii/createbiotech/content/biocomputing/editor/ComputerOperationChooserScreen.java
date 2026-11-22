package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Shared native selector geometry, category/page navigation and transactional callbacks. */
abstract class ComputerOperationChooserScreen<O extends Enum<O>, C extends Enum<C>>
    extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    protected final O selected;
    private final Consumer<O> selection;
    private final Runnable close;
    private C category;
    private int page;

    protected ComputerOperationChooserScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
        O selected, Consumer<O> selection, Runnable close) {
        super(menu, inventory, title);
        this.selected=Objects.requireNonNull(selected);this.selection=Objects.requireNonNull(selection);
        this.close=Objects.requireNonNull(close);category=category(selected);
    }
    protected abstract O[] catalog();
    protected abstract C[] categories();
    protected abstract C category(O operation);
    protected abstract String translationPrefix();
    protected abstract Component hint();

    @Override protected void init() {
        setWindowSize(Math.min(580,width-12),Math.min(380,height-12));super.init();setFocused(null);clearWidgets();
        int gap=4,count=categories().length,w=(imageWidth-16-gap*(count-1))/count;
        for(int i=0;i<count;i++)categoryButton(i,w,gap);
        var operations=operations();int rows=rows();page=Math.clamp(page,0,Math.max(0,(operations.size()-1)/rows));
        for(int row=0;row<rows;row++){
            int index=page*rows+row;if(index>=operations.size())break;var operation=operations.get(index);
            var button=button(8,58+row*22,imageWidth-16,name(operation),()->{selection.accept(operation);close.run();});
            if(operation==selected)button.green=true;
        }
        int bottom=imageHeight-27;
        button(8,bottom,68,text("previous"),()->{page--;init();}).active=page>0;
        button(80,bottom,68,text("next"),()->{page++;init();}).active=(page+1)*rows<operations.size();
        button(imageWidth-76,bottom,68,text("back"),this::onClose);
    }
    private void categoryButton(int index,int width,int gap){
        var value=categories()[index];var button=button(8+index*(width+gap),34,width,
            text("category."+value.name().toLowerCase(Locale.ROOT)),()->{category=value;page=0;init();});
        if(value==category)button.green=true;
    }
    private List<O> operations(){return Arrays.stream(catalog()).filter(operation->category(operation)==category).toList();}
    private int rows(){return Math.max(1,(imageHeight-58-27-36)/22);}
    private Component name(O operation){return text("name."+operation.name().toLowerCase(Locale.ROOT));}
    protected final Component text(String key){return Component.translatable(translationPrefix()+key);}
    private CreateTextButton button(int x,int y,int width,Component label,Runnable action){
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,20,label,action,true));
    }
    @Override protected void renderBg(GuiGraphics graphics,float partialTick,int mouseX,int mouseY){
        PatternGuiSkin.blit(graphics,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(graphics,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        graphics.drawString(font,text("title"),leftPos+8,topPos+10,0xffeeeeee,false);
        graphics.drawWordWrap(font,hint(),leftPos+8,topPos+58+rows()*22,imageWidth-16,0xffcccccc);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY) {}
    @Override public void onClose(){close.run();}
    @Override public void removed() {}
}
