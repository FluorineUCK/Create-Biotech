package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.*;

/** Physical, menu-authorized instrument console. Editing the draft never arms hardware. */
final class ResidentInstrumentScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerCommunicationScreen parent;
    private String model="",draft=""; private List<String> sio=List.of(),xbus=List.of();
    private RecordValue state=new RecordValue(Map.of());
    private final List<FormattedCharSequence> lines=new ArrayList<>();
    private final List<CreateTextButton> actions=new ArrayList<>();
    private EditBox input;private CreateTextButton pin,mode,channel,prev,next,refresh;
    private int channelId,modeId,pinId,page; private boolean pending,requested;
    private long response;private Component notice=Component.empty();
    private static final List<String> MODES=List.of("ready","rising","falling");
    ResidentInstrumentScreen(ComputerProgramMenu menu,Inventory inv,Component title,ComputerCommunicationScreen parent){
        super(menu,inv,title);this.parent=parent;response=menu.controlResponseVersion();
    }
    private static Component tr(String key,Object... args){return Component.translatable("create_biotech.biocomputing.instrument."+key,args);}
    private CreateTextButton button(int x,int y,int w,String label,Runnable run){
        return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,tr(label),run,true));
    }
    @Override protected void init(){
        setWindowSize(Math.min(520,width-12),Math.min(340,height-12));super.init();clearWidgets();actions.clear();setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.HARDWARE));
        int third=(imageWidth-24)/3;
        channel=button(8,31,third,"channel",()->{channelId=1-channelId;update();});
        mode=button(12+third,31,third,"mode",()->{modeId=(modeId+1)%3;pinId=0;update();});
        pin=button(16+2*third,31,third,"pin",()->{pinId++;update();});
        input=addRenderableWidget(new EditBox(font,leftPos+8,topPos+70,imageWidth-16,18,tr("input")));
        input.setMaxLength(512);input.setValue(draft);input.setResponder(v->draft=v);
        int fourth=(imageWidth-28)/4;
        for(int i=0;i<4;i++){final int ix=i;actions.add(button(8+i*(fourth+4),92,fourth,"apply",()->apply(ix)));}
        prev=button(8,imageHeight-27,32,"prev",()->{page--;update();});next=button(44,imageHeight-27,32,"next",()->{page++;update();});
        refresh=button(82,imageHeight-27,72,"refresh",this::read);button(imageWidth-76,imageHeight-27,68,"back",this::onClose);update();
    }
    private boolean fisher(){return model.equals("fisherman");}
    private boolean sequencer(){return model.equals("vindicator");}
    private boolean scope(){return model.equals("illusioner");}
    private List<String> pins(){return fisher()&&modeId==0?xbus:sio;}
    private String selected(){var p=pins();return p.isEmpty()?"":p.get(Math.floorMod(pinId,p.size()));}
    private List<String> ops(){
        if(fisher())return List.of("bind","clear");
        if(sequencer())return List.of("start","stop");
        return scope()?List.of("start","freeze","resume","clear"):List.of();
    }
    private void read(){send(READ_INSTRUMENT,new RecordValue(Map.of()));}
    private void send(ComputerControlSession.Action action,RecordValue value){
        pending=true;notice=tr("waiting");menu.control(action,value);update();
    }
    private void apply(int i){
        var op=ops().get(i);var fields=new HashMap<String,DataValue>();fields.put("op",new TextValue(op));
        try{
            if(fisher()){
                fields.put("channel",new IntegerValue(channelId));
                if(op.equals("bind")){fields.put("mode",new TextValue(MODES.get(modeId)));fields.put("pin",new TextValue(selected()));fields.put("entry",new TextValue(draft.strip()));}
            }else if(sequencer()&&op.equals("start")){
                fields.put("pin",new TextValue(selected()));var segments=new ArrayList<DataValue>();
                for(String segment:draft.split(",",-1)){
                    var pair=segment.strip().split(":",-1);if(pair.length!=2)throw new IllegalArgumentException();
                    segments.add(new RecordValue(Map.of("level",new IntegerValue(Long.parseLong(pair[0].strip())),"ticks",new IntegerValue(Long.parseLong(pair[1].strip())))));
                }
                fields.put("segments",new ListValue(segments));
            }else if(scope()&&op.equals("start")){
                var names=draft.isBlank()?sio.stream().limit(4).toList():Arrays.stream(draft.split(",",-1)).map(String::strip).toList();
                fields.put("pins",new ListValue(names.stream().<DataValue>map(TextValue::new).toList()));
            }
            send(CONFIGURE_INSTRUMENT,new RecordValue(fields));
        }catch(IllegalArgumentException e){notice=tr("bad_input");update();}
    }
    private static List<String> names(RecordValue v,String field){return ((ListValue)v.field(field)).values().stream().map(x->((TextValue)x).value()).toList();}
    @Override protected void containerTick(){
        super.containerTick();if(!requested){requested=true;read();}
        if(response==menu.controlResponseVersion())return;response=menu.controlResponseVersion();pending=false;
        var view=menu.instrumentStatus();if(view.isPresent()){
            var v=view.get();var newModel=((TextValue)v.field("instrument")).value();
            if(!newModel.equals(model)){draft="";input.setValue("");pinId=0;modeId=0;page=0;}
            model=newModel;sio=names(v,"sio");xbus=names(v,"xbus");state=(RecordValue)v.field("details");notice=tr("snapshot");
        }else{notice=menu.controlResponse().map(r->tr("error",r.code(),r.detail())).orElse(tr("bad_input"));model="";}
        update();
    }
    private static String plain(RecordValue record,String field) {
        var v=record.fields().get(field);return v instanceof TextValue t?t.value():v==null?"—":value(v);
    }
    private static Component meaning(String state) {
        String key="create_biotech.biocomputing.instrument.status."+state;
        return net.minecraft.locale.Language.getInstance().has(key)?Component.translatable(key):Component.literal(state);
    }
    private int rows(){return Math.max(1,(imageHeight-167)/12);}
    private void line(Component text){lines.addAll(font.split(text,imageWidth-16));}
    private static String value(DataValue v){return String.join("",ComputerValueDisplay.lines(v,160));}
    private void update(){
        channel.visible=mode.visible=fisher();pin.visible=fisher()||sequencer();
        channel.active=mode.active=pin.active=!pending;
        channel.setMessage(tr("channel",channelId+1));mode.setMessage(tr(MODES.get(modeId)));pin.setMessage(tr("pin",selected()));
        input.visible=fisher()||sequencer()||scope();var ops=ops();
        for(int i=0;i<4;i++){var a=actions.get(i);a.visible=i<ops.size();a.active=a.visible&&!pending;
            if(a.visible)a.setMessage(tr(ops.get(i)));}
        lines.clear();
        if(scope() && state.fields().get("edges") instanceof ListValue edges){
            line(tr("trace_state",meaning(plain(state,"state")),value(state.field("pins")),edges.values().size()));
            for(var item:edges.values()){var e=(RecordValue)item;line(tr("edge",value(e.field("tick")),value(e.field("pin")),value(e.field("from")),value(e.field("to"))));}
        }else if(fisher()){
            for(int i=0;i<2;i++)if(state.fields().get("ch"+i) instanceof RecordValue ch){
                line(tr("channel_state",i+1,meaning(plain(ch,"status"))));
                if(ch.fields().containsKey("entry"))line(tr("binding",tr(plain(ch,"mode")),plain(ch,"pin"),plain(ch,"entry")));
            }
        }else if(sequencer()){
            line(tr("wave_state",meaning(plain(state,"state")),plain(state,"pin")));
            if(state.fields().get("segments") instanceof ListValue steps && !steps.values().isEmpty()){
                line(tr("wave_position",((IntegerValue)state.field("index")).value()+1,steps.values().size(),plain(state,"remaining")));
                for(var v:steps.values())if(v instanceof RecordValue s)line(tr("segment",plain(s,"level"),plain(s,"ticks")));
            }
        }else if(!pending)line(tr("no_device"));
        page=Math.clamp(page,0,Math.max(0,(lines.size()-1)/rows()));prev.active=page>0;next.active=(page+1)*rows()<lines.size();refresh.active=!pending;
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my){
        PatternGuiSkin.blit(g,PatternGuiSkin.SURFACE,leftPos,topPos,imageWidth,imageHeight);
        PatternGuiSkin.blit(g,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);
        g.drawString(font,font.plainSubstrByWidth(tr("title").getString(),imageWidth-90),leftPos+10,topPos+10,0xffeeeeee,false);
        if(fisher()||scope()||sequencer())g.drawString(font,font.plainSubstrByWidth(tr("hint."+model).getString(),imageWidth-16),leftPos+8,topPos+56,0xffbbddff,false);
        g.drawString(font,font.plainSubstrByWidth(notice.getString(),imageWidth-16),leftPos+8,topPos+117,0xffffdba0,false);
        for(int i=0;i<rows()&&page*rows()+i<lines.size();i++)g.drawString(font,lines.get(page*rows()+i),leftPos+8,topPos+132+i*12,0xffeeeeee,false);
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my){}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void removed(){}
}
