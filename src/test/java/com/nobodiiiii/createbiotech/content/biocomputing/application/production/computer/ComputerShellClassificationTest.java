package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.block.TransparentComputerCasingBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

class ComputerShellClassificationTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void anyBlockImplementingShellContractClassifiesAsCasing() {
		ComputerCasingBlock opaque = new ComputerCasingBlock(BlockBehaviour.Properties.of());
		Block testTransparentShell = new TestTransparentShell();
		Predicate<BlockState> noTags = ignored -> false;

		assertEquals(ComputerStructureScanner.CellKind.CASING,
			ComputerWorldView.classify(opaque.defaultBlockState(), noTags));
		assertEquals(ComputerStructureScanner.CellKind.CASING,
			ComputerWorldView.classify(testTransparentShell.defaultBlockState(), noTags));
	}

	@Test
	void transparentCasingDeclaresItsIdAndShellBehavior() {
		TransparentComputerCasingBlock block = new TransparentComputerCasingBlock(BlockBehaviour.Properties.of());
		Predicate<BlockState> noTags = ignored -> false;

		assertEquals(CreateBiotech.asResource("transparent_computer_casing"),
			CBBlocks.TRANSPARENT_COMPUTER_CASING.getId());
		assertEquals(ComputerShellMaterial.TRANSPARENT,
			block.shellMaterial(block.defaultBlockState()));
		assertEquals(ComputerStructureScanner.CellKind.CASING,
			ComputerWorldView.classify(block.defaultBlockState(), noTags));
	}

	@Test void materialMigrationKeepsExactHistoricalSchemaIdentities() {
		var registries = net.minecraft.core.RegistryAccess.EMPTY;
		String historical = "com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerShellMaterial";
		for (var material : ComputerShellMaterial.values()) {
			var encoded = com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(material, registries);
			assertEquals(historical, encoded.getString("Type"));
			assertEquals(historical, encoded.getCompound("Value").getString("Type"));
			assertEquals(material, com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(encoded, registries, ComputerShellMaterial.class).orElseThrow());
			var alias = encoded.copy(); alias.putString("Type", ComputerShellMaterial.class.getName());
			org.junit.jupiter.api.Assertions.assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(alias, registries, ComputerShellMaterial.class).isEmpty());
			var nested = encoded.copy(); nested.getCompound("Value").putString("Type", ComputerShellMaterial.class.getName());
			org.junit.jupiter.api.Assertions.assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(nested, registries, ComputerShellMaterial.class).isEmpty());
		}
	}

	private static final class TestTransparentShell extends Block implements ComputerShellBlock {
		private TestTransparentShell() {
			super(BlockBehaviour.Properties.of());
		}

		@Override
		public ComputerShellMaterial shellMaterial(BlockState state) {
			return ComputerShellMaterial.TRANSPARENT;
		}
	}
}
