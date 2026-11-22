package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;

/** Previewing a crystal preset or input face is local until its own Apply button is pressed. */
public final class ComputerRadioScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerCommunicationScreen parent;
    private RadioControlData.View view;
    private RadioControlData.CarrierInput face = new RadioControlData.CarrierInput(Optional.empty());
    private RadioControlData.CarrierInput receive = new RadioControlData.CarrierInput(Optional.empty());
    private CreateTextButton rxFace, rxApply;
    private WitchReceiverFilter.Setting witch = WitchReceiverFilter.Setting.OFF;
    private int selected = -1, page;
    private long responseVersion;
    private boolean requested, pending;
    private Component notice = Component.empty();
    private CreateTextButton lower, higher, arm, closePort, selectFace, connect, prev, next, refresh, stage;
    private List<FormattedCharSequence> lines = List.of();

    ComputerRadioScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerCommunicationScreen parent) {
        super(menu,inventory,title); this.parent=Objects.requireNonNull(parent);responseVersion=menu.controlResponseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(540,width-12),Math.min(320,height-12));super.init();clearWidgets();setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 186, topPos + 5, 68, 20, ManualFile.RADIO));
        stage=button(imageWidth-114,5,106,"stage.open",()->minecraft.setScreen(
            new WitchCarrierScreen(menu,minecraft.player.getInventory(),title,this,view)));
        lower=button(8,31,28,"lower",()->choose(-1));higher=button(imageWidth-36,31,28,"higher",()->choose(1));
        int half=(imageWidth-20)/2;
        arm=button(8,56,half,"arm",()->{
            if (isWitch()) { witch=new WitchReceiverFilter.Setting(witch.output(),witch.width().next()); update(); }
            else apply(true);
        });closePort=button(12+half,56,half,"disarm",()->apply(false));
        selectFace=button(8,81,half,"carrier_select",()->{
            if (isWitch()) witch=new WitchReceiverFilter.Setting(new RadioControlData.CarrierInput(witch.output()).next().face(),witch.width());
            else face=face.next();
            update();
        });
        connect=button(12+half,81,half,"carrier_apply",this::applyCarrier);
        rxFace=button(8,105,half,"rx.internal",()->{receive=receive.next();update();});
        rxApply=button(12+half,105,half,"rx.apply",()->{
            pending=true;notice=text("waiting");update();menu.connectReceiveLoop(view.revision(),receive);
        });
        prev=button(8,imageHeight-27,32,"previous",()->{page--;update();});
        next=button(44,imageHeight-27,32,"next",()->{page++;update();});
        refresh=button(80,imageHeight-27,70,"refresh",this::read);
        button(imageWidth-78,imageHeight-27,70,"back",this::onClose);
        update();
    }
    private CreateTextButton button(int x,int y,int w,String key,Runnable run) {
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,text(key),run,true));
    }
    private void read() {
        pending=true; notice=text("waiting");update();
        menu.control(ComputerControlSession.Action.READ_RADIO,new DataValue.RecordValue(Map.of()));
    }
    private void choose(int delta) {
        if (pending || view==null || !view.selectable(selected+delta)) return;
        selected+=delta;update();
    }
    private void apply(boolean enabled) {
        if (!canApply()) return;
        pending=true;notice=text("waiting");update();
        menu.tuneRadio(view.revision(), new RadioControlData.Tune(selected,enabled));
    }
    private boolean canApply() {
        return !pending && view!=null && view.selectable(selected) && !view.diagnostic().equals("invalid_radio_save");
    }
    private void applyCarrier() {
        if (!connect.active) return;
        pending=true;notice=text("waiting");update();
        if (isWitch()) menu.configureWitchFilter(view.revision(),witch);
        else menu.connectCarrier(view.revision(), face);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested=true;read(); }
        if (responseVersion==menu.controlResponseVersion()) return;
        responseVersion=menu.controlResponseVersion();pending=false;page=0;
        var reply=menu.controlResponse();
        try {
            if (reply.isEmpty() || !reply.orElseThrow().code().equals("ok")) {
                view=null;selected=-1;
                notice=reply.map(r->text("rejected",code(r.code()),code(r.detail()))).orElse(text("invalid"));
            } else {
                var fresh=RadioControlData.View.decode(menu.radioStatus().orElseThrow());
                if (view==null || fresh.revision()!=view.revision() || !fresh.selectable(selected)) selected=fresh.selectable(fresh.preset())?fresh.preset():-1;
                if (view==null || fresh.revision()!=view.revision() || fresh.carrier().isPresent()!=view.carrier().isPresent())
                    face=new RadioControlData.CarrierInput(fresh.carrier().flatMap(CarrierPortStatus::input));
                if (view==null || fresh.revision()!=view.revision() || !fresh.witch().equals(view.witch()))
                    witch=fresh.witch().orElse(WitchReceiverFilter.Setting.OFF);
                if(view==null || fresh.revision()!=view.revision()) receive=new RadioControlData.CarrierInput(fresh.receiveSend());
                view=fresh;notice=text("snapshot");
            }
        } catch (IllegalArgumentException | NoSuchElementException invalid) { view=null;selected=-1;notice=text("invalid"); }
        update();
    }
    private boolean isWitch(){ return view!=null && view.witch().isPresent(); }
    private Component witchFace(WitchReceiverFilter.Setting setting) {
        return setting.output().isPresent() ? sourceName(setting.output()) : text("witch.off");
    }
    private int rows(){return Math.max(1,(imageHeight-141-(rxFace.visible?24:0))/12);}
    private void update() {
        stage.visible=view!=null && view.stage().isPresent();
        stage.active=stage.visible && !pending;
        lower.active=!pending && view!=null && view.selectable(selected-1);
        higher.active=!pending && view!=null && view.selectable(selected+1);
        arm.active=canApply() && (!view.armed() || selected!=view.preset());
        closePort.active=canApply() && (view.armed() || selected!=view.preset());
        var observed = view==null ? Optional.<CarrierPortStatus>empty() : view.carrier();
        selectFace.active=!pending && observed.isPresent() && !view.diagnostic().equals("invalid_radio_save");
        selectFace.setMessage(observed.isEmpty() ? text("carrier_select") : text("carrier_preview",sourceName(face.face())));
        connect.active=selectFace.active && !observed.orElseThrow().input().equals(face.face());
        lower.visible=higher.visible=closePort.visible=!isWitch();
        arm.setMessage(text("arm")); connect.setMessage(text("carrier_apply"));
        if (isWitch()) {
            arm.active=selectFace.active=!pending && !view.witchLink().equals("invalid");
            arm.setMessage(text("witch.width",text("witch."+witch.width().id())));
            selectFace.setMessage(text("witch.output",witchFace(witch)));
            connect.active=arm.active && (!view.witch().orElseThrow().equals(witch) || view.witchLink().equals("rewire"));
            connect.setMessage(text("witch.apply"));
        }
        rxFace.visible=rxApply.visible=view!=null && view.bands().isPresent();
        rxFace.active=rxFace.visible && !pending && !view.diagnostic().equals("invalid_radio_save");
        rxApply.active=rxFace.active && !view.receiveSend().equals(receive.face());
        rxFace.setMessage(receive.face().isEmpty()?text("rx.internal"):
            text("rx.faces",sourceName(receive.face()),sourceName(receive.face().map(net.minecraft.core.Direction::getOpposite))));
        refresh.active=!pending;
        var out=new ArrayList<FormattedCharSequence>();
        if (view!=null) {
            if (isWitch()) {
                var applied=view.witch().orElseThrow();
                add(out,text("witch.applied",witchFace(applied),text("witch."+applied.width().id()),text("witch."+view.witchLink())));
                if (applied.output().isPresent()) add(out,text("rx.filter_faces",sourceName(applied.output().map(net.minecraft.core.Direction::getOpposite)),sourceName(applied.output())));
                add(out,text("witch.help"));
            } else if (view.bands().isEmpty()) add(out,text("missing"));
            else {
                var osc=view.bands().orElseThrow();
                add(out,text("current",view.armed()?text("on"):text("off"),view.preset()+1L,osc.presets(),
                    view.selectable(view.preset()) ? String.valueOf(osc.emission(view.preset()).center()) : "—"));
                observed.ifPresent(port -> {
                    var output = port.output();
                    add(out,text("carrier_applied", sourceName(port.input()),
                        text("carrier_state."+output.state().name().toLowerCase(Locale.ROOT)),
                        output.frequency().<Component>map(f->Component.literal(f.toString())).orElseGet(()->text("carrier_none"))));
                });
                add(out,view.receiveSend().isEmpty()?text("rx.internal"):text("rx.faces",sourceName(view.receiveSend()),sourceName(view.receiveSend().map(net.minecraft.core.Direction::getOpposite))));
                add(out,text("widths",osc.transmitWidth(),view.effectiveWidth()));
                if (view.witchLink().equals("connected") && view.effectiveWidth() < osc.transmitWidth()) add(out,text("rx.clipped"));
                if (!view.witchLink().equals("off")) add(out,text("witch.receiver",text("witch."+view.witchLink())));
                add(out,text("transfer",code(view.transmit()),code(view.reception()),view.frames()));
                add(out,view.reception().equals("idle") ? text("unobserved")
                    : text("signals",view.wanted(),view.interference()));
            }
            if (!view.diagnostic().isBlank()) add(out,text("fault",code(view.diagnostic())));
        }
        add(out,notice);lines=List.copyOf(out);
        page=Math.clamp(page,0,Math.max(0,(lines.size()-1)/rows()));prev.active=page>0;next.active=(page+1)*rows()<lines.size();
    }
    private void add(List<FormattedCharSequence> dest,Component line) { dest.addAll(font.split(line,imageWidth-16)); }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,font.plainSubstrByWidth(text(isWitch()?"witch.title":"title").getString(),Math.max(0,imageWidth-202)),leftPos+10,topPos+10,0xffeeeeee,false);
        var label=isWitch()?text("witch.preview"):view==null || !view.selectable(selected)?text("no_selection"):
            text("preview",selected+1,String.valueOf(view.bands().orElseThrow().emission(selected).center()));
        g.drawCenteredString(font,font.plainSubstrByWidth(label.getString(),imageWidth-84),leftPos+imageWidth/2,topPos+37,0xffaaccee);
        for(int i=0;i<rows() && page*rows()+i<lines.size();i++)
            g.drawString(font,lines.get(page*rows()+i),leftPos+8,topPos+109+(rxFace.visible?24:0)+12*i,0xffdddddd,false);
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void removed(){}
    private static Component text(String key,Object... args){return Component.translatable("create_biotech.biocomputing.radio."+key,args);}
    private static Component sourceName(Optional<net.minecraft.core.Direction> face) {
        return text("carrier_input."+CarrierPortStatus.inputName(face));
    }
    static Component code(String code) {
        if (code.startsWith("carrier_")) {
            String key="create_biotech.biocomputing.radio.carrier_state."+code.substring("carrier_".length());
            if (Language.getInstance().has(key)) return Component.translatable(key);
        }
        String k="create_biotech.biocomputing.radio.code."+code;
        return Language.getInstance().has(k)?Component.translatable(k):Component.literal(BrowserLiteralDisplay.quote(code));
    }
}
