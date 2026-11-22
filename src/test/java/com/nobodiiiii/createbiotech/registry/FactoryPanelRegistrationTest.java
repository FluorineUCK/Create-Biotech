package com.nobodiiiii.createbiotech.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.List;
import java.util.Map;

import com.nobodiiiii.createbiotech.CreateBiotech;
import net.minecraft.world.MenuProvider;

class FactoryPanelRegistrationTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void onlyTheValidatedMaterialServiceExposesAPanelMenu() {
        var ids=CBMenuTypes.MENU_TYPES.getEntries().stream().map(holder -> holder.getId()).toList();
        org.junit.jupiter.api.Assertions.assertFalse(ids.contains(CreateBiotech.asResource("factory_panel")),
            "The retired orchestration menu must not be registered");
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains(CreateBiotech.asResource("biocomputing_production_port")));
        assertEquals(CreateBiotech.asResource("factory_panel"), CBBlocks.MATERIAL_REQUEST_PORT.getId());
        org.junit.jupiter.api.Assertions.assertFalse(MenuProvider.class.isAssignableFrom(
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortBlockEntity.class),
            "Vanilla block-entity menu fallback must not bypass the access-checked production port opener");
    }
}
