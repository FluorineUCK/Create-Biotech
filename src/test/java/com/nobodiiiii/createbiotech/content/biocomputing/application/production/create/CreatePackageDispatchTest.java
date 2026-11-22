package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatePackageDispatchTest {
    private static StackKey stone;
    private static final UUID NETWORK = new UUID(7, 8);
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        stone = new StackKey(new ItemStack(Items.STONE));
    }
    @Test void onlyAnExactReadyManifestMayCrossTheNativeBoundary() {
        var calls = new AtomicInteger();
        var result = CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, 4L), 8, () -> true,
            (network, address, stacks) -> {
                assertEquals(NETWORK, network); assertEquals("workshop", address);
                assertEquals(4, stacks.getFirst().count);
                return new CreatePackageDispatch.Prepared(List.of(
                    new CreatePackageDispatch.Promise(stone, 2, 73, 0),
                    new CreatePackageDispatch.Promise(stone, 2, 73, 1)), () -> true, calls::incrementAndGet);
            });
        assertEquals("accepted", result.code());
        assertEquals(List.of(new CreatePackageDispatch.NativeOrder(73, Set.of(0, 1))), result.orders());
        assertEquals(1, calls.get());
    }
    @Test void partialAvailabilityCannotSendAPartialRecipe() {
        var calls = new AtomicInteger();
        var result = CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, 4L), 8, () -> true,
            (network, address, stacks) -> new CreatePackageDispatch.Prepared(List.of(
                new CreatePackageDispatch.Promise(stone, 3, 73, 0)), () -> true, calls::incrementAndGet));
        assertEquals("manifest_mismatch", result.code()); assertEquals(0, calls.get());
    }
    @Test void BusyOrUnloadedPackagersCannotDispatchEvenWithEnoughStock() {
        var calls = new AtomicInteger();
        var result = CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, 4L), 8, () -> true,
            (network, address, stacks) -> new CreatePackageDispatch.Prepared(List.of(
                new CreatePackageDispatch.Promise(stone, 4, 73, 0)), () -> false, calls::incrementAndGet));
        assertEquals("device_unavailable", result.code()); assertEquals(0, calls.get());
    }
    @Test void AuthorityIsRecheckedAfterNativePreparationBeforeAnyMaterialOperation() {
        var calls = new AtomicInteger(); var allowed = new AtomicBoolean(true);
        var result = CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, 4L), 8, allowed::get,
            (network, address, stacks) -> {
                allowed.set(false);
                return new CreatePackageDispatch.Prepared(List.of(new CreatePackageDispatch.Promise(stone, 4, 73, 0)),
                    () -> true, calls::incrementAndGet);
            });
        assertEquals("denied", result.code()); assertEquals(0, calls.get());
    }
    @Test void UnboundedCountsAreRejectedBeforePreparingOrAllocatingNativeRequests() {
        var prepared = new AtomicInteger();
        var result = CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, Long.MAX_VALUE), 8, () -> true,
            (network, address, stacks) -> { prepared.incrementAndGet(); throw new AssertionError(); });
        assertEquals("capacity", result.code()); assertEquals(0, prepared.get());
    }
    @Test void NativeFailuresPropagateAsUncertainNotProofOfNoMaterial() {
        assertThrows(IllegalStateException.class, () -> CreatePackageDispatch.dispatch(NETWORK, "workshop", Map.of(stone, 4L), 8,
            () -> true, (network, address, stacks) -> new CreatePackageDispatch.Prepared(
                List.of(new CreatePackageDispatch.Promise(stone, 4, 73, 0)), () -> true,
                () -> { throw new IllegalStateException("may have packaged before failure"); })));
    }
}
