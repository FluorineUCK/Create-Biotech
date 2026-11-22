package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WritableBookContent;

class RemoteExpansionBoundsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void nestedExactComponentsHaveUnambiguousExpansionFingerprints() {
		CompoundTag first = new CompoundTag();
		CompoundTag firstNested = new CompoundTag();
		firstNested.putInt("x", 1);
		first.put("a", firstNested);
		first.putInt("y", 2);
		CompoundTag second = new CompoundTag();
		CompoundTag secondNested = new CompoundTag();
		secondNested.putInt("x", 1);
		secondNested.putInt("y", 2);
		second.put("a", secondNested);

		assertNotEquals(request(componentStack(first), 1, 1).fingerprint(),
			request(componentStack(second), 1, 1).fingerprint(),
			"compound boundaries must be framed in exact-stack protocol identities");
	}

	@Test
	void registryHolderExactComponentsAreCapturedWithTheRealRegistryBeforeDigesting() {
		RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(
			BuiltInRegistries.REGISTRY);
		StackKey healing = new StackKey(PotionContents.createItemStack(Items.POTION,
			Potions.HEALING));
		StackKey swiftness = new StackKey(PotionContents.createItemStack(Items.POTION,
			Potions.SWIFTNESS));

		assertDoesNotThrow(() -> healing.stack().saveOptional(registries),
			"the fixture must serialize through its authoritative registry access");
		RemoteExpansionRequest healingRequest = assertDoesNotThrow(() ->
			request(registries, healing, 1, 1),
			"remote expansion capture must not replace the real registry with RegistryAccess.EMPTY");
		RemoteExpansionRequest swiftnessRequest = request(registries, swiftness, 1, 1);
		assertNotEquals(healingRequest.exactStackSnapshot().entries().getFirst().fingerprint(),
			swiftnessRequest.exactStackSnapshot().entries().getFirst().fingerprint());
		assertNotEquals(healingRequest.fingerprint(), swiftnessRequest.fingerprint());
		byte[] callerBytes = healingRequest.exactStackSnapshot().entries().getFirst()
			.canonicalBytes();
		callerBytes[0] ^= 1;
		assertNotEquals(callerBytes[0], healingRequest.exactStackSnapshot().entries().getFirst()
			.canonicalBytes()[0], "canonical exact-stack bytes must be defensively immutable");
		CompoundTag encoded = CurrentSchemaStateNbt.save(healingRequest, registries);
		assertEquals(healingRequest, CurrentSchemaStateNbt.load(encoded, registries,
			RemoteExpansionRequest.class).orElseThrow());
		CompoundTag oldShape = encoded.copy();
		oldShape.getCompound("Value").remove("exactStackSnapshot");
		assertTrue(CurrentSchemaStateNbt.load(oldShape, registries,
			RemoteExpansionRequest.class).isEmpty(),
			"the current exact-stack snapshot schema has no legacy decoder");
	}

	@Test
	void requestRejectsExpansionCountOverflowBeforeItCanEnterTheWorker() {
		assertThrows(IllegalArgumentException.class,
			() -> request(new StackKey(new ItemStack(Items.IRON_INGOT)), 2, Long.MAX_VALUE));
	}

	@Test
	void requestRejectsAnOversizedExactComponentPayload() {
		CompoundTag oversized = new CompoundTag();
		oversized.putByteArray("payload", new byte[(1 << 20) + 1]);

		assertThrows(IllegalArgumentException.class,
			() -> request(componentStack(oversized), 1, 1));
	}

	@Test
	void requestBoundCountsTheCompleteStageAndDependencyManifestWirePayload() {
		CompoundTag repeated = new CompoundTag();
		repeated.putByteArray("payload", new byte[(RemoteExpansionRequest.MAX_SERIALIZED_BYTES / 2)
			+ 1024]);

		assertThrows(IllegalArgumentException.class,
			() -> request(componentStack(repeated), 1, 1),
			"the dependency manifest is a second on-wire exact-stack occurrence");
	}

	@Test
	void resultMirrorsThePatternRepresentationStageAndAggregateEntryBound() {
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		CreateRouteKey route = new CreateRouteKey(id(20), "input");
		ArrayList<RemoteExpansionResult.ExpandedStage> stages = new ArrayList<>();
		LinkedHashMap<StackKey, Long> aggregate = new LinkedHashMap<>();
		for (int index = 0; index <= WritableBookContent.PAGE_EDIT_LENGTH; index++) {
			stages.add(new RemoteExpansionResult.ExpandedStage(index, route, Map.of(iron, 1L)));
			aggregate.merge(iron, 1L, Math::addExact);
		}
		ChildExecutionRevision child = new ChildExecutionRevision(id(21), 1, 1,
			InputMode.BULK, InputMode.BULK, 1, 1, 0);
		RemoteExpansionStackSnapshot exactStacks = RemoteExpansionStackSnapshot.capture(
			RemoteExpansionTestRegistries.provider(), aggregate.keySet());
		RemoteExpansionRevision revision = new RemoteExpansionRevision(id(22), 2, id(23),
			1, id(24), 1, 1, child,
			RemoteExpansionDigest.expansion(stages, aggregate, exactStacks));

		assertThrows(IllegalArgumentException.class,
			() -> new RemoteExpansionResult(revision, stages, aggregate, exactStacks));
	}

	private static RemoteExpansionRequest request(StackKey ingredient, long count,
		long multiplier) {
		return request(RemoteExpansionTestRegistries.provider(), ingredient, count, multiplier);
	}

	private static RemoteExpansionRequest request(RegistryAccess registries, StackKey ingredient,
		long count, long multiplier) {
		CreateRouteKey route = new CreateRouteKey(id(10), "input");
		return RemoteExpansionRequest.capture(registries,
			id(11), id(12), 1, 2, id(13), 2, id(14), 3, InputMode.BULK, multiplier,
			List.of(new RemoteExpansionRequest.StageInput(0, route,
				Map.of(ingredient, count))), Map.of(ingredient, count));
	}

	private static StackKey componentStack(CompoundTag data) {
		ItemStack stack = new ItemStack(Items.IRON_INGOT);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return new StackKey(stack);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
