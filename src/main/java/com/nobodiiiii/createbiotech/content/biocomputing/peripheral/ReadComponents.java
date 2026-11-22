package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Trusted mod-initialization extension point, frozen on first catalog access. */
public final class ReadComponents {
    private static final ReadComponentRegistry.Builder<BlockReadContext> BUILDER = ReadComponentRegistry.builder();
    private static final List<ReadAccessPolicy> POLICIES = new ArrayList<>();
    private static ReadComponentRegistry<BlockReadContext> registry;
    private static List<ReadAccessPolicy> policies;
    static {
        BuiltinReadComponents.register(BUILDER);
        CreateReadComponents.register(BUILDER);
        POLICIES.add(CreateReadComponents::deny);
    }
    private ReadComponents() {}

    public static synchronized void register(ReadComponentProvider<BlockReadContext> provider) {
        ensureOpen();
        BUILDER.register(provider);
    }
    public static synchronized void registerPolicy(ReadAccessPolicy policy) {
        ensureOpen();
        POLICIES.add(Objects.requireNonNull(policy));
    }
    public static synchronized ReadComponentRegistry<BlockReadContext> registry() {
        if (registry == null) {
            registry = BUILDER.build();
            policies = List.copyOf(POLICIES);
        }
        return registry;
    }
    public static synchronized List<ReadAccessPolicy> policies() {
        registry();
        return policies;
    }
    private static void ensureOpen() {
        if (registry != null) throw new IllegalStateException("Read components are frozen");
    }
}
