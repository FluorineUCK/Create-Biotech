package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Physical capture validation and bounded render metadata; never a program-accessible entity handle. */
public final class LibraryResidentCapture implements LibraryResident.CaptureAccess {
    // Existing client projection bound, retained by the recipe application's historical wire contract.
    private static final int MAX_NAME_CODE_POINTS = 64;
    private final Level level;
    public LibraryResidentCapture(Level level) { this.level = Objects.requireNonNull(level); }

	@Override public boolean isBox(ItemStack stack) {
		return CapturedEntityBoxItem.isBox(stack);
	}

	@Override public boolean hasCapturedEntity(ItemStack stack) {
		return CapturedEntityBoxHelper.hasCapturedEntity(stack);
	}

	@Override
	public LibraryResident.InstallResult inspect(ItemStack copied) {
		Tag raw = CBItemData.getOrEmpty(copied).get("CapturedEntity");
		if (!(raw instanceof CompoundTag captured)
			|| !captured.contains("id", Tag.TAG_STRING)
			|| !captured.hasUUID("UUID")) return LibraryResident.InstallResult.MALFORMED;
		if (!"minecraft:villager".equals(captured.getString("id")))
			return LibraryResident.InstallResult.UNSUPPORTED_ENTITY;
		Tag passengers = captured.get("Passengers");
		if (passengers != null && (!(passengers instanceof ListTag list) || !list.isEmpty()))
			return LibraryResident.InstallResult.UNSUPPORTED_ENTITY;
		UUID capturedId = captured.getUUID("UUID");
		MinecraftServer server = level.getServer();
		if (server != null)
			for (var candidate : server.getAllLevels())
				if (candidate.getEntity(capturedId) != null)
					return LibraryResident.InstallResult.DUPLICATE_IDENTITY;
		Entity decoded = CapturedEntityBoxHelper
			.createCapturedEntityPreservingUuid(copied, level);
		if (!(decoded instanceof Villager villager) || !decoded.getUUID().equals(capturedId))
			return LibraryResident.InstallResult.MALFORMED;
		if (villager.isBaby()) return LibraryResident.InstallResult.BABY;
		return villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN
			? LibraryResident.InstallResult.SUCCESS : LibraryResident.InstallResult.UNSUPPORTED_ENTITY;
	}

	@Override
	public boolean clearCapturedEntity(ItemStack held) {
		CapturedEntityBoxHelper.clearCapturedEntity(held);
		return !CapturedEntityBoxHelper.hasCapturedEntity(held);
	}

	public static Projection project(ItemStack snapshot,
		HolderLookup.Provider registries) {
		if (snapshot.isEmpty())
			return Projection.DEFAULT;
		CompoundTag itemData = CBItemData.get(snapshot);
		if (itemData == null || !itemData.contains("CapturedEntity", Tag.TAG_COMPOUND))
			return Projection.DEFAULT;
		CompoundTag entity = itemData.getCompound("CapturedEntity");
		if (!entity.contains("id", Tag.TAG_STRING)
			|| !"minecraft:villager".equals(entity.getString("id"))
			|| !entity.contains("VillagerData", Tag.TAG_COMPOUND))
			return Projection.DEFAULT;
		CompoundTag data = entity.getCompound("VillagerData");
		if (!data.contains("type", Tag.TAG_STRING)
			|| !data.contains("profession", Tag.TAG_STRING)
			|| !data.contains("level", Tag.TAG_INT)
			|| !"minecraft:librarian".equals(data.getString("profession")))
			return Projection.DEFAULT;
		ResourceLocation type = ResourceLocation.tryParse(data.getString("type"));
		int level = data.getInt("level");
		if (type == null || !isKnownVillagerType(type) || level < 1 || level > 5)
			return Projection.DEFAULT;
		String customName = null;
		if (entity.contains("CustomName", Tag.TAG_STRING)) {
			try {
				Component decoded = Component.Serializer.fromJson(entity.getString("CustomName"), registries);
				if (decoded != null)
					customName = boundedPlainText(decoded.getString());
			} catch (RuntimeException ignored) {
				// A malformed optional name does not invalidate otherwise usable librarian metadata.
			}
		}
		return new Projection(true, type, level, customName);
	}

	private static boolean isKnownVillagerType(ResourceLocation type) {
		BuiltInRegistries.VILLAGER_TYPE.getKey(VillagerType.PLAINS);
		return BuiltInRegistries.VILLAGER_TYPE.containsKey(type);
	}

	private static String boundedPlainText(String value) {
		int codePoints = value.codePointCount(0, value.length());
		return codePoints <= MAX_NAME_CODE_POINTS ? value : value.substring(0, value.offsetByCodePoints(0, MAX_NAME_CODE_POINTS));
	}

    public record Projection(boolean valid, ResourceLocation type, int level, @Nullable String customName) {
        private static final Projection DEFAULT = new Projection(false,
            ResourceLocation.withDefaultNamespace("plains"), 1, null);
    }
}
