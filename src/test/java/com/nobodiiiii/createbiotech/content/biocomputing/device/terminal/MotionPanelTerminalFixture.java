package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Actual terminal layout, projection and queue; the enclosing test owns the wire. */
public final class MotionPanelTerminalFixture {
    private final UUID relay = UUID.randomUUID();
    private final TerminalUiState.Limits limits;
    private final DataValue.RecordValue address;
    private TerminalUiState.State state = TerminalUiState.grant(
        TerminalUiState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(relay), UUID.randomUUID());

    public MotionPanelTerminalFixture(TerminalUiState.Limits limits, DataValue.RecordValue address) {
        this.limits = limits;
        this.address = address;
    }

    public DataValue.RecordValue reply(DataValue.RecordValue packet) {
        var planned = TerminalUiProtocol.plan(state, relay, address, packet, limits).orElseThrow();
        state = planned.next();
        return planned.reply();
    }

    public void seedPriorView(TerminalDefinition layout) {
        var published = TerminalUiState.publish(state, TerminalCodec.encode(layout),
            new DataValue.RecordValue(Map.of()), limits);
        assertEquals("published", published.code());
        state = published.state();
    }

    public void submit(String distance) {
        var page = state.page().orElseThrow();
        var input = TerminalProjection.project(page.layout(), page.roots(), Map.of(), 4, 12);
        assertEquals("1", input.input().orElseThrow().text());
        var queued = TerminalUiState.interact(state, state.session(), state.revision(), input.instance(),
            Optional.of(distance), limits);
        assertEquals("queued", queued.code());
        state = queued.state();
    }

    public void click(String widget) {
        var page = state.page().orElseThrow();
        var view = TerminalProjection.project(page.layout(), page.roots(), Map.of(), 4, 12);
        var button = view.children().stream().filter(child -> child.node().equals(widget))
            .filter(child -> child.button().orElseThrow().enabled()).findFirst().orElseThrow();
        var queued = TerminalUiState.interact(state, state.session(), state.revision(), button.instance(),
            Optional.empty(), limits);
        assertEquals("queued", queued.code());
        state = queued.state();
    }

    public int queued() { return state.events().size(); }

    public List<DataValue> visibleText() {
        var page = state.page().orElseThrow();
        var view = TerminalProjection.project(page.layout(), page.roots(), Map.of(), limits.widgets(), limits.widgets());
        var values = new ArrayList<DataValue>();
        collect(view, values);
        return List.copyOf(values);
    }

    private static void collect(TerminalProjection.View view, List<DataValue> values) {
        if (view.definition() instanceof TerminalDefinition.Text) view.value().ifPresent(values::add);
        view.children().forEach(child -> collect(child, values));
    }
}
