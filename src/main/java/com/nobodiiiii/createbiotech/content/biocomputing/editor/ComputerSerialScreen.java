package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Explicit physical-interface settings; browsing never installs a program or grants an old endpoint. */
public final class ComputerSerialScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerCommunicationScreen parent;
    private final Map<String, Direction> faces = new HashMap<>();
    private SerialDevice.View view;
    private String selected;
    private boolean requested, awaiting;
    private long responseVersion;
    private SerialControlData.Configuration submitted;
    private List<String> lines = List.of();
    private int page;
    private Component notice = Component.empty();
    private CreateTextButton portPrevious, portNext, face, enable, disable, previous, next;
    private CreateTextButton bindGateway, clearGateway;
    private CreateTextButton route, resetLatch;
    private DataValue.RecordValue breakpoint;
    private EditBox alias;
    private String aliasDraft = "";
    private boolean aliasEdited;
    private boolean graph, armed;
    private int socketIndex, socketCount;
    private SerialControlData.PanelSocket socket;
    private String socketState = "";
    private CreateTextButton sourceSocket, targetSocket, prevSocket, nextSocket;

    public ComputerSerialScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerCommunicationScreen parent) {
        super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); responseVersion = menu.controlResponseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.SERIAL));
        if (graph) { initGraph(); return; }
        portPrevious = button(8, 30, 32, "previous_port", () -> select(-1));
        portNext = button(44, 30, 32, "next_port", () -> select(1));
        face = button(8, 54, imageWidth - 16, "face", this::cycleFace);
        int column = (imageWidth - 20) / 2;
        route = button(12+column,54,column/2-2,"breakpoint_route",()->request(ComputerControlSession.Action.CONFIGURE_BREAKPOINT_OUTPUT,
            new DataValue.RecordValue(Map.of("pin",new DataValue.TextValue(nextRoute())))));
        resetLatch = button(14+column+column/2,54,column/2-2,"breakpoint_reset",()->request(ComputerControlSession.Action.RESET_BREAKPOINT,new DataValue.RecordValue(Map.of())));
        enable = button(8, 78, column, "enable", () -> configure(true));
        disable = button(12 + column, 78, column, "disable", () -> configure(false));
        alias = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 102, imageWidth - 16, 18, text("alias")));
        alias.setMaxLength(com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.MAX_NAME + 1);
        aliasDraft = aliasDraft == null ? "" : aliasDraft;
        alias.setValue(aliasDraft); alias.setHint(text("alias"));
        alias.setResponder(value -> { aliasDraft = value; aliasEdited = true; refresh(); });
        bindGateway = button(8, 126, column, "bind_gateway", this::openGraph);
        clearGateway = button(12 + column, 126, column, "clear_gateway", () -> gateway(false));
        previous = button(8, imageHeight - 27, 32, "previous", () -> { page--; refresh(); });
        next = button(44, imageHeight - 27, 32, "next", () -> { page++; refresh(); });
        button(84, imageHeight - 27, 84, "refresh", () -> request(ComputerControlSession.Action.READ_SERIAL, new DataValue.RecordValue(Map.of())));
        button(imageWidth - 76, imageHeight - 27, 68, "back", this::onClose);
        refresh();
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, text(key), action, true));
    }
    private List<String> ports() { return view == null ? List.of() : List.copyOf(view.ports().keySet()); }
    private String nextRoute() {
        String bus=((DataValue.TextValue)breakpoint.field("xbus")).value();
        String current=((DataValue.TextValue)breakpoint.field("pin")).value();
        if (selected == null) return bus;
        return selected.equals(current) && !bus.isEmpty() ? bus : selected;
    }
    private void select(int delta) {
        var names = ports(); int index = (selected == null ? -1 : names.indexOf(selected)) + delta;
        if (index >= 0 && index < names.size()) { selected = names.get(index); seedAlias(); page = 0; refresh(); }
    }
    private void seedAlias() {
        alias.setValue(view == null || selected == null ? "" : view.ports().get(selected).gateway().map(g -> g.alias()).orElse(""));
        aliasEdited = false;
    }
    private Direction chosenFace() {
        if (view != null && selected != null && view.ports().containsKey(selected) && view.ports().get(selected).gateway().isPresent())
            return view.ports().get(selected).gateway().orElseThrow().side();
        if (faces.containsKey(selected)) return faces.get(selected);
        return view == null || selected == null ? null : view.ports().get(selected).side().orElse(null);
    }
    private void cycleFace() {
        if (selected == null || awaiting || view == null || view.diagnostic().isPresent() || view.ports().get(selected).gateway().isPresent()) return;
        var old = chosenFace(); var directions = Direction.values();
        faces.put(selected, directions[old == null ? 0 : (old.ordinal() + 1) % directions.length]); refresh();
    }
    private void configure(boolean enabled) {
        var side = chosenFace();
        if (selected == null || side == null || awaiting || view == null || view.diagnostic().isPresent()) return;
        submitted = new SerialControlData.Configuration(selected, side, enabled);
        request(ComputerControlSession.Action.CONFIGURE_SERIAL, submitted.encode());
    }
    private void request(ComputerControlSession.Action action, DataValue.RecordValue inputs) {
        if (action == ComputerControlSession.Action.READ_SERIAL) submitted = null;
        awaiting = true; notice = Component.translatable("create_biotech.biocomputing.debug.waiting");
        menu.control(action, inputs); refresh();
    }
    private void openGraph() {
        if (aliasDraft.isBlank()) alias.setValue(selected);
        graph = true; armed = false; socket = null; socketCount = socketIndex = 0;
        init(); loadSocket(0);
    }
    private void initGraph() {
        int w=(imageWidth-60)/2;
        sourceSocket=button(8,56,w,"graph_target",()->{armed=!armed; refresh();});
        targetSocket=button(imageWidth-8-w,56,w,"graph_target",()->gateway(true));
        prevSocket=button(8,86,36,"previous",()->loadSocket(socketIndex-1));
        nextSocket=button(48,86,36,"next",()->loadSocket(socketIndex+1));
        button(88,86,76,"refresh",()->loadSocket(socketIndex));
        button(imageWidth-76,imageHeight-27,68,"back",this::closeGraph);
        refresh();
    }
    private void loadSocket(int index) {
        socket=null; socketState="";
        request(ComputerControlSession.Action.READ_PANEL_SOCKET,new DataValue.RecordValue(Map.of(
            "page",new DataValue.IntegerValue(index), "port",new DataValue.TextValue(selected))));
    }
    private void closeGraph() {
        graph=false; armed=false; socket=null; init(); request(ComputerControlSession.Action.READ_SERIAL,new DataValue.RecordValue(Map.of()));
    }
    private void refreshGraph() {
        sourceSocket.setMessage(text("graph_source",BrowserLiteralDisplay.quote(selected)));
        sourceSocket.active=!awaiting;
        targetSocket.setMessage(socket==null?text("graph_empty"):Component.literal(socket.pos().toShortString()).append(" · ").append(direction(socket.side())));
        targetSocket.active=!awaiting && armed && socket!=null && socketState.equals("available");
        prevSocket.active=!awaiting && socketIndex>0; nextSocket.active=!awaiting && socketIndex+1<socketCount;
    }
    private void gateway(boolean bind) {
        if (selected == null || awaiting || view == null || view.diagnostic().isPresent()) return;
        try {
            var value = bind ? new SerialControlData.GatewayConfiguration(selected, aliasDraft,Optional.of(Objects.requireNonNull(socket))).encode()
                : new DataValue.RecordValue(Map.of("port", new DataValue.TextValue(selected)));
            submitted = null; faces.remove(selected); aliasEdited = false;
            request(bind ? ComputerControlSession.Action.CONFIGURE_SERIAL_GATEWAY : ComputerControlSession.Action.CLEAR_SERIAL_GATEWAY, value);
        } catch (IllegalArgumentException invalid) { notice = text("invalid_alias"); refresh(); }
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; request(ComputerControlSession.Action.READ_SERIAL, new DataValue.RecordValue(Map.of())); }
        if (responseVersion == menu.controlResponseVersion()) return;
        responseVersion = menu.controlResponseVersion(); awaiting = false;
        var reply = menu.controlResponse();
        if(graph) {
            var data=menu.serialStatus();
            if (reply.isEmpty() || !reply.get().code().equals("ok") || data.isEmpty()) {
                notice=reply.map(r -> text("rejected",code(r.code()),code(r.detail()))).orElse(text("invalid"));
                socket=null; socketCount=socketIndex=0; refresh(); return;
            }
            var record=data.get();
            if(record.fields().containsKey("serial")) { graph=false; armed=false; init(); }
            else {
                socketIndex=(int)((DataValue.IntegerValue)record.field("page")).value();
                socketCount=(int)((DataValue.IntegerValue)record.field("count")).value();
                socket=socketCount==0?null:SerialControlData.PanelSocket.decode(record.field("panel"));
                socketState=socket==null?"":((DataValue.TextValue)record.field("state")).value();
                notice=socket==null?text("graph_empty"):socketState.equals("available")?text("graph_hint"):code(socketState);
                refresh(); return;
            }
        }
        view = null; breakpoint = null;
        try {
            if (reply.isEmpty() || !reply.orElseThrow().code().equals("ok")) {
                notice = reply.<Component>map(r -> text("rejected", BrowserLiteralDisplay.quote(r.code()), BrowserLiteralDisplay.quote(r.detail())))
                    .orElse(text("invalid"));
            } else {
                var data=menu.serialStatus().orElseThrow();
                if (data.fields().containsKey("breakpoint")) {
                    if (!data.fields().keySet().equals(Set.of("breakpoint", "serial"))
                            || !(data.fields().get("breakpoint") instanceof DataValue.RecordValue bp)
                            || !bp.fields().keySet().equals(Set.of("xbus","pin","state","outcome","preview"))
                            || !bp.fields().values().stream().allMatch(DataValue.TextValue.class::isInstance))
                        throw new IllegalArgumentException("Invalid breakpoint snapshot");
                    breakpoint = bp;
                    view = SerialControlData.decode(new DataValue.RecordValue(Map.of("serial",data.fields().get("serial"))));
                } else {
                    view = SerialControlData.decode(data);
                }
                if (submitted != null) faces.remove(submitted.port());
                if (selected == null || !view.ports().containsKey(selected)) selected = ports().stream().findFirst().orElse(null);
                if (!aliasEdited) seedAlias();
                notice = text("frozen");
            }
        } catch (IllegalArgumentException | NoSuchElementException invalid) { view = null; notice = text("invalid"); }
        submitted = null; page = 0; refresh();
    }
    private void refresh() {
        if(graph){refreshGraph(); return;}
        boolean show=breakpoint!=null && view!=null;
        route.visible=resetLatch.visible=show; route.active=show&&!awaiting&&!nextRoute().isEmpty()&&((DataValue.TextValue)breakpoint.field("state")).value().equals("armed");
        if(route.active)route.setMessage(text("breakpoint_route",nextRoute()));
        resetLatch.active=show&&!awaiting&&((DataValue.TextValue)breakpoint.field("state")).value().equals("halted");
        face.setWidth(show?(imageWidth-20)/2:imageWidth-16);
        var names = ports(); int index = selected == null ? -1 : names.indexOf(selected);
        portPrevious.active = !awaiting && index > 0;
        portNext.active = !awaiting && index >= 0 && index + 1 < names.size();
        boolean usable = !awaiting && index >= 0 && view.diagnostic().isEmpty();
        boolean mapped = usable && view.ports().get(selected).gateway().isPresent();
        face.active = usable && !mapped; enable.active = disable.active = usable && chosenFace() != null;
        alias.setEditable(usable && menu.hasPanelAccess()); clearGateway.active = mapped;
        bindGateway.active = false;
        if (usable && menu.hasPanelAccess()) try { new SerialControlData.GatewayConfiguration(selected, aliasDraft.isEmpty()?selected:aliasDraft); bindGateway.active = true; } catch (IllegalArgumentException ignored) { }
        
        face.setMessage(text("face", chosenFace() == null ? text("choose_face") : direction(chosenFace())));
        var result = new ArrayList<String>();
        append(result, notice);
        if (view != null) {
            view.diagnostic().ifPresent(code -> append(result, text("diagnostic", code(code))));
            if (index < 0) {
                if (view.diagnostic().isEmpty()) append(result, text("no_hardware"));
            }
            else {
                var port = view.ports().get(selected); var status = port.status();
                append(result, text("port", BrowserLiteralDisplay.quote(selected)));
                append(result, text("saved", port.enabled() ? text("enabled") : text("disabled"), port.side().map(ComputerSerialScreen::direction).orElse(text("unbound"))));
                port.gateway().ifPresent(g -> append(result, text("gateway", BrowserLiteralDisplay.quote(g.alias()),
                    g.position().getX(), g.position().getY(), g.position().getZ(), direction(g.side()))));
                append(result, text("connection", code(status.connection())));
                append(result, text("transmit", code(status.transmit())));
                append(result, text("buffer", status.receive().frames(), status.receive().bytes()));
                if (!status.failure().isEmpty()) append(result, text("failure", code(status.failure())));
                for (var fault : status.receive().faults().stream().sorted().toList()) append(result, text("fault", code(fault.name().toLowerCase(Locale.ROOT))));
            }
            if (show) append(result,text("breakpoint_status",code(((DataValue.TextValue)breakpoint.field("state")).value()),
                ((DataValue.TextValue)breakpoint.field("pin")).value(),displayOutcome(((DataValue.TextValue)breakpoint.field("outcome")).value()),
                ((DataValue.TextValue)breakpoint.field("preview")).value()));
            append(result, text("boundary"));
            append(result, text("gateway_boundary"));
            append(result, text("indicator"));
        }
        lines = List.copyOf(result); var slice = ComputerValueDisplay.page(lines, page, pageSize()); page = slice.index();
        previous.active = page > 0; next.active = page + 1 < slice.count();
    }
    private void append(List<String> result, Component component) {
        String remaining = component.getString();
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, imageWidth - 16);
            if (!line.isEmpty() && Character.isHighSurrogate(line.charAt(line.length() - 1))) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
            result.add(line); remaining = remaining.substring(line.length());
        }
    }
    private int pageSize() { return Math.max(1, (imageHeight - 158 - 34) / 12); }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(text("title").getString(), imageWidth - 90), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        if(graph) {
            int w=(imageWidth-60)/2, x1=leftPos+8+w, x2=leftPos+imageWidth-8-w, y=topPos+66;
            var binding=view.ports().get(selected).gateway();
            boolean linked=socket!=null && binding.filter(b -> b.controller().equals(socket.id()) && b.position().equals(socket.pos()) && b.side()==socket.side()).isPresent();
            if(linked)graphics.hLine(x1,x2,y,0xff73bc9b);
            graphics.fill(x1-1,y-2,x1+4,y+3,armed?0xffffce68:0xffb0bdbc);
            graphics.fill(x2-4,y-2,x2+1,y+3,linked?0xff73bc9b:0xffb0bdbc);
            graphics.drawString(font,text("graph_header",socketCount==0?0:socketIndex+1,socketCount),leftPos+8,topPos+34,0xffeeeeee,false);
            graphics.drawWordWrap(font,notice,leftPos+8,topPos+114,imageWidth-16,0xffdddddd);
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(selected == null ? text("empty").getString() : BrowserLiteralDisplay.quote(selected), imageWidth - 92), leftPos + 84, topPos + 36, 0xffaaccee, false);
        var slice = ComputerValueDisplay.page(lines, page, pageSize());
        for (int row = 0; row < slice.lines().size(); row++) graphics.drawString(font, slice.lines().get(row), leftPos + 8, topPos + 158 + 12 * row, 0xffdddddd, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { if(graph){closeGraph();return;} minecraft.setScreen(parent); }
    @Override public void removed() {}
    private static Component direction(Direction side) { return Component.translatable("create_biotech.biocomputing.communication.face." + side.getName()); }
    private static Component displayOutcome(String value) {
        return value.isEmpty() ? Component.literal("—") : code(value);
    }
    private static Component code(String value) {
        String key = "create_biotech.biocomputing.serial.code." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(BrowserLiteralDisplay.quote(value));
    }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.serial." + key, args); }
}
