package com.nobodiiiii.createbiotech.foundation.gui;

import com.nobodiiiii.createbiotech.CreateBiotech;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

/** Native Create recipe-board geometry, backed by a resource-pack replaceable copy of its atlas. */
public final class PatternGaugeLayout {
    public static final int WIDTH=200, HEIGHT=160;
    public static final ResourceLocation TEXTURE=CreateBiotech.asResource("textures/gui/pattern_factory_gauge.png");
    private PatternGaugeLayout() {}

    public static Rect2i input(int x,int y,int slot) {
        if(slot<0||slot>=9)throw new IllegalArgumentException("Not a visible recipe input slot");
        return new Rect2i(x+67+slot%3*20,y+27+slot/3*20,18,18);
    }
    public static Rect2i output(int x,int y) {return new Rect2i(x+159,y+47,18,18);}

    public static void render(GuiGraphics graphics,int x,int y) {
        graphics.blit(TEXTURE,x,y,32,0,192,96,256,256);
        graphics.blit(TEXTURE,x,y+96,32,176,200,64,256,256);
        // This draft has no Create promise/expiration state. Reuse the atlas's neutral footer
        // instead of showing inoperative promise fields or fabricated stock values.
        graphics.blit(TEXTURE,x+1,y+129,142,29,34,214,48,22,256,256);
    }
}
