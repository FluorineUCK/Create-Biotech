package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class NewTaskDraftStateTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		try {
			Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loading.getMethod("get").invoke(null) == null)
				loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			flag.setAccessible(true); flag.setBoolean(null, true);
			Items.AIR.getDefaultInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void controlsStayDisabledUntilTheFirstCurrentContext() {
		NewTaskDraftState draft = new NewTaskDraftState();
		assertFalse(draft.controlsEnabled());
		assertFalse(draft.editOutput("minecraft:stone"));
		draft.initialize(null, "");
		assertTrue(draft.controlsEnabled());
		assertTrue(draft.editOutput("minecraft:stone"));
		draft.initialize(null, "minecraft:dirt");
		StackKey retained = new StackKey(new ItemStack(Items.STONE));
		assertEquals(retained, draft.outputKey(() -> retained));
	}

	@Test
	void unchangedComponentBearingPrefillKeepsItsExactStackKey() {
		ItemStack stack = new ItemStack(Items.DIAMOND);
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exact = new StackKey(stack);
		NewTaskDraftState draft = new NewTaskDraftState();
		draft.initialize(exact, "minecraft:diamond");
		assertEquals(exact, draft.outputKey(() -> new StackKey(new ItemStack(Items.DIAMOND))));
		draft.editOutput("minecraft:emerald");
		StackKey replacement = new StackKey(new ItemStack(Items.EMERALD));
		assertEquals(replacement, draft.outputKey(() -> replacement));
	}

	@Test
	void lateFailureCannotRestoreOverNewOutputOrOtherFieldEdits() {
		StackKey submitted = new StackKey(new ItemStack(Items.DIAMOND));
		StackKey edited = new StackKey(new ItemStack(Items.EMERALD));
		NewTaskDraftState draft = new NewTaskDraftState();
		draft.initialize(submitted, "minecraft:diamond");
		long submittedVersion = draft.editVersion();
		draft.editOutput("minecraft:emerald");
		assertFalse(draft.preserveIfUnedited(submittedVersion, submitted, "minecraft:diamond"));
		assertEquals(edited, draft.outputKey(() -> edited));
		long secondVersion = draft.editVersion();
		draft.markEdited(); // address/multiplier/mode edit: output need not change
		assertFalse(draft.preserveIfUnedited(secondVersion, submitted, "minecraft:diamond"));
		assertTrue(draft.preserveIfUnedited(draft.editVersion(), edited, "minecraft:emerald"));
	}

	@Test
	void failedSubmissionCanRestoreTheServerReturnedExactDraft() {
		ItemStack stack = new ItemStack(Items.DIAMOND);
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exact = new StackKey(stack);
		NewTaskDraftState draft = new NewTaskDraftState();
		draft.initialize(null, "minecraft:stone");
		draft.editOutput("minecraft:emerald");

		draft.preserve(exact, "minecraft:diamond");

		assertEquals(exact, draft.outputKey(() -> new StackKey(new ItemStack(Items.DIAMOND))));
	}
}
