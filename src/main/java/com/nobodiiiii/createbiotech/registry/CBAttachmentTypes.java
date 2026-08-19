package com.nobodiiiii.createbiotech.registry;

import java.util.function.Supplier;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.block.SuperButterStreak;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class CBAttachmentTypes {
	private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
		DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateBiotech.MOD_ID);

	public static final Supplier<AttachmentType<SuperButterStreak>> SUPER_BUTTER_STREAK =
		ATTACHMENT_TYPES.register("super_butter_streak",
			() -> AttachmentType.builder(SuperButterStreak::new).build());

	private CBAttachmentTypes() {}

	public static void register(IEventBus modEventBus) {
		ATTACHMENT_TYPES.register(modEventBus);
	}
}
