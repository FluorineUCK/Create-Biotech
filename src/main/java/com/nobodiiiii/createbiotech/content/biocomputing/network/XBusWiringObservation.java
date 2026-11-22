package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;

/** Per-declaration physical binding feedback, NOT component readiness or exchange authority. */
public final class XBusWiringObservation {
    private XBusWiringObservation() {}
    public record Modes(XBusPortState.Mode source, XBusPortState.Mode target) {}
    public record Result(String code, Optional<Modes> modes) {}

    public static Result inspect(ServerLevel level, XBusWiringSavedData store,
            XBusWiringSavedData.EnabledConnection connection) {
        return inspect(store, connection, XBusCableNetwork.resolver(level));
    }

    static Result inspect(XBusWiringSavedData store, XBusWiringSavedData.EnabledConnection connection,
            BiFunction<XBusCableNetwork.End, XBusCableNetwork.End, XBusCableRoute.Result> resolve) {
        if (!store.current(connection)) return rejected("stale");
        var faces = EnumSet.allOf(Direction.class);
        var source = new XBusCableNetwork.End(connection.entry().origin(), faces);
        var target = new XBusCableNetwork.End(connection.entry().peer(), faces);
        var left = XBusCableNetwork.resolve(resolve, target, source);
        var right = XBusCableNetwork.resolve(resolve, source, target);
        if (!store.current(connection)) return rejected("stale");
        if (left.pin().isEmpty()) return rejected(left.code());
        if (right.pin().isEmpty()) return rejected(right.code());
        var a = left.pin().orElseThrow(); var b = right.pin().orElseThrow();
        var guarded = a.access().validated(() -> store.current(connection)
            ? Optional.empty() : Optional.of(XBusExchange.Status.STALE));
        // inspect() would publish identity frontiers/retire history. Cosmetics must only inspect bindings.
        var denied = XBusAccess.network(List.of(guarded, b.access())).inspectBindings();
        if (denied.isPresent()) return rejected(denied.orElseThrow().name().toLowerCase(Locale.ROOT));
        return new Result("ready", Optional.of(new Modes(a.observed().mode(), b.observed().mode())));
    }

    private static Result rejected(String code) { return new Result(code, Optional.empty()); }
}
