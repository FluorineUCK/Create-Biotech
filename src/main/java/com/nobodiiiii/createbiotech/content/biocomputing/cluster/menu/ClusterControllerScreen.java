package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerWiringScreen;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.Label;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Create-native geometric member list and explicit name, port and connection configuration. */
public final class ClusterControllerScreen extends AbstractSimiContainerScreen<ClusterControllerMenu> {
    private enum Panel { MEMBERS, PORTS, CONNECTIONS, CANDIDATES, MEMBER, HARDWARE, PORT, CONNECTION, WIRE_GRAPH, WIRES, WIRE, SIGNALS, PUBLIC_PINS, PUBLIC_PIN, WIRING_SOURCES }
    private enum Pending { NONE, REFRESH, SAVE, OPEN, WIRING }
    private ClusterControlDraft draft = new ClusterControlDraft(ClusterConfiguration.empty());
    private ClusterControlView authority;
    private Panel panel = Panel.MEMBERS;
    private Component notice = Component.empty();
    private StringWidget noticeLabel;
    private boolean requested, seeded, dirty, waiting;
    private Pending pending = Pending.NONE;
    private long draftVersion, pendingDraftVersion, draftRevision;
    private long version;
    private int page, memberChoice, sourceChoice, targetChoice;
    private String selectedMember, selectedPort, selectedConnection, memberText = "", publicText = "", inputText = "", outputText = "", externalText = "";
    private ClusterControlView.Candidate selectedCandidate;
    private boolean enabled = true;
    private boolean signalWire;
    private String selectedWire, leftEndpoint = "", rightEndpoint = "";
    private String selectedPublicPin;
    private String requestedWiringSource;
    private CreateTextButton wiringButton;

