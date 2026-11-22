package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CapturedResidentHardwareTest {

	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		field.setAccessible(true); field.setBoolean(null, true);
	}

	@Test void frozenVillagerSelectsItsOwnProfessionAndLevelWithoutMutatingTheItem() {
		var stack = captured(villager("minecraft:librarian", 3));
		var before = stack.get(DataComponents.CUSTOM_DATA);
		var source = new CapturedResidentHardware(() -> stack);
		var selected = source.select(config(), false);
		assertEquals(HardwareSelection.Status.READY, selected.status());
		assertEquals("control", selected.profile());
		assertEquals(128, selected.limits().hardware().programUnits());
		assertEquals(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
			ResourceLocation.parse("minecraft:librarian"), 3), selected.resident().orElseThrow());
		assertSame(before, stack.get(DataComponents.CUSTOM_DATA));
		assertEquals(before.copyTag(), CBItemData.get(stack));
	}

	@Test void replacingCustomDataOnTheSameItemInvalidatesTheIdentityCache() {
		var stack = captured(villager("minecraft:librarian", 1));
		var source = new CapturedResidentHardware(() -> stack); var config = config();
		assertEquals("control", source.select(config, false).profile());
		CBItemData.set(stack, itemData(villager("minecraft:toolsmith", 4)));
		var changed = source.select(config, false);
		assertEquals("toolsmith", changed.profile());
		assertEquals(144, changed.limits().hardware().workPerSlice());
		assertEquals(4, changed.resident().orElseThrow().level());
		stack.remove(DataComponents.CUSTOM_DATA);
		assertEquals(HardwareSelection.Status.INVALID_RESIDENT, source.select(config, false).status());
	}

	@Test void noResidentMalformedDataAndUnknownIdentityAreNotTheSameResult() {
		var stack = new AtomicReference<>(ItemStack.EMPTY);
		var source = new CapturedResidentHardware(stack::get); var config = config();
		assertEquals(HardwareSelection.Status.NO_RESIDENT, source.select(config, false).status());
		stack.set(new ItemStack(Items.PAPER));
		assertEquals(HardwareSelection.Status.INVALID_RESIDENT, source.select(config, false).status());
		var unknown = new CompoundTag(); unknown.putString("id", "example:resident");
		stack.set(captured(unknown));
		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT, source.select(config, false).status());
		stack.set(captured(villager("example:librarian", 1)));
		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT, source.select(config, false).status());
		stack.set(ItemStack.EMPTY);
		assertEquals(HardwareSelection.Status.NO_RESIDENT, source.select(config, false).status());
	}

	@Test void malformedVillagerFieldsAndBabiesCannotBecomeDefaultHardware() {
		var variants = new ArrayList<CompoundTag>();
		var missing = villager("minecraft:librarian", 1); missing.getCompound("VillagerData").remove("level"); variants.add(missing);
		var wrongType = villager("minecraft:librarian", 1); wrongType.getCompound("VillagerData").putString("level", "1"); variants.add(wrongType);
		variants.add(villager("minecraft:librarian", 0)); variants.add(villager("minecraft:librarian", 6));
		variants.add(villager("librarian", 1)); variants.add(villager("BAD:profession", 1)); variants.add(villager("minecraft:", 1));
		var baby = villager("minecraft:librarian", 1); baby.putInt("Age", -1); variants.add(baby);
		var wrongAge = villager("minecraft:librarian", 1); wrongAge.putString("Age", "0"); variants.add(wrongAge);
		for (var entity : variants) {
			var source = new CapturedResidentHardware(() -> captured(entity));
			assertEquals(HardwareSelection.Status.INVALID_RESIDENT, source.select(config(), false).status(), entity::toString);
		}
	}

	@Test void specialResidentsHaveNoInventedProfessionProgressionAndZombieUsesDegradedHardware() {
		var trader = new CompoundTag(); trader.putString("id", "minecraft:wandering_trader"); trader.putInt("Age", 0);
		trader.put("VillagerData", villager("minecraft:librarian", 5).getCompound("VillagerData"));
		var selection = new CapturedResidentHardware(() -> captured(trader)).select(config(), false);
		assertEquals("trader", selection.profile()); assertEquals(1, selection.resident().orElseThrow().level());
		var zombie = new CompoundTag(); zombie.putString("id", "minecraft:zombie_villager"); zombie.putBoolean("IsBaby", false);
		var source = new CapturedResidentHardware(() -> captured(zombie));
		assertEquals("zombie", source.select(config(), false).profile());
		zombie.putBoolean("IsBaby", true);
		assertEquals(HardwareSelection.Status.INVALID_RESIDENT, source.select(config(), false).status());
	}

	private static BiocomputingConfig config() { return new BiocomputingConfig(new ModConfigSpec.Builder()); }
	private static CompoundTag villager(String profession, int level) {
		var entity = new CompoundTag(); entity.putString("id", "minecraft:villager"); entity.putInt("Age", 0);
		var data = new CompoundTag(); data.putString("profession", profession); data.putInt("level", level);
		entity.put("VillagerData", data); return entity;
	}
	private static CompoundTag itemData(CompoundTag entity) { var data = new CompoundTag(); data.put("CapturedEntity", entity.copy()); return data; }
	private static ItemStack captured(CompoundTag entity) { var stack = new ItemStack(Items.PAPER); CBItemData.set(stack, itemData(entity)); return stack; }
}
