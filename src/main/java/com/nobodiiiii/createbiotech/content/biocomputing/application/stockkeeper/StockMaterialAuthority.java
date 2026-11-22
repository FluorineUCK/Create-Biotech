package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Order;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPackagingBackend.Route;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import java.util.function.Supplier;

/** An in-memory witness for exactly one external command or one scheduler scan, never an effect permit. */
final class StockMaterialAuthority {
    private record Witness(UUID source, Route route, DataValue.TextValue network) {}
    private Witness current;

    <T> T with(UUID source, Supplier<Optional<Route>> resolve, Supplier<T> action, T denied) {
        if (current != null) throw new IllegalStateException("material_authority_reentry");
        var route = resolve.get().orElse(null);
        if (route == null) return denied;
        current = new Witness(source, route, new DataValue.TextValue(route.network().toString()));
        try { return action.get(); }
        finally { current = null; }
    }
    Optional<Route> route(UUID source) {
        return current != null && current.source().equals(source) ? Optional.of(current.route()) : Optional.empty();
    }
    boolean inScope(Order order) {
        return current != null && current.source().equals(order.key().source())
            && current.network().equals(order.plan().steps().get(order.step()).fields().get("network"));
    }
}