    public ClusterControllerScreen(ClusterControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); version = menu.responseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets(); setFocused(null); wiringButton = null;
        button(8,30,82,"refresh",()->{if(panel==Panel.WIRING_SOURCES)showWiringSources(null);else refresh();}); button(94,30,82,"save",this::save).active=dirty&&seeded&&!waiting;
        addRenderableWidget(new CreateTextButton(font, leftPos + 180, topPos + 30, 64, 20,
            com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventScreen.title(), () -> minecraft.setScreen(
                new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventScreen(menu, minecraft.player.getInventory(), () -> minecraft.setScreen(this))), true));
        noticeLabel = addRenderableWidget(new StringWidget(leftPos + 248, topPos + 30, Math.max(1, imageWidth - 256), 20,
            notice, font).alignLeft().setColor(0xffffddaa));
        noticeLabel.setTooltip(Tooltip.create(notice));
        int tab = (imageWidth - 36) / 6;
        button(8,54,tab,"members",()->open(Panel.MEMBERS)); button(12+tab,54,tab,"ports",()->open(Panel.PORTS));
        button(16+2*tab,54,tab,"connections",()->open(Panel.CONNECTIONS));
        button(20+3*tab,54,tab,"wires",()->open(Panel.WIRE_GRAPH)); button(24+4*tab,54,tab,"signals",()->open(Panel.SIGNALS));
        button(28+5*tab,54,tab,"public_pins",()->open(Panel.PUBLIC_PINS));
        switch(panel){case MEMBERS->members();case PORTS->ports();case CONNECTIONS->connections();case CANDIDATES->candidates();case MEMBER->member();case HARDWARE->hardware();case PORT->port();case CONNECTION->connection();case WIRE_GRAPH->wireGraph();case WIRES->wires();case WIRE->wire();case SIGNALS->signals();case PUBLIC_PINS->publicPins();case PUBLIC_PIN->publicPin();case WIRING_SOURCES->wiringSources();}
        if(waiting)lockWidgets();
        var topic = switch (panel) {
            case HARDWARE, MEMBER, MEMBERS, CANDIDATES -> ManualFile.HARDWARE;
            case SIGNALS -> ManualFile.QUICK;
            default -> ManualFile.WIRING;
        };
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, topic));
    }
    private void members(){
        var values=new ArrayList<>(draft.configuration().members().entrySet());
        var unnamed=authority==null?List.<ClusterControlView.Candidate>of():authority.candidates().stream()
            .filter(ClusterControlView.Candidate::member)
            .filter(c->values.stream().noneMatch(e->e.getValue().position().equals(c.position())
                // Failed observation is a diagnostic for the saved location, not another device.
                // A positively identified replacement must still have its own candidate row.
                && (c.identity().isEmpty() || c.identity().filter(e.getValue().identity()::equals).isPresent()))).toList();
        rows(unnamed.size()+values.size(),i->{
            if(i<unnamed.size()){candidateRow(unnamed.get(i),i);return;}
            var e=values.get(i-unnamed.size());var p=e.getValue().position();
            var row=button(8,rowY(i),imageWidth-16,"member",e.getKey(),p.getX(),p.getY(),p.getZ(),()->{selectedMember=e.getKey();selectedCandidate=null;memberText=e.getKey();open(Panel.MEMBER);});
            if(authority!=null)authority.candidates().stream().filter(c->c.position().equals(p)).findFirst()
                .ifPresent(c->row.setMessage(row.getMessage().copy().append(" · ").append((Component)code(c.diagnostic()))));
        });
        bottom("configure_member",()->open(Panel.CANDIDATES));
    }
    private void ports(){
        var values=new ArrayList<>(draft.configuration().ports().entrySet());
        rows(values.size(),i->{var e=values.get(i);var p=e.getValue();button(8,rowY(i),imageWidth-16,p.enabled()?"port_enabled":"port_disabled",
            e.getKey(),p.member(),p.input(),()->editPort(e.getKey(),p));});
        var add=bottom("add_port",()->editPort(null,null));add.active=!draft.configuration().members().isEmpty();
    }
    private void connections() {
        var values = new ArrayList<>(draft.configuration().connections().entrySet());
        rows(values.size(), i -> {
            var entry = values.get(i); var connection = entry.getValue();
            button(8, rowY(i), imageWidth - 16, connection.enabled() ? "connection_enabled" : "connection_disabled",
                entry.getKey(), connection.source(), connection.target(), () -> editConnection(entry.getKey(), connection));
        });
        var add = bottom("add_connection", () -> editConnection(null, null));
        add.active = draft.configuration().members().size() >= 2;
    }
    private void publicPins() {
        var values = new ArrayList<>(draft.configuration().publicPins().entrySet());
        rows(values.size(), i -> {
            var entry = values.get(i); var mapping = entry.getValue(); var pin = mapping.target();
            var row = button(8, rowY(i), imageWidth - 16, mapping.enabled() ? "public_pin_enabled" : "public_pin_disabled",
                entry.getKey(), pin.member(), pin.pin(), () -> editPublicPin(entry.getKey(), mapping));
            if (authority != null && Objects.equals(authority.configuration().publicPins().get(entry.getKey()), mapping))
                Optional.ofNullable(authority.publicPinDiagnostics().get(entry.getKey())).ifPresent(status ->
                    row.setMessage(row.getMessage().copy().append(" · ").append(code(status))));
            // Create renders its own tooltip list, not AbstractWidget's Tooltip holder.
            // NeoForge wraps these complete components to the available screen width.
            row.getToolTip().add(row.getMessage().copy());
            row.getToolTip().add(text("public_pin_help"));
        });
        bottom("add_public_pin", () -> editPublicPin(null, null)).active = !draft.configuration().members().isEmpty();
        button(80, imageHeight - 27, imageWidth - 192, "external_wiring", () -> showWiringSources(null)).active = seeded && !dirty;
    }
    private void showWiringSources(String selected) {
        if (dirty || !seeded || waiting) return;
        if (selected != null && (authority == null || !publicPinFormMatchesAuthority(new ArrayList<>(draft.configuration().members().keySet())))) return;
        requestedWiringSource = selected; panel = Panel.WIRING_SOURCES; page = 0;
        waiting = true; pending = Pending.WIRING; menu.listWiringSources(); setNotice(text("waiting")); init();
    }
    private void wiringSources() {
        var sources = menu.wiringSources().orElse(null);
        var values = sources == null ? List.<Map.Entry<String, Boolean>>of() : new ArrayList<>(sources.endpoints().entrySet());
        rows(values.size(), i -> {
            var source = values.get(i);
            var row = button(8, rowY(i), imageWidth - 16, "wiring_source",
                BrowserLiteralDisplay.quote(source.getKey()), text(source.getValue() ? "wiring_configured" : "wiring_orphaned"),
                text("wiring_source_action"), () -> openWiring(source.getKey()));
            row.getToolTip().add(row.getMessage()); row.getToolTip().add(text("wiring_sources_help"));
        });
        bottom("back", () -> open(Panel.PUBLIC_PINS));
        if (sources != null && sources.endpoints().isEmpty()) setNotice(text("wiring_sources_empty"));
    }
    private void openWiring(String endpoint) {
        if (dirty || waiting || menu.wiringSources().filter(sources -> sources.endpoints().containsKey(endpoint)).isEmpty()) return;
        menu.selectWiringSource(endpoint);
        minecraft.setScreen(new ComputerWiringScreen(menu, minecraft.player.getInventory(), title, this));
    }
    private void editPublicPin(String name, XBusPublicPin mapping) {
        selectedPublicPin = name; publicText = name == null ? "" : name;
        var members = new ArrayList<>(draft.configuration().members().keySet());
        memberChoice = mapping == null ? 0 : Math.max(0, members.indexOf(mapping.target().member()));
        inputText = mapping == null ? endpoints(members.get(memberChoice)).stream().findFirst().orElse("") : mapping.target().endpoint();
        outputText = mapping == null ? "" : mapping.target().pin(); enabled = mapping == null || mapping.enabled();
        setNotice(text("public_pin_brief")); open(Panel.PUBLIC_PIN);
    }
    private void publicPin() {
        int column = (imageWidth - 20) / 2; var members = new ArrayList<>(draft.configuration().members().keySet());
        edit(8, 82, imageWidth - 16, "public_pin_name", publicText, value -> publicText = value);
        if (!members.isEmpty()) selection(8, 106, imageWidth - 16, members.stream().map(Component::literal).toList(), memberChoice, value -> {
            memberChoice = value; inputText = endpoints(members.get(value)).stream().findFirst().orElse(""); init();
        }, "member_select");
        edit(8, 130, column, "public_pin_grant", inputText, value -> inputText = value);
        edit(12 + column, 130, column, "public_pin_hardware", outputText, value -> outputText = value)
            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("public_pin_help")));
        button(8, 154, imageWidth - 16, enabled ? "enabled" : "disabled", () -> { enabled = !enabled; init(); });
        wiringButton = button(8, 178, imageWidth - 16, "external_wiring", () -> showWiringSources(selectedPublicPin));
        updateWiringShortcut();
        int w = (imageWidth - 24) / 3, y = imageHeight - 27;
        button(8, y, w, "apply", () -> {
            try {
                draft.putPublicPin(selectedPublicPin, publicText, new XBusPublicPin(new XBusWire.Pin(members.get(memberChoice), inputText, outputText), enabled));
                changed(Panel.PUBLIC_PINS);
            } catch (RuntimeException invalid) { setNotice(text("invalid", safe(invalid))); }
        }).active = !members.isEmpty();
        button(12 + w, y, w, "remove", () -> { draft.removePublicPin(selectedPublicPin); changed(Panel.PUBLIC_PINS); }).active = selectedPublicPin != null;
        button(16 + 2 * w, y, w, "cancel", () -> open(Panel.PUBLIC_PINS));
    }
    private boolean publicPinFormMatchesAuthority(List<String> members) {
        var mapping = authority.configuration().publicPins().get(selectedPublicPin);
        return mapping != null && selectedPublicPin.equals(publicText) && memberChoice >= 0 && memberChoice < members.size()
            && mapping.target().member().equals(members.get(memberChoice)) && mapping.target().endpoint().equals(inputText)
            && mapping.target().pin().equals(outputText) && mapping.enabled() == enabled;
    }
    private void updateWiringShortcut() {
        if (wiringButton != null) wiringButton.active = !dirty && seeded && !waiting && selectedPublicPin != null && authority != null
            && publicPinFormMatchesAuthority(new ArrayList<>(draft.configuration().members().keySet()));
    }
    private void candidates(){
        var values=authority==null?List.<ClusterControlView.Candidate>of():authority.candidates();
        rows(values.size(),i->candidateRow(values.get(i),i));
        bottom("back",()->open(Panel.MEMBERS));
    }
    // A saved endpoint whose identity, hardware or grant is unavailable is evidence, never authority.
    private record GraphPin(String pin, XBusWire.Pin ghost) {}
    private static final class GraphColumn {
        String member, endpoint;
        int page;
        final Map<GraphPin, Integer> socketY = new LinkedHashMap<>();
        final Set<XBusWire.Pin> ghosts = new LinkedHashSet<>();
        GraphPin row(XBusWire.Pin end) { return new GraphPin(end.pin(), ghosts.contains(end) ? end : null); }
    }
    private GraphColumn[] graphColumns;
    private XBusWire.Pin graphAnchor;
    private int anchorSide;
    private int graphWidth() { return (imageWidth - 24) * 2 / 5; }
    private int graphX(int side) { return side == 0 ? 8 : imageWidth - 8 - graphWidth(); }

    private void wireGraph() {
        if (graphColumns == null) {
            graphColumns = new GraphColumn[]{new GraphColumn(), new GraphColumn()};
            draft.configuration().wires().values().stream().findFirst().ifPresent(w -> {
                graphColumns[0].member = w.left().member(); graphColumns[1].member = w.right().member();
            });
        }
        var members = new ArrayList<>(draft.configuration().members().keySet());
        for (int side = 0; side < 2; side++) graphColumn(side, members);
        var hint = graphAnchor == null ? text(members.size() < 2 ? "graph_need_members" : "graph_hint")
            : text("graph_pending", quoted(graphAnchor.member()), quoted(graphAnchor.pin()));
        var status = addRenderableWidget(new StringWidget(leftPos + 8, topPos + 120, imageWidth - 16, 16,
            hint, font).setColor(0xffffddaa));
        status.setTooltip(Tooltip.create(hint));
        int w = Math.min(104, imageWidth / 3);
        button((imageWidth - w) / 2, imageHeight - 27, w, "graph_list", () -> open(Panel.WIRES));
    }
    private static String quoted(String name) { return BrowserLiteralDisplay.quote(name); }
    private void graphColumn(int side, List<String> members) {
        var column = graphColumns[side];
        column.socketY.clear(); column.ghosts.clear();
        int x = graphX(side), w = graphWidth(), footer = imageHeight - 27;
        if (members.isEmpty()) return;
        if (!members.contains(column.member)) column.member = members.get(Math.min(side, members.size() - 1));
        var grants = ClusterWireGraph.endpoints(draft.configuration(), authority, column.member);
        if (column.endpoint == null || !grants.contains(column.endpoint)) column.endpoint = grants.isEmpty() ? "" : grants.getFirst();
        var pins = ClusterWireGraph.pins(draft.configuration(), authority, column.member);
        for (var wire : draft.configuration().wires().values()) {
            for (var end : List.of(wire.left(), wire.right()))
                if (column.member.equals(end.member()) && (!pins.contains(end.pin()) || !grants.contains(end.endpoint())))
                    column.ghosts.add(end);
        }
        var rows = new ArrayList<GraphPin>();
        column.ghosts.forEach(end -> rows.add(column.row(end)));
        // If every grant is closed, don't repeat the same hardware name below its saved read-only socket.
        for (var pin : pins) if (!grants.isEmpty() || column.ghosts.stream().noneMatch(g -> g.pin().equals(pin)))
            rows.add(new GraphPin(pin, null));
        var memberSelector = selection(x, 79, w, members.stream().map(m -> text("graph_member", quoted(m))).toList(),
            members.indexOf(column.member), value -> {
                column.member = members.get(value); column.endpoint = null; column.page = 0;
                if (anchorSide == side) graphAnchor = null;
                init();
            }, side == 0 ? "left_member" : "right_member");
        memberSelector.active = members.size() > 1;
        var grantSelector = selection(x, 100, w, grants.isEmpty() ? List.of(text("no_endpoints"))
            : grants.stream().map(e -> text("graph_grant", quoted(e))).toList(), Math.max(0, grants.indexOf(column.endpoint)),
            value -> {
                if (grants.isEmpty()) return;
                column.endpoint = grants.get(value); if (anchorSide == side) graphAnchor = null; init();
            }, side == 0 ? "left_endpoint" : "right_endpoint");
        grantSelector.active = grants.size() > 1;
        int firstY = 138, capacity = Math.max(1, (footer - firstY) / 22);
        int pages = Math.max(1, Math.ceilDiv(rows.size(), capacity));
        column.page = Math.clamp(column.page, 0, pages - 1);
        for (int i = column.page * capacity; i < Math.min(rows.size(), (column.page + 1) * capacity); i++) {
            var row = rows.get(i); String pin = row.pin(); int y = firstY + (i % capacity) * 22;
            column.socketY.put(row, y + 10);
            XBusWire.Pin end = row.ghost() != null || grants.isEmpty() ? null : new XBusWire.Pin(column.member, column.endpoint, pin);
            var connected = draft.configuration().wires().entrySet().stream().filter(e ->
                matches(column, row, e.getValue().left()) || matches(column, row, e.getValue().right())).toList();
            var mark = graphAnchor != null && anchorSide == side && graphAnchor.equals(end) ? "◆" : connected.isEmpty() ? "○" : "●";
            var caption = row.ghost() != null ? text("graph_saved_pin", quoted(pin), quoted(row.ghost().endpoint()))
                : text("graph_pin", quoted(pin), mark);
            var widget = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20,
                caption, () -> graphClick(side, end), true));
            widget.active = end != null && seeded && !waiting;
            if (row.ghost() != null) {
                widget.getToolTip().add(text("graph_saved_help")); widget.getToolTip().add(graphLocation(row.ghost()));
            } else widget.getToolTip().add(text("graph_location", quoted(column.member), quoted(column.endpoint), quoted(pin)));
            for (var entry : connected) {
                var wire = entry.getValue();
                widget.getToolTip().add(text(wire.enabled() ? "wire_enabled" : "wire_disabled", quoted(entry.getKey()),
                    graphLocation(wire.left()), graphLocation(wire.right())));
            }
        }
        if (rows.isEmpty()) {
            var empty = addRenderableWidget(new StringWidget(leftPos + x, topPos + firstY, w, 20, text("graph_no_pins"), font));
            empty.setTooltip(Tooltip.create(text("graph_no_pins")));
        }
        int step = Math.min(32, (w - 4) / 2);
        if (pages > 1) {
            int start = side == 0 ? x : x + w - step * 2 - 3;
            button(start, footer, step, "previous", () -> { column.page--; init(); }).active = column.page > 0;
            button(start + step + 3, footer, step, "next", () -> { column.page++; init(); }).active = column.page + 1 < pages;
        }
    }
    private static boolean matches(GraphColumn column, GraphPin row, XBusWire.Pin end) {
        return end.member().equals(column.member) && column.row(end).equals(row);
    }
    private static Component graphLocation(XBusWire.Pin end) {
        return text("graph_location", quoted(end.member()), quoted(end.endpoint()), quoted(end.pin()));
    }
    private void graphClick(int side, XBusWire.Pin end) {
        if (end == null || !seeded || waiting || panel != Panel.WIRE_GRAPH) return;
        if (graphAnchor == null || anchorSide == side) {
            graphAnchor = end.equals(graphAnchor) ? null : end;
            anchorSide = side;
            init(); return;
        }
        try {
            ClusterWireGraph.connect(draft, authority, graphAnchor, end);
            graphAnchor = null; changed(Panel.WIRE_GRAPH);
        } catch (IllegalArgumentException invalid) {
            var reason = safe(invalid);
            setNotice(reason.startsWith("graph_") ? text(reason) : text("invalid", reason));
        }
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The contact is drawn just outside the pin label. Both must be the same accessible control.
        if (button == 0 && panel == Panel.WIRE_GRAPH && graphColumns != null) {
            for (int side = 0; side < 2; side++) {
                int edge = leftPos + graphX(side) + (side == 0 ? graphWidth() : 0);
                double offset = side == 0 ? mouseX - edge : edge - mouseX;
                if (offset < 0 || offset > 6) continue;
                for (int y : graphColumns[side].socketY.values()) {
                    if (Math.abs(mouseY - topPos - y) <= 5)
                        return super.mouseClicked(edge + (side == 0 ? -2 : 2), mouseY, button);
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    private void drawWireGraph(GuiGraphics graphics) {
        int x1 = leftPos + graphX(0) + graphWidth(), x2 = leftPos + graphX(1);
        graphics.fill(leftPos + 6, topPos + 136, leftPos + imageWidth - 6, topPos + imageHeight - 30, 0xff222b31);
        var left = graphColumns[0]; var right = graphColumns[1];
        int track = 0;
        for (var wire : draft.configuration().wires().values()) {
            var a = wire.left(); var b = wire.right();
            if (!a.member().equals(left.member)) { a = wire.right(); b = wire.left(); }
            if (!a.member().equals(left.member) || !b.member().equals(right.member)) continue;
            var ay = left.socketY.get(left.row(a)); var by = right.socketY.get(right.row(b));
            if (ay == null || by == null) continue;
            int mid = x1 + 6 + (track++ * 7) % Math.max(1, x2 - x1 - 12);
            int y1 = topPos + ay, y2 = topPos + by, color = wire.enabled() && !left.ghosts.contains(a) && !right.ghosts.contains(b) ? 0xff6ee2bd : 0xff858a8f;
            graphics.hLine(x1, mid, y1, color); graphics.vLine(mid, Math.min(y1, y2), Math.max(y1, y2), color);
            graphics.hLine(mid, x2, y2, color);
        }
        for (int side = 0; side < 2; side++) {
            int x = side == 0 ? x1 + 2 : x2 - 3;
            for (var socket : graphColumns[side].socketY.entrySet()) {
                int y = topPos + socket.getValue();
                boolean picked = graphAnchor != null && anchorSide == side && matches(graphColumns[side], socket.getKey(), graphAnchor);
                graphics.fill(x - 2, y - 2, x + 3, y + 3, picked ? 0xffffce68 : socket.getKey().ghost() != null ? 0xff858a8f : 0xffa1bbb4);
            }
        }
    }

    private void wires() {
        var values = new ArrayList<>(draft.configuration().wires().entrySet());
        rows(values.size(), i -> {
            var entry = values.get(i); var wire = entry.getValue();
            var row = button(8, rowY(i), imageWidth - 16, wire.enabled() ? "wire_enabled" : "wire_disabled",
                entry.getKey(), wire.left().member() + "." + wire.left().pin(), wire.right().member() + "." + wire.right().pin(),
                () -> editWire(entry.getKey(), wire));
            if (authority != null && Objects.equals(authority.configuration().wires().get(entry.getKey()), wire))
                Optional.ofNullable(authority.wireDiagnostics().get(entry.getKey())).ifPresent(status ->
                    row.setMessage(row.getMessage().copy().append(" · ").append(code(status))));
        });
        bottom("add_wire", () -> editWire(null, null)).active = draft.configuration().members().size() >= 2;
    }
    private void signals() {
        var values = new ArrayList<>(draft.configuration().signals().entrySet());
        rows(values.size(), i -> {
            var entry = values.get(i); var wire = entry.getValue();
            var row = button(8, rowY(i), imageWidth - 16, wire.enabled() ? "signal_enabled" : "signal_disabled",
                entry.getKey(), wire.left().member() + "." + wire.left().pin(), wire.right().member() + "." + wire.right().pin(),
                () -> editSignal(entry.getKey(), wire));
            if (authority != null && Objects.equals(authority.configuration().signals().get(entry.getKey()), wire))
                Optional.ofNullable(authority.wireDiagnostics().get(entry.getKey())).ifPresent(status ->
                    row.setMessage(row.getMessage().copy().append(" · ").append(code(status))));
        });
        bottom("add_signal", () -> editSignal(null, null)).active = !draft.configuration().members().isEmpty();
    }
    private void editWire(String name, XBusWire value) {
        signalWire = false;
        selectedWire = name; publicText = name == null ? "" : name;
        var members = new ArrayList<>(draft.configuration().members().keySet());
        sourceChoice = value == null ? 0 : Math.max(0, members.indexOf(value.left().member()));
        targetChoice = value == null ? Math.min(1, members.size() - 1) : Math.max(0, members.indexOf(value.right().member()));
        leftEndpoint = value == null ? endpoints(members.get(sourceChoice)).stream().findFirst().orElse("") : value.left().endpoint();
        rightEndpoint = value == null ? endpoints(members.get(targetChoice)).stream().findFirst().orElse("") : value.right().endpoint();
        outputText = value == null ? "" : value.left().pin(); inputText = value == null ? "" : value.right().pin();
        enabled = value == null || value.enabled();
        setNotice(text("wire_help")); open(Panel.WIRE);
    }
    private void editSignal(String name, SimpleIoWire value) {
        signalWire = true;
        selectedWire = name; publicText = name == null ? "" : name;
        var members = new ArrayList<>(draft.configuration().members().keySet());
        sourceChoice = value == null ? 0 : Math.max(0, members.indexOf(value.left().member()));
        targetChoice = value == null ? Math.min(1, members.size() - 1) : Math.max(0, members.indexOf(value.right().member()));
        leftEndpoint = value == null ? endpoints(members.get(sourceChoice)).stream().findFirst().orElse("") : value.left().endpoint();
        rightEndpoint = value == null ? endpoints(members.get(targetChoice)).stream().findFirst().orElse("") : value.right().endpoint();
        outputText = value == null ? "" : value.left().pin(); inputText = value == null ? "" : value.right().pin();
        enabled = value == null || value.enabled();
        setNotice(text("signal_help")); open(Panel.WIRE);
    }
    private void wire() {
        int column = (imageWidth - 20) / 2;
        edit(8, 82, imageWidth - 16, "wire_name", publicText, value -> publicText = value);
        var members = new ArrayList<>(draft.configuration().members().keySet());
        if (!members.isEmpty()) {
            selection(8, 106, column, members.stream().map(Component::literal).toList(), sourceChoice, value -> {
                sourceChoice = value; leftEndpoint = endpoints(members.get(value)).stream().findFirst().orElse(""); init();
            }, "left_member");
            selection(12 + column, 106, column, members.stream().map(Component::literal).toList(), targetChoice, value -> {
                targetChoice = value; rightEndpoint = endpoints(members.get(value)).stream().findFirst().orElse(""); init();
            }, "right_member");
        }
        edit(8, 130, column, "left_endpoint", leftEndpoint, value -> leftEndpoint = value);
        edit(12 + column, 130, column, "right_endpoint", rightEndpoint, value -> rightEndpoint = value);
        edit(8, 154, column, signalWire ? "left_signal_pin" : "left_pin", outputText, value -> outputText = value);
        edit(12 + column, 154, column, signalWire ? "right_signal_pin" : "right_pin", inputText, value -> inputText = value);
        button(8, 178, imageWidth - 16, enabled ? "enabled" : "disabled", () -> { enabled = !enabled; init(); });
        int w = (imageWidth - 24) / 3, y = imageHeight - 27;
        button(8, y, w, "apply", () -> {
            try {
                if (signalWire) draft.putSignal(selectedWire, publicText, new SimpleIoWire(new SimpleIoWire.Pin(members.get(sourceChoice), leftEndpoint, outputText),
                    new SimpleIoWire.Pin(members.get(targetChoice), rightEndpoint, inputText), enabled));
                else draft.putWire(selectedWire, publicText, new XBusWire(new XBusWire.Pin(members.get(sourceChoice), leftEndpoint, outputText),
                    new XBusWire.Pin(members.get(targetChoice), rightEndpoint, inputText), enabled));
                changed(signalWire ? Panel.SIGNALS : Panel.WIRES);
            } catch (RuntimeException invalid) { setNotice(text("invalid", safe(invalid))); }
        });
        var remove = button(12 + w, y, w, "remove", () -> { if (signalWire) draft.removeSignal(selectedWire); else draft.removeWire(selectedWire); changed(signalWire ? Panel.SIGNALS : Panel.WIRES); });
        remove.active = selectedWire != null;
        button(16 + 2 * w, y, w, "cancel", () -> open(signalWire ? Panel.SIGNALS : Panel.WIRES));
    }
    private void candidateRow(ClusterControlView.Candidate c,int index){
        var p=c.position();var row=button(8,rowY(index),imageWidth-16,"candidate",p.getX(),p.getY(),p.getZ(),code(c.diagnostic()),
            ()->{selectedCandidate=c;selectedMember=null;memberText="";open(Panel.MEMBER);});
        row.active=c.editable();
    }
    private void member(){
        edit(8,88,imageWidth-16,"member_name",memberText,v->memberText=v);int w=(imageWidth-24)/3;
        button(8,114,w,"apply",this::applyMember).active=selectedCandidate==null||currentCandidate().filter(c->c.selectable()&&c.identity().isPresent()).isPresent();
        var remove=button(12+w,114,w,"remove_binding",this::removeMember);remove.active=selectedMember!=null;
        button(16+2*w,114,w,"cancel",()->open(Panel.MEMBERS));
        var candidate = currentCandidate();
        var editor = button(8, 142, imageWidth - 16, "open_computer", () -> candidate.ifPresent(c -> {
            waiting = true; pending = Pending.OPEN; pendingDraftVersion = draftVersion;
            menu.openComputer(c.position()); setNotice(text("waiting")); init();
        }));
        editor.active = !dirty && candidate.filter(ClusterControlView.Candidate::editable).isPresent();
        editor.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("open_computer_hint")));
        button(8, 166, imageWidth - 16, "hardware", () -> open(Panel.HARDWARE)).active = candidate.flatMap(ClusterControlView.Candidate::hardware).isPresent();
        if(selectedCandidate!=null)candidate.ifPresent(c->{var p=c.position();setNotice(text("candidate_detail",p.getX(),p.getY(),p.getZ(),code(c.diagnostic()),c.endpoints().size()));});
    }
    private Optional<ClusterControlView.Candidate> currentCandidate() {
        if (authority == null) return Optional.empty();
        if (selectedCandidate != null) return authority.candidates().stream().filter(c -> c.position().equals(selectedCandidate.position()))
            .filter(c -> selectedCandidate.identity().isEmpty() || c.identity().isEmpty() || c.identity().equals(selectedCandidate.identity())).findFirst();
        return selectedMember == null ? Optional.empty() : Optional.ofNullable(draft.configuration().members().get(selectedMember))
                .flatMap(member -> authority.candidates().stream().filter(c -> c.position().equals(member.position()))
                    .filter(c -> c.identity().isEmpty() || c.identity().filter(member.identity()::equals).isPresent()).findFirst());
    }
    private void hardware() {
        var facts = new ArrayList<Component>();
        var candidate = currentCandidate();
        candidate.ifPresent(c -> facts.add(text("hardware_position", c.position().getX(), c.position().getY(), c.position().getZ())));
        var summary = candidate.flatMap(ClusterControlView.Candidate::hardware);
        if (summary.isEmpty()) facts.add(text("hardware_unavailable"));
        else {
            var h = summary.orElseThrow();
            facts.add(text("hardware_status", text("hardware_status." + h.status().name().toLowerCase(Locale.ROOT))));
            h.resident().ifPresent(r -> {
                facts.add(text("hardware_type", Component.translatableWithFallback(r.entityType().toLanguageKey("entity"), r.entityType().toString())));
                facts.add(text("hardware_profession", Component.translatableWithFallback(
                    "entity." + r.profession().getNamespace() + ".villager." + r.profession().getPath(), r.profession().toString())));
                facts.add(text("hardware_level", r.level()));
            });
            facts.add(text("hardware_profile", Component.translatableWithFallback(
                "create_biotech.biocomputing.cluster.hardware_profile." + h.profile(), BrowserLiteralDisplay.quote(h.profile()))));
            h.effective().ifPresent(e -> {
                facts.add(text("hardware_instruction_sets", "ISA-1" + (e.isa1a() ? ", ISA-1a" : "") + (e.isa1b() ? ", ISA-1b" : "")));
                facts.add(text("hardware_program", e.resources().programUnits()));
                facts.add(text("hardware_memory", e.resources().memoryUnits()));
                facts.add(text("hardware_work", e.resources().workPerSlice()));
                facts.add(text("hardware_tasks", e.taskSlots()));
                facts.add(text("hardware_registers", e.resources().registerSlots()));
                if (e.pins().xbus().isEmpty()) facts.add(text("hardware_xbus_empty"));
                else e.pins().xbus().forEach(pin -> facts.add(text("hardware_xbus", BrowserLiteralDisplay.quote(pin))));
                if (e.pins().simpleIo().isEmpty()) facts.add(text("hardware_simple_io_empty"));
                else e.pins().simpleIo().forEach(pin -> facts.add(text("hardware_simple_io", BrowserLiteralDisplay.quote(pin))));
                if (e.serial().ports().isEmpty()) facts.add(text("hardware_serial_empty"));
                else {
                    facts.add(text("hardware_serial_pending"));
                    e.serial().ports().forEach((name, port) -> {
                        facts.add(text("hardware_serial_port", BrowserLiteralDisplay.quote(name)));
                        facts.add(text("hardware_serial_rate", port.bitsPerTick()));
                        port.duplex().ifPresent(d -> facts.add(text("hardware_duplex", d.transmitBitsPerTick(), d.receiveBitsPerTick())));
                        facts.add(text("hardware_serial_frame", port.maxFrameBytes()));
                        facts.add(text("hardware_serial_receive", port.receiveFrames(), port.receiveBytes()));
                        facts.add(text("hardware_serial_transmit",port.transmitFrames(),port.transmitBytes()));
                    });
                }
            });
            if (h.effective().isEmpty()) facts.add(text("hardware_no_effective"));
        }
        rows(facts.size(), i -> {
            var label = addRenderableWidget(new StringWidget(leftPos + 8, topPos + rowY(i), imageWidth - 16, 20, facts.get(i), font)
                .alignLeft().setColor(0xffeeeeee));
            label.setTooltip(Tooltip.create(facts.get(i)));
        });
        bottom("back", () -> open(Panel.MEMBER));
    }
    private void port(){
        int c=(imageWidth-20)/2;edit(8,82,c,"public_port",publicText,v->publicText=v);
        edit(8,106,c,"output",outputText,v->outputText=v);edit(12+c,106,c,"external",externalText,v->externalText=v);
        var members=new ArrayList<>(draft.configuration().members().keySet());if(!members.isEmpty()){
            memberChoice=Math.max(0,Math.min(memberChoice,members.size()-1));String member=members.get(memberChoice);var endpoints=new ArrayList<>(endpoints(member));
            if(inputText.isEmpty()&&!endpoints.isEmpty())inputText=endpoints.getFirst();
            if(!inputText.isEmpty()&&!endpoints.contains(inputText))endpoints.add(inputText);int endpoint=Math.max(0,endpoints.indexOf(inputText));
            var inputOptions=endpoints.isEmpty()?List.of(text("no_endpoints")):endpoints.stream().map(Component::literal).toList();
            var inputSelect=selection(12+c,82,c,inputOptions,endpoint,
                v->{if(!endpoints.isEmpty())inputText=endpoints.get(v);},"input");inputSelect.active=!endpoints.isEmpty();
            selection(8,130,imageWidth-16,members.stream().map(Component::literal).toList(),memberChoice,
                v->{memberChoice=v;var next=endpoints(members.get(v));inputText=next.isEmpty()?"":next.getFirst();init();},"member_select");}
        button(8,154,imageWidth-16,enabled?"enabled":"disabled",()->{enabled=!enabled;init();});int w=(imageWidth-24)/3,y=imageHeight-27;
        button(8,y,w,"apply",this::applyPort);var remove=button(12+w,y,w,"remove",this::removePort);remove.active=selectedPort!=null;
        button(16+2*w,y,w,"cancel",()->open(Panel.PORTS));
    }
    private void connection() {
        int column = (imageWidth - 20) / 2;
        edit(8, 82, column, "connection_name", publicText, value -> publicText = value);
        edit(12 + column, 82, column, "output", outputText, value -> outputText = value);
        var members = new ArrayList<>(draft.configuration().members().keySet());
        if (!members.isEmpty()) {
            sourceChoice = Math.clamp(sourceChoice, 0, members.size() - 1);
            targetChoice = Math.clamp(targetChoice, 0, members.size() - 1);
            selection(8, 106, imageWidth - 16, members.stream().map(Component::literal).toList(), sourceChoice,
                value -> { sourceChoice = value; }, "source_member_select");
            selection(8, 130, imageWidth - 16, members.stream().map(Component::literal).toList(), targetChoice,
                value -> {
                    targetChoice = value;
                    var next = endpoints(members.get(value)); inputText = next.isEmpty() ? "" : next.getFirst(); init();
                }, "target_member_select");
            var endpoints = new ArrayList<>(endpoints(members.get(targetChoice)));
            if (inputText.isEmpty() && !endpoints.isEmpty()) inputText = endpoints.getFirst();
            if (!inputText.isEmpty() && !endpoints.contains(inputText)) endpoints.add(inputText);
            var input = edit(8, 154, column, "input", inputText, value -> inputText = value);
            var options = endpoints.isEmpty() ? List.of(text("no_endpoints")) : endpoints.stream().map(Component::literal).toList();
            var selector = selection(12 + column, 154, column, options, Math.max(0, endpoints.indexOf(inputText)),
                value -> { if (!endpoints.isEmpty()) input.setValue(endpoints.get(value)); }, "input");
            selector.active = !endpoints.isEmpty();
        }
        button(8, 178, imageWidth - 16, enabled ? "enabled" : "disabled", () -> { enabled = !enabled; init(); });
        int width = (imageWidth - 24) / 3, y = imageHeight - 27;
        button(8, y, width, "apply", this::applyConnection);
        var remove = button(12 + width, y, width, "remove", this::removeConnection); remove.active = selectedConnection != null;
        button(16 + 2 * width, y, width, "cancel", () -> open(Panel.CONNECTIONS));
    }
    private void applyMember(){try{if(selectedCandidate!=null)draft.addMember(memberText,currentCandidate().orElseThrow(()->new IllegalArgumentException("member_unavailable")));else draft.renameMember(Objects.requireNonNull(selectedMember),memberText);
        changed(Panel.MEMBERS);}catch(RuntimeException e){setNotice(text("invalid",safe(e)));}}
    private void removeMember(){if(selectedMember!=null){draft.removeMember(selectedMember);changed(Panel.MEMBERS);}}
    private void editPort(String name,ClusterConfiguration.Port value){selectedPort=name;publicText=name==null?"":name;inputText=value==null?"":value.input();
        outputText=value==null?"":value.output();externalText=value==null?"":value.external();enabled=value==null||value.enabled();
        if(value!=null)memberChoice=Math.max(0,new ArrayList<>(draft.configuration().members().keySet()).indexOf(value.member()));open(Panel.PORT);}
    private List<String> endpoints(String member){if(authority==null)return List.of();var configured=draft.configuration().members().get(member);if(configured==null)return List.of();
        return authority.candidates().stream().filter(c->c.position().equals(configured.position())&&c.identity().filter(configured.identity()::equals).isPresent())
            .findFirst().map(ClusterControlView.Candidate::endpoints).orElseGet(List::of);}
    private void applyPort(){try{var members=new ArrayList<>(draft.configuration().members().keySet());if(members.isEmpty())throw new IllegalArgumentException("no_members");
        draft.putPort(selectedPort,publicText,members.get(Math.max(0,Math.min(memberChoice,members.size()-1))),inputText,outputText,externalText,enabled);changed(Panel.PORTS);
        }catch(RuntimeException e){setNotice(text("invalid",safe(e)));}}
    private void removePort(){if(selectedPort!=null){draft.removePort(selectedPort);changed(Panel.PORTS);}}
    private void editConnection(String name, ClusterConfiguration.Connection value) {
        selectedConnection = name; publicText = name == null ? "" : name;
        outputText = value == null ? "" : value.output(); inputText = value == null ? "" : value.input();
        enabled = value == null || value.enabled();
        var members = new ArrayList<>(draft.configuration().members().keySet());
        sourceChoice = value == null ? 0 : Math.max(0, members.indexOf(value.source()));
        targetChoice = value == null ? Math.min(1, members.size() - 1) : Math.max(0, members.indexOf(value.target()));
        open(Panel.CONNECTION);
    }
    private void applyConnection() {
        try {
            var members = new ArrayList<>(draft.configuration().members().keySet());
            if (members.size() < 2) throw new IllegalArgumentException("no_members");
            draft.putConnection(selectedConnection, publicText, members.get(sourceChoice), outputText, members.get(targetChoice), inputText, enabled);
            changed(Panel.CONNECTIONS);
        } catch (RuntimeException invalid) { setNotice(text("invalid", safe(invalid))); }
    }
    private void removeConnection() {
        if (selectedConnection != null) { draft.removeConnection(selectedConnection); changed(Panel.CONNECTIONS); }
    }
    private void setNotice(Component message) {
        notice = message;
        if (noticeLabel != null) {
            noticeLabel.setMessage(message);
            noticeLabel.setTooltip(Tooltip.create(message));
        }
    }
    private void changed(Panel target){dirty=true;draftVersion++;setNotice(text("draft_changed"));open(target);}
    private void refresh(){waiting=true;pending=Pending.REFRESH;pendingDraftVersion=draftVersion;menu.requestRefresh();setNotice(text("waiting"));init();}
    private void save(){if(dirty&&!waiting){waiting=true;pending=Pending.SAVE;pendingDraftVersion=draftVersion;menu.configure(draft.configuration(),draftRevision);setNotice(text("waiting"));init();}}
    private void open(Panel target){if (target != Panel.WIRE_GRAPH) graphAnchor = null; boolean leavingWiring=panel==Panel.WIRING_SOURCES&&target!=Panel.WIRING_SOURCES;panel=target;page=0;if(leavingWiring)refresh();else init();}
    private interface Row{void add(int index);}
    private ScrollInput selection(int x, int y, int width, List<? extends Component> options, int state,
            java.util.function.Consumer<Integer> changed, String title) {
        var control = new SelectionScrollInput(leftPos + x, topPos + y, width, 18);
        // Create's scroll control is a hotspot; its value needs a separately rendered label.
        var label = new Label(leftPos + x, topPos + y, Component.empty()) {
            @Override protected void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                PatternGuiSkin.blit(graphics, PatternGuiSkin.button(control.active ? "normal" : "disabled"),
                    getX(), getY(), getWidth(), getHeight());
                graphics.drawString(font, font.plainSubstrByWidth(this.text.getString(), getWidth() - 8),
                    getX() + 4, getY() + 5, control.active ? 0xffeeeeee : 0xff999999, false);
            }
        };
        label.setWidth(width); label.setHeight(18);
        addRenderableOnly(label);
        return addRenderableWidget(control.forOptions(options).setState(state).writingTo(label)
            .calling(changed).titled(text(title).copy()));
    }
    private void rows(int size,Row row){int n=Math.max(1,(imageHeight-112)/22),pages=Math.max(1,(size+n-1)/n);page=Math.max(0,Math.min(page,pages-1));
        for(int i=page*n;i<Math.min(size,(page+1)*n);i++)row.add(i);var prev=button(8,imageHeight-27,32,"previous",()->{page--;init();});prev.active=page>0;
        var next=button(44,imageHeight-27,32,"next",()->{page++;init();});next.active=page+1<pages;}
    private int rowY(int index){int n=Math.max(1,(imageHeight-112)/22);return 80+(index%n)*22;}
    private CreateTextButton bottom(String key,Runnable action){return button(imageWidth-108,imageHeight-27,100,key,action);}
    private EditBox edit(int x,int y,int w,String key,String value,java.util.function.Consumer<String> responder){var box=addRenderableWidget(new EditBox(font,leftPos+x,topPos+y,w,18,text(key)));
        box.setMaxLength(EndpointConfiguration.MAX_NAME_LENGTH+1);box.setValue(value);box.setHint(text(key));box.setResponder(v->{responder.accept(v);updateWiringShortcut();});return box;}
    private CreateTextButton button(int x,int y,int w,String key,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,text(key),action,true));}
    private CreateTextButton button(int x,int y,int w,String key,Object a,Object b,Object c,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,text(key,a,b,c),action,true));}
    private CreateTextButton button(int x,int y,int w,String key,Object a,Object b,Object c,Object d,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,w,20,text(key,a,b,c,d),action,true));}
    private void lockWidgets(){children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);}
    @Override protected void containerTick(){super.containerTick();if(!requested){requested=true;waiting=true;pending=Pending.REFRESH;pendingDraftVersion=draftVersion;menu.requestRefresh();lockWidgets();}if(version==menu.responseVersion())return;
        version=menu.responseVersion();var response=menu.response().orElse(null);if(response==null)return;setNotice(result(response.code(),response.detail()));
        if(panel==Panel.WIRING_SOURCES){waiting=false;pending=Pending.NONE;init();var selected=requestedWiringSource;requestedWiringSource=null;if(selected!=null)openWiring(selected);return;}
        if(waiting){waiting=false;
            graphAnchor = null; authority=response.state().length>0?menu.view().orElse(null):null;
            if(authority!=null){var v=authority;if(pending==Pending.REFRESH||pending==Pending.SAVE&&response.code().equals("configured")&&pendingDraftVersion==draftVersion){
                draft=new ClusterControlDraft(v.configuration());draftRevision=response.revision();seeded=true;dirty=false;draftVersion=0;}}pending=Pending.NONE;init();}}
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics,PatternGuiSkin.HEADER,leftPos+4,topPos+4,imageWidth-8,22);graphics.drawString(font,font.plainSubstrByWidth(text("title").getString(),imageWidth-90),leftPos+10,topPos+10,0xffeeeeee,false);
        if (panel == Panel.WIRE_GRAPH && graphColumns != null) drawWireGraph(graphics);
        if(authority!=null && imageWidth>360)graphics.drawString(font,font.plainSubstrByWidth(text("status",code(authority.diagnostic()),authority.queued(),authority.pending()).getString(),imageWidth-302),leftPos+220,topPos+10,0xffaaccee,false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void removed() {} // Child wiring screen shares this same physical menu.
    /** Shared presentation for status rows and recent events; never reads device state. */
    public static Component code(String value) {
        if (value.matches("signal_[0-9]{1,3}_[0-9]{1,3}")) {
            var levels = value.split("_"); return text("signal_levels", levels[1], levels[2]);
        }
        if (value.startsWith("signal_") && value.contains("+")) {
            var reasons = value.substring("signal_".length()).split("\\+", -1);
            var known = Set.of("closed", "unloaded", "missing", "hardware_mismatch", "resource_limit");
            if (Arrays.stream(reasons).allMatch(known::contains)) {
                var label = Component.empty();
                for (var reason : reasons) {
                    if (!label.getSiblings().isEmpty()) label.append(" · ");
                    label.append(code("signal_" + reason));
                }
                return label;
            }
        }
        var parts = value.split("_", -1);
        var modes = Set.of("idle", "paused", "read", "write", "readable", "complete", "failed");
        if (parts.length == 3 && parts[0].equals("xbus") && modes.contains(parts[1]) && modes.contains(parts[2]))
            return text("wire_wait_status", text("pin_mode." + parts[1]), text("pin_mode." + parts[2]));
        return Component.translatableWithFallback("create_biotech.biocomputing.cluster.code." + value, value);
    }
    private static Component result(String value, String detail) {
        String key = "create_biotech.biocomputing.cluster.result." + value;
        if (net.minecraft.locale.Language.getInstance().has(key) && !detail.isEmpty()) return Component.translatable(key, detail);
        var label = code(value).copy();
        return detail.isEmpty() ? label : label.append(" · ").append(detail);
    }
    private static String safe(RuntimeException e){String value=String.valueOf(e.getMessage());return value.length()<=160?value:value.substring(0,160);}
    private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.cluster."+key,args);}
}
