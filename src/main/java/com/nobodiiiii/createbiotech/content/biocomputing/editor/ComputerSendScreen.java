package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local editor for the root event's explicit, ordered send effects. */
public final class ComputerSendScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerProgramScreen parent;
    private ProgramDraft draft;
    private Runnable changed;
    private Runnable close;
    private String activeEntry;
    private Supplier<List<String>> entryNames;
    private EditBox target, payload, slot, replyEntry, captureOutput, captureInput, conditionOutput;
    private String targetText = "", payloadText = "", slotText = "", replyEntryText = "";
    private ProgramDefinition.Dispatch dispatch = ProgramDefinition.Dispatch.MESSAGE;
    private String captureOutputText = "", captureInputText = "";
    private boolean requestOptions, continuationOptions, captureEnabled;
    private String conditionText = "";
    private boolean conditionOptions, conditionEnabled;
    private int selected = -1, page;
    private Component notice = Component.empty();

    public ComputerSendScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerProgramScreen parent,
        ProgramDraft draft, Runnable changed) {
        this(menu, inventory, title, parent, draft, changed, null, List::of);
    }
    public ComputerSendScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerProgramScreen parent,
        ProgramDraft draft, Runnable changed, String activeEntry, Supplier<List<String>> entryNames) {
        super(menu, inventory, title); this.parent=Objects.requireNonNull(parent); this.draft=Objects.requireNonNull(draft);
        this.changed=Objects.requireNonNull(changed); this.activeEntry=activeEntry; this.entryNames=Objects.requireNonNull(entryNames);
        this.close=()->minecraft.setScreen(parent);
    }

    @Override protected void init() {
        setWindowSize(Math.min(580,width-12),Math.min(380,height-12)); super.init(); setFocused(null); clearWidgets();
        target=payload=slot=replyEntry=captureOutput=captureInput=conditionOutput=null;
        int modes=activeEntry!=null?3:2, modeWidth=(imageWidth-16-(modes-1)*4)/modes;
        mode(0,ProgramDefinition.Dispatch.MESSAGE,modeWidth);mode(1,ProgramDefinition.Dispatch.REQUEST,modeWidth);
        // Named callbacks may acquire a return route via explicit continuation. Installation
        // validates reachability; a draft is allowed to configure its callback before its caller.
        if(activeEntry!=null)mode(2,ProgramDefinition.Dispatch.REPLY,modeWidth);
        if(dispatch==ProgramDefinition.Dispatch.REQUEST){int tabWidth=(imageWidth-28)/4;
            button(8,58,tabWidth,"bindings",()->{requestOptions=false;continuationOptions=false;conditionOptions=false;init();});
            button(12+tabWidth,58,tabWidth,"request_options",()->{requestOptions=true;continuationOptions=false;conditionOptions=false;init();});
            button(16+tabWidth*2,58,tabWidth,"continuation",()->{requestOptions=false;continuationOptions=true;conditionOptions=false;init();});
            button(20+tabWidth*3,58,tabWidth,"condition",()->{conditionOptions=true;init();});
        }else {int tabWidth=(imageWidth-20)/2;
            button(8,58,tabWidth,"bindings",()->{conditionOptions=false;init();});
            button(12+tabWidth,58,tabWidth,"condition",()->{conditionOptions=true;init();});}
        int fieldWidth=imageWidth-54;
        if(conditionOptions){
            button(8,82,88,conditionEnabled?"condition_enabled":"condition_disabled",()->{conditionEnabled=!conditionEnabled;init();});
            conditionOutput=field(100,82,imageWidth-146,"condition_output",conditionText,value->conditionText=value);
            conditionOutput.active=conditionEnabled;
            button(imageWidth-42,82,34,"next_output",this::cycleCondition);
        }else if(dispatch==ProgramDefinition.Dispatch.REQUEST&&continuationOptions){
            button(8,82,72,captureEnabled?"capture_enabled":"capture_disabled",()->{captureEnabled=!captureEnabled;init();});
            captureOutput=field(84,82,imageWidth-130,"capture_output",captureOutputText,value->captureOutputText=value);
            button(imageWidth-42,82,34,"next_output",this::cycleCapture);
            captureInput=field(8,106,imageWidth-16,"capture_input",captureInputText,value->captureInputText=value);
            captureOutput.active=captureInput.active=captureEnabled;
        }else if(dispatch==ProgramDefinition.Dispatch.REQUEST&&requestOptions){
            slot=field(8,82,imageWidth-16,"slot",slotText,value->slotText=value);
            replyEntry=field(8,106,fieldWidth,"reply_entry",replyEntryText,value->replyEntryText=value);button(imageWidth-42,106,34,"next_entry",this::cycleEntry);
        }else{
            if(dispatch!=ProgramDefinition.Dispatch.REPLY){target=field(8,82,fieldWidth,"target_output",targetText,value->targetText=value);button(imageWidth-42,82,34,"next_output",()->cycle(true));}
            payload=field(8,106,fieldWidth,"payload_output",payloadText,value->payloadText=value);button(imageWidth-42,106,34,"next_output",()->cycle(false));
        }
        int column=(imageWidth-24)/3;
        button(8,130,column,"create",this::create);
        button(12+column,130,column,"update",this::update).active=validSelection();
        button(16+column*2,130,column,"delete",this::delete).active=validSelection();
        int rows=rows(); page=Math.clamp(page,0,Math.max(0,(draft.sends().size()-1)/rows));
        for(int row=0;row<rows;row++){
            int index=page*rows+row;if(index>=draft.sends().size())break;
            var send=draft.sends().get(index);
            button(8,176+row*22,imageWidth-16,summary(index,send),()->select(index));
        }
        int bottom=imageHeight-27;
        button(8,bottom,32,"previous",()->{page--;init();}).active=page>0;
        button(44,bottom,32,"next",()->{page++;init();}).active=(page+1)*rows<draft.sends().size();
        button(imageWidth-76,bottom,68,"back",this::onClose);
    }
    private void mode(int index, ProgramDefinition.Dispatch value, int width) {
        button(8 + index * (width + 4), 34, width, "mode_" + value.name().toLowerCase(Locale.ROOT), () -> {
            dispatch = value;
            requestOptions = false;
            continuationOptions = false;
            conditionOptions = false;
            if (value != ProgramDefinition.Dispatch.REQUEST) captureEnabled = false;
            if (value == ProgramDefinition.Dispatch.REPLY) {
                targetText = "";
                slotText = "";
                replyEntryText = "";
            } else if (value == ProgramDefinition.Dispatch.MESSAGE) {
                slotText = "";
                replyEntryText = "";
            }
            init();
        });
    }
    private Component summary(int index, ProgramDefinition.Send send) {
        var summary = send.dispatch() == ProgramDefinition.Dispatch.MESSAGE
            ? Component.translatable("create_biotech.biocomputing.send.binding", index + 1,
                BrowserLiteralDisplay.quote(send.endpointOutput()), BrowserLiteralDisplay.quote(send.payloadOutput()))
            : Component.translatable("create_biotech.biocomputing.send.binding_" + send.dispatch().name().toLowerCase(Locale.ROOT),
            index + 1, BrowserLiteralDisplay.quote(send.endpointOutput()), BrowserLiteralDisplay.quote(send.payloadOutput()),
            BrowserLiteralDisplay.quote(send.requestSlot()), BrowserLiteralDisplay.quote(send.replyEntry()));
        send.continuation().ifPresent(binding -> summary.append(text("capture_summary",
            BrowserLiteralDisplay.quote(binding.captureOutput()), BrowserLiteralDisplay.quote(binding.captureInput()))));
        send.conditionOutput().ifPresent(output -> summary.append(text("condition_summary", BrowserLiteralDisplay.quote(output))));
        return summary;
    }
    private EditBox field(int x,int y,int width,String key,String value,java.util.function.Consumer<String> responder){
        var box=addRenderableWidget(new EditBox(font,leftPos+x,topPos+y,width,18,text(key)));box.setMaxLength(ProgramEditorWire.MAX_BYTES);box.setValue(value);box.setHint(text(key));box.setResponder(responder);return box;
    }
    private CreateTextButton button(int x,int y,int width,String key,Runnable action){return button(x,y,width,text(key),action);}
    private CreateTextButton button(int x,int y,int width,Component label,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,20,label,action,true));}
    private int rows(){return Math.max(1,(imageHeight-176-32)/22);}
    private boolean validSelection(){return selected>=0&&selected<draft.sends().size();}
    private void select(int index) {
        selected = index;
        load(draft.sends().get(index));
        requestOptions = false;
        continuationOptions = false;
        conditionOptions = false;
        init();
    }
    private void load(ProgramDefinition.Send send) {
        dispatch = send.dispatch();
        targetText = send.endpointOutput();
        payloadText = send.payloadOutput();
        slotText = send.requestSlot();
        replyEntryText = send.replyEntry();
        captureEnabled = send.continuation().isPresent();
        captureOutputText = send.continuation().map(ProgramDefinition.Continuation::captureOutput).orElse("");
        captureInputText = send.continuation().map(ProgramDefinition.Continuation::captureInput).orElse("");
        conditionEnabled = send.conditionOutput().isPresent();
        conditionText = send.conditionOutput().orElse("");
    }
    private ProgramDefinition.Send edited() {
        if (dispatch == ProgramDefinition.Dispatch.REQUEST && !entryNames.get().contains(replyEntryText))
            throw new ComputationException(ComputationException.Code.INVALID_GRAPH, "send", "Unknown reply entry: " + replyEntryText);
        var send = switch (dispatch) {
            case MESSAGE -> new ProgramDefinition.Send(targetText, payloadText);
            case REQUEST -> captureEnabled
                ? ProgramDefinition.Send.continueRequest(targetText, payloadText, slotText, replyEntryText, captureOutputText, captureInputText)
                : ProgramDefinition.Send.request(targetText, payloadText, slotText, replyEntryText);
            case REPLY -> ProgramDefinition.Send.reply(payloadText);
        };
        return conditionEnabled ? send.when(conditionText) : send;
    }
    private void create(){
        try{draft.addSend(edited());}catch(ComputationException|IllegalArgumentException invalid){notice=text("invalid",String.valueOf(invalid.getMessage()));return;}
        selected=draft.sends().size()-1;page=selected/rows();changed.run();notice=text("saved");init();
    }
    private void update(){
        if(!validSelection())return;
        try{draft.setSend(selected,edited());}catch(ComputationException|IllegalArgumentException invalid){notice=text("invalid",String.valueOf(invalid.getMessage()));return;}
        changed.run();notice=text("saved");init();
    }
    private void delete(){if(!validSelection())return;draft.removeSend(selected);selected=Math.min(selected,draft.sends().size()-1);if(validSelection())load(draft.sends().get(selected));changed.run();notice=text("removed");init();}
    private void cycle(boolean endpoint){
        var names=List.copyOf(draft.outputs().keySet());if(names.isEmpty())return;String current=endpoint?targetText:payloadText;int index=names.indexOf(current);String next=names.get((index+1)%names.size());if(endpoint)target.setValue(next);else payload.setValue(next);
    }
    private void cycleEntry(){var names=List.copyOf(entryNames.get());if(names.isEmpty())return;replyEntry.setValue(names.get((names.indexOf(replyEntryText)+1)%names.size()));}
    private void cycleCapture(){if(!captureEnabled)return;var names=List.copyOf(draft.outputs().keySet());if(names.isEmpty())return;captureOutput.setValue(names.get((names.indexOf(captureOutputText)+1)%names.size()));}
    private void cycleCondition(){if(!conditionEnabled)return;var names=List.copyOf(draft.outputs().keySet());if(names.isEmpty())return;conditionOutput.setValue(names.get((names.indexOf(conditionText)+1)%names.size()));}
    @Override public void onClose(){close.run();}
    @Override public void removed(){}
    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        PatternGuiSkin.blit(graphics,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);PatternGuiSkin.blit(graphics,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        graphics.drawString(font,text("title"),leftPos+10,topPos+10,0xffeeeeee,false);
        if(conditionOptions)line(graphics,text("condition_value_hint"),106,0xffaaccee);
        line(graphics,text(conditionOptions?"condition_hint":continuationOptions?"capture_hint":dispatch==ProgramDefinition.Dispatch.REPLY?"reply_hint":"event_success_hint"),154,0xffaaccee);line(graphics,notice,164,0xffffddaa);
    }
    private void line(GuiGraphics graphics,Component value,int y,int color){graphics.drawString(font,font.plainSubstrByWidth(value.getString(),imageWidth-16),leftPos+8,topPos+y,color,false);}
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){}
    private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.send."+key,args);}
}
