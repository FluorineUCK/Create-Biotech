package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A local preview. Only Apply sends the complete mode, faces and factor to the server. */
final class WitchCarrierScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final List<Direction> FACES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH,
        Direction.WEST, Direction.UP, Direction.DOWN);
    private final ComputerRadioScreen parent;
    private RadioControlData.View view;
    private WitchCarrierStage.Mode mode;
    private Direction input, output;
    private String factorText;
    private EditBox factor;
    private CreateTextButton operation, inputFace, outputFace, apply, refresh;
    private Component notice = Component.empty();
    private boolean pending;
    private long responseVersion;

    WitchCarrierScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
            ComputerRadioScreen parent, RadioControlData.View view) {
        super(menu,inventory,title);
        this.parent = parent;
        responseVersion = menu.controlResponseVersion();
        seed(view);
    }

    private void seed(RadioControlData.View fresh) {
        var applied = fresh.stage().orElseThrow().applied();
        view = fresh;
        mode = applied.mode();
        input = applied.input().orElse(Direction.NORTH);
        output = applied.output().orElse(Direction.SOUTH);
        factorText = Integer.toString(applied.factor());
    }

    @Override protected void init() {
        setWindowSize(Math.min(440,width-12),Math.min(244,height-12));
        super.init();
        clearWidgets();
        setFocused(null);
        int half = (imageWidth-20)/2;
        operation = button(8,34,half,"mode",()->{
            mode = switch (mode) {
                case OFF -> WitchCarrierStage.Mode.MULTIPLY;
                case MULTIPLY -> WitchCarrierStage.Mode.DIVIDE;
                case DIVIDE -> WitchCarrierStage.Mode.OFF;
            };
            factor.setValue(Integer.toString(view.stage().orElseThrow().suggestedFactor(mode)));
            update();
        });
        int factorWidth = Math.min(66, half/2);
        factor = addRenderableWidget(new EditBox(font,leftPos+imageWidth-8-factorWidth,topPos+35,factorWidth,18,text("factor")));
        factor.setMaxLength(10);
        factor.setFilter(s -> s.matches("[0-9]*"));
        factor.setValue(factorText);
        factor.setResponder(s -> { factorText=s; update(); });
        inputFace = button(8,59,half,"input",()->{input=next(input,output);update();});
        outputFace = button(12+half,59,half,"output",()->{output=next(output,input);update();});
        apply = button(8,imageHeight-28,half,"apply",this::submit);
        refresh = button(12+half,imageHeight-28,half/2-2,"refresh",()->{
            waitForReply();
            menu.control(ComputerControlSession.Action.READ_RADIO,new DataValue.RecordValue(Map.of()));
        });
        button(12+half+half/2,imageHeight-28,half-half/2,"back",this::onClose);
        update();
    }

    private CreateTextButton button(int x,int y,int w,String key,Runnable click) {
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,text(key),click,true));
    }
    private static Direction next(Direction current, Direction exclude) {
        int i = FACES.indexOf(current);
        do { i=(i+1)%FACES.size(); } while (FACES.get(i)==exclude);
        return FACES.get(i);
    }
    private Optional<WitchCarrierStage.Setting> selection() {
        return view == null ? Optional.empty() : RadioControlData.stageSelection(mode,input,output,factorText,view.stage().orElseThrow().maxFactor());
    }
    private void submit() {
        if (!apply.active) return;
        selection().ifPresent(setting -> {
            waitForReply();
            menu.configureWitchCarrier(view.revision(),setting);
        });
    }
    private void waitForReply() {
        pending=true; notice=radio("waiting"); update();
    }
    private void update() {
        boolean editable = !pending && view!=null;
        boolean resetOnly = view!=null && view.stage().orElseThrow().state().equals("invalid");
        operation.active=editable && !resetOnly;
        operation.setMessage(text("mode",modeName(mode)));
        boolean enabled = operation.active && mode!=WitchCarrierStage.Mode.OFF;
        factor.setEditable(enabled);
        inputFace.active=outputFace.active=enabled;
        inputFace.setMessage(text("input",faceName(input)));
        outputFace.setMessage(text("output",faceName(output)));
        apply.active=editable && selection().filter(s -> resetOnly || !s.equals(view.stage().orElseThrow().applied())).isPresent();
        refresh.active=!pending;
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (responseVersion==menu.controlResponseVersion()) return;
        responseVersion=menu.controlResponseVersion();
        pending=false;
        try {
            var reply=menu.controlResponse().orElseThrow();
            if (!reply.code().equals("ok")) {
                view=null;
                notice=radio("rejected",ComputerRadioScreen.code(reply.code()),ComputerRadioScreen.code(reply.detail()));
            } else {
                seed(RadioControlData.View.decode(menu.radioStatus().orElseThrow()));
                factor.setValue(factorText);
                notice=radio("snapshot");
            }
        } catch (IllegalArgumentException | NoSuchElementException invalid) { view=null; notice=radio("invalid"); }
        update();
    }

    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,text("title"),leftPos+10,topPos+10,0xffeeeeee,false);
        g.drawString(font,text("factor"),leftPos+12+(imageWidth-20)/2,topPos+40,0xffeeeeee,false);
        int y = topPos+85;
        if (view!=null) {
            var stage=view.stage().orElseThrow();
            var applied=stage.applied();
            var summary = applied.mode()==WitchCarrierStage.Mode.OFF ? modeName(applied.mode())
                : text("summary",modeName(applied.mode()),applied.factor(),faceName(applied.input().orElseThrow()),faceName(applied.output().orElseThrow()));
            y=draw(g,text("applied",summary),y);
            var state=switch(stage.state()) {
                case "off" -> modeName(WitchCarrierStage.Mode.OFF);
                case "invalid" -> text("invalid");
                default -> radio("carrier_state."+stage.state());
            };
            y=draw(g,text("status",state,stage.frequency().isEmpty()?radio("carrier_none"):Component.literal(stage.frequency())),y);
            y=draw(g,text("range",stage.maxFactor()),y);
        }
        draw(g,notice,y);
    }
    private int draw(GuiGraphics g,Component value,int y) {
        for (var line:font.split(value,imageWidth-16)) {
            if (y+font.lineHeight>topPos+imageHeight-32) break;
            g.drawString(font,line,leftPos+8,y,0xffdddddd,false);
            y+=font.lineHeight+2;
        }
        return y;
    }
    private static Component faceName(Direction dir){return radio("carrier_input."+dir.getSerializedName());}
    private static Component modeName(WitchCarrierStage.Mode mode){return text("mode."+mode.id());}
    private static Component text(String key,Object... args){return radio("stage."+key,args);}
    private static Component radio(String key,Object... args){return Component.translatable("create_biotech.biocomputing.radio."+key,args);}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void removed(){}
}
