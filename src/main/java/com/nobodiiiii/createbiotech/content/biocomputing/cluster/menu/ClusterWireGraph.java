package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import java.util.List;
import java.util.Optional;

/** Project the captured authority onto actual named members, never guessed pin or endpoint names. */
final class ClusterWireGraph {
    private ClusterWireGraph() {}

    private static Optional<ClusterControlView.Candidate> candidate(ClusterConfiguration config, ClusterControlView view, String name) {
        var member = config.members().get(name);
        if (member == null || view == null) return Optional.empty();
        return view.candidates().stream().filter(c -> c.member() && c.selectable() && c.position().equals(member.position())
            && c.identity().filter(member.identity()::equals).isPresent()).findFirst();
    }

    static List<String> endpoints(ClusterConfiguration config, ClusterControlView view, String member) {
        return candidate(config, view, member).map(ClusterControlView.Candidate::endpoints).orElse(List.of());
    }

    static List<String> pins(ClusterConfiguration config, ClusterControlView view, String member) {
        return candidate(config, view, member).flatMap(ClusterControlView.Candidate::hardware)
            .flatMap(h -> h.effective()).map(e -> e.pins().xbus()).orElse(List.of());
    }

    static String connect(ClusterControlDraft draft, ClusterControlView view, XBusWire.Pin first, XBusWire.Pin second) {
        var config = draft.configuration();
        if (first.member().equals(second.member())) throw new IllegalArgumentException("graph_distinct");
        for (var pin : List.of(first, second)) {
            if (!endpoints(config, view, pin.member()).contains(pin.endpoint()) || !pins(config, view, pin.member()).contains(pin.pin()))
                throw new IllegalArgumentException("graph_unavailable");
        }
        // Grants authorize access to a pin; they do not define separate physical edges.
        // Multi-drop is valid, but an existing pair (including a disabled one) is edited in the list.
        if (config.wires().values().stream().anyMatch(w ->
            (first.samePin(w.left()) && second.samePin(w.right())) ||
            (first.samePin(w.right()) && second.samePin(w.left()))))
            throw new IllegalArgumentException("graph_duplicate");
        var names = config.wireNames();
        int n = 1;
        while (names.contains("wire_" + n)) n++;
        String name = "wire_" + n;
        draft.putWire(null, name, new XBusWire(first, second, true));
        return name;
    }
}
