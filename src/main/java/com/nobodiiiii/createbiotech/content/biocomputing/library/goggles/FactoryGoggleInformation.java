package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.List;
import net.minecraft.network.chat.Component;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
/** Only the immutable synchronized normal state is read while rendering either tooltip mode. */
public interface FactoryGoggleInformation extends IHaveGoggleInformation {
	FactoryGoggleState normalGoggles();
	FactoryGoggleMetadata advancedGoggleMetadata();
	@Override default boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
		var lines=com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryGoggleReadout.lines(normalGoggles()); tooltip.addAll(lines); return !lines.isEmpty();
	}
}
