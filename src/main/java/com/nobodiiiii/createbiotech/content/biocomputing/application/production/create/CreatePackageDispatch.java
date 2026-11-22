package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Checked native Create packaging, independent of production Computer slots and the old engine.
 * Internal effect boundary, not a general program/world-write node: callers must first persist
 * their operation intent and supply a current server-thread/authority check for this exact route.
 * Accepted means native packaging was invoked, NOT delivered or production completed.
 */
public final class CreatePackageDispatch {
    private CreatePackageDispatch() {}
    public record NativeOrder(int id, Set<Integer> links) {
        public NativeOrder {
            links = Set.copyOf(links);
            if (links.isEmpty() || links.stream().anyMatch(link -> link < 0)) throw new IllegalArgumentException("invalid_native_order");
        }
    }
    public record Result(String code, List<NativeOrder> orders) {
        public Result {
            Objects.requireNonNull(code); orders = List.copyOf(orders);
            if (code.equals("accepted") == orders.isEmpty()) throw new IllegalArgumentException("invalid_dispatch_result");
        }
        public boolean accepted() { return code.equals("accepted"); }
    }
    record Promise(StackKey stack, long count, int order, int link) {
        Promise {
            Objects.requireNonNull(stack);
            if (count <= 0 || link < 0) throw new IllegalArgumentException("invalid_native_promise");
        }
    }
    /** Ephemeral server-thread capture. Never persisted, exposed as a value, or retained across ticks. */
    record Prepared(List<Promise> promises, BooleanSupplier ready, Runnable perform) {
        Prepared { promises = List.copyOf(promises); Objects.requireNonNull(ready); Objects.requireNonNull(perform); }
    }
    @FunctionalInterface interface Prepare {
        Prepared capture(UUID network, String address, List<BigItemStack> stacks);
    }
    public static Result dispatch(UUID network, String address, Map<StackKey, Long> manifest,
                                  int maxEntries, BooleanSupplier mayDispatch) {
        return dispatch(network, address, manifest, maxEntries, mayDispatch, CreatePackageDispatch::prepareCreate);
    }
    static Result dispatch(UUID network, String address, Map<StackKey, Long> manifest,
                           int maxEntries, BooleanSupplier mayDispatch, Prepare prepare) {
        Objects.requireNonNull(network); address = RecipeAddressRules.requireNormalized(address);
        Objects.requireNonNull(mayDispatch); Objects.requireNonNull(prepare);
        manifest = Map.copyOf(manifest);
        if (maxEntries < 1 || manifest.isEmpty()) throw new IllegalArgumentException("invalid_manifest");
        if (!mayDispatch.getAsBoolean()) return rejected("denied");
        // Count the required native int-sized entries before allocating or visiting the network.
        long entries = 0;
        for (var entry : manifest.entrySet()) {
            long amount = entry.getValue();
            if (amount <= 0 || entry.getKey().stack().isEmpty()) throw new IllegalArgumentException("invalid_manifest");
            entries += 1 + (amount - 1) / Integer.MAX_VALUE;
            if (entries > maxEntries) return rejected("capacity");
        }
        var stacks = new ArrayList<BigItemStack>((int) entries);
        manifest.forEach((key, amount) -> {
            long remaining = amount;
            while (remaining > 0) {
                int part = (int)Math.min(Integer.MAX_VALUE, remaining);
                stacks.add(new BigItemStack(key.stack(), part)); remaining -= part;
            }
        });
        Prepared prepared = Objects.requireNonNull(prepare.capture(network, address, List.copyOf(stacks)));
        if (prepared.promises().isEmpty()) return rejected("no_stock");
        if (prepared.promises().size() > maxEntries) return rejected("capacity");
        var promised = new LinkedHashMap<StackKey, Long>();
        var links = new LinkedHashMap<Integer, Set<Integer>>();
        for (var promise : prepared.promises()) {
            try { promised.merge(promise.stack(), promise.count(), Math::addExact); }
            catch (ArithmeticException invalidNativeCounts) { return rejected("manifest_mismatch"); }
            links.computeIfAbsent(promise.order(), ignored -> new LinkedHashSet<>()).add(promise.link());
        }
        if (!promised.equals(manifest)) return rejected("manifest_mismatch");
        if (!prepared.ready().getAsBoolean()) return rejected("device_unavailable");
        if (!mayDispatch.getAsBoolean()) return rejected("denied");
        var identities = links.entrySet().stream().map(entry -> new NativeOrder(entry.getKey(), entry.getValue())).toList();
        // Do not turn exceptions here into rejection. Some packagers may already have changed.
        prepared.perform().run();
        return new Result("accepted", identities);
    }
    private static Result rejected(String code) { return new Result(code, List.of()); }
    private static Prepared prepareCreate(UUID network, String address, List<BigItemStack> stacks) {
        var requests = LogisticsManager.findPackagersForRequest(network, PackageOrderWithCrafts.simple(stacks), null, address);
        var promises = requests.values().stream().map(request -> new Promise(new StackKey(request.item()),
            request.getCount(), request.orderId(), request.linkIndex())).toList();
        return new Prepared(promises,
            () -> requests.keySet().stream().allMatch(CreatePackageDispatch::ready),
            () -> LogisticsManager.performPackageRequests(requests));
    }
    private static boolean ready(PackagerBlockEntity packager) {
        if (!(packager.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
            || packager.isRemoved() || !level.shouldTickBlocksAt(packager.getBlockPos())) return false;
        var pos = packager.getBlockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) == packager && !packager.isTooBusyFor(RequestType.PLAYER);
    }
}
