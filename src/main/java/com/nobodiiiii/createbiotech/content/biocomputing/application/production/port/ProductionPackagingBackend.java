package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreatePackageDispatch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Backend;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.HolderLookup;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Shared checked material adapter for a host-resolved route and host-supplied grant fence. */
public final class ProductionPackagingBackend implements Backend {
    public record Route(UUID network, String address, long revision) {
        boolean matches(DataValue.RecordValue command) {
            return text(network.toString()).equals(command.fields().get("network"))
                && text(address).equals(command.fields().get("address")) && integer(revision).equals(command.fields().get("revision"));
        }
    }
    private final Function<UUID, Optional<Route>> authority, liveAuthority;
    private final Supplier<HolderLookup.Provider> registries;
    private final Predicate<Order> scope;
    public ProductionPackagingBackend(Function<UUID, Optional<Route>> authority,
                                      Supplier<HolderLookup.Provider> registries, Predicate<Order> scope) {
        this(authority, authority, registries, scope);
    }
    public ProductionPackagingBackend(Function<UUID, Optional<Route>> authority, Function<UUID, Optional<Route>> liveAuthority,
                                      Supplier<HolderLookup.Provider> registries, Predicate<Order> scope) {
        this.authority = authority;
        this.liveAuthority = liveAuthority;
        this.registries = registries;
        this.scope = scope;
    }
    public boolean authorized(UUID source) { return authority.apply(source).isPresent(); }
    public boolean inScope(Order order) { return scope.test(order); }
    public boolean supports(String extension, int version) { return extension.equals("create.packaging") && version == 1; }
    private Map<StackKey, Long> materials(DataValue.RecordValue request) {
        var limits = CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded());
        return ProductionMaterials.decode(request, registries.get(),
            new NativeNbtCodec.Limits(limits.maxEncodedBytes(), limits.maxDecodeHeapBytes(), limits.values()), nativeEntries());
    }
    private static int nativeEntries() {
        var value = CBConfigs.SERVER.biocomputing.productionNativeRequestEntries;
        return CBConfigs.SERVER_SPEC.isLoaded() ? value.get() : value.getDefault();
    }
    public Preparation prepare(UUID source, DataValue.RecordValue request) {
        var route = authority.apply(source).orElse(null);
        if (route == null) return Preparation.rejected("denied");
        try { materials(request); }
        catch (RuntimeException failure) { return Preparation.rejected("capacity".equals(failure.getMessage()) ? "capacity" : "invalid_material"); }
        var command = new DataValue.RecordValue(Map.of("network", text(route.network().toString()), "address", text(route.address()),
            "revision", integer(route.revision()), "data", request));
        return Preparation.ready(new Plan("create.packaging", 1, List.of(command)));
    }
    public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
        var source = operation.order().source();
        var route = liveAuthority.apply(source).filter(value -> value.matches(command)).orElse(null);
        if (route == null) return Dispatch.rejected("grant_changed");
        final Map<StackKey, Long> manifest;
        try { manifest = materials((DataValue.RecordValue)command.field("data")); }
        catch (RuntimeException invalid) { return Dispatch.rejected("invalid_material"); }
        var dispatched = CreatePackageDispatch.dispatch(route.network(), route.address(), manifest, nativeEntries(),
            () -> liveAuthority.apply(source).filter(route::equals).isPresent());
        if (!dispatched.accepted()) return Dispatch.rejected(dispatched.code());
        var receipts = dispatched.orders().stream().<DataValue>map(order -> new DataValue.RecordValue(Map.of("id", integer(order.id()),
            "links", new DataValue.ListValue(order.links().stream().sorted().<DataValue>map(ProductionPackagingBackend::integer).toList())))).toList();
        return Dispatch.accepted(new DataValue.RecordValue(Map.of("native_orders", new DataValue.ListValue(receipts))));
    }
    public Observation observe(Operation operation, DataValue.RecordValue receipt) {
        // Native unpack receipts independently publish exact delivery evidence, including when the host is unloaded.
        return Observation.pending();
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
