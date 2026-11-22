package com.nobodiiiii.createbiotech.content.biocomputing.manual;

import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Client-local action: no menu switch, player inventory, packet or server permission required. */
public final class ManualButton {
    private ManualButton() {}
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.manual." + key, args);
    }
    public static CreateTextButton at(Font font, int x, int y, int width, int height, ManualFile topic) {
        var hint = text("hint", topic.entry, topic.page);
        var button = new CreateTextButton(font, x, y, width, height, text("open"), () -> open(topic, Screen.hasShiftDown()), true);
        button.setTooltip(Tooltip.create(hint));
        button.setToolTip(hint);
        return button;
    }
    private static void open(ManualFile topic, boolean copy) {
        var client = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                var path = topic.extract(client.gameDirectory.toPath()).toAbsolutePath();
                if (!copy) {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                        Desktop.getDesktop().open(path.toFile());
                    else Util.getPlatform().openFile(path.toFile());
                }
                return path;
            } catch (IOException e) { throw new CompletionException(e); }
        }, Util.ioPool()).whenCompleteAsync((path, error) -> {
            Component message;
            if (error != null) {
                LogUtils.getLogger().warn("Could not open BC-01 ({})", topic.entry, error);
                message = text(copy ? "copy_failed" : "failed");
            } else if (copy) {
                client.keyboardHandler.setClipboard(path.toString());
                message = text("copied");
            } else message = text("location", topic.entry, topic.page);
            SystemToast.add(client.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, text("title"), message);
        }, client);
    }
}
