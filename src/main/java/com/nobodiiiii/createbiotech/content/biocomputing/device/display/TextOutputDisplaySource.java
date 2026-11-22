package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Uses Create's native refresh, target layout, kinetic requirement and line reservations. */
public final class TextOutputDisplaySource extends DisplaySource {
    @Override public void transferData(DisplayLinkContext context, DisplayTarget target, int line) {
        // Unavailable/corrupt is not an empty page. Only a valid accepted empty page clears a target.
        if (!context.level().isLoaded(context.getSourcePos())
            || !(context.getSourceBlockEntity() instanceof TextOutputBlockEntity output) || !output.available()) return;
        super.transferData(context, target, line);
    }
    @Override public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!context.level().isLoaded(context.getSourcePos())) return List.of();
        if (!(context.getSourceBlockEntity() instanceof TextOutputBlockEntity output)) return List.of();
        return output.displayLines().stream().map(Component::literal).toList();
    }
}
