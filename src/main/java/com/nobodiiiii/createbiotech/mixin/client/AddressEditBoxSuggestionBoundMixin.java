package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.CommunicationFrequencyEditBox;
import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.logistics.AddressEditBoxHelper;
import com.simibubi.create.content.trains.schedule.DestinationSuggestions;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Prevent Create's constructor helper from eagerly scanning clipboard contents for our literal box. */
@Mixin(AddressEditBox.class)
public abstract class AddressEditBoxSuggestionBoundMixin {
	@Redirect(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/Font;IIIIZLjava/lang/String;)V",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/logistics/AddressEditBoxHelper;createSuggestions(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/components/EditBox;ZLjava/lang/String;)Lcom/simibubi/create/content/trains/schedule/DestinationSuggestions;"))
	private DestinationSuggestions createBiotech$boundFrequencyConstructorSuggestions(Screen screen,
		EditBox textBox, boolean anchorToBottom, String initialValue) {
		if ((Object) this instanceof CommunicationFrequencyEditBox)
			return CommunicationFrequencyEditBox.inertSuperclassSuggestions(screen, textBox,
				anchorToBottom);
		return AddressEditBoxHelper.createSuggestions(screen, textBox, anchorToBottom, initialValue);
	}
}
