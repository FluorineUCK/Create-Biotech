package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FactoryPanelAtlasTest {
    // Independent acceptance fixture: wrong UVs, rectangle edges or any stray pixel must fail.
    static final String SPEC = """
        WINDOW 0 0 256 192 20252b
        TITLE 0 192 256 24 15191d
        CARD_NORMAL 0 216 128 32 303943
        CARD_SELECTED 128 216 128 32 3d5266
        CARD_WAIT 0 248 128 32 665a35
        CARD_FAULT 128 248 128 32 663b3b
        NODE_NORMAL 0 280 128 24 34404a
        NODE_SELECTED 128 280 128 24 46657a
        NAV_NORMAL 256 0 32 32 3d5266
        NAV_SELECTED 256 32 32 32 5b7890
        SCROLL_TRACK 288 0 8 128 252b31
        SCROLL_THUMB 296 0 8 32 c49a55
        INPUT 304 0 160 20 181c20
        BUTTON_NORMAL 304 20 80 20 45535f
        BUTTON_PRESSED 384 20 80 20 2d3942
        POPUP 256 160 240 160 252b31
        STATE_IDLE 256 320 120 8 52606c
        STATE_RUN 256 328 120 8 5c9b63
        STATE_SLEEP 256 336 120 8 6a71a5
        STATE_FAULT 256 344 120 8 ad5555
        """;

    @Test void atlasHasExactDimensionsAndEveryRequiredPixel() throws Exception {
        Path resource = Path.of("src/main/resources/assets/create_biotech/textures/gui/factory_panel_monitor.png");
        assertTrue(Files.isRegularFile(resource), "replaceable atlas resource is required");
        BufferedImage image = ImageIO.read(resource.toFile());
        assertEquals(512, image.getWidth()); assertEquals(512, image.getHeight());
        int[] expected = new int[512 * 512];
        java.util.Arrays.fill(expected, 0xff20252b);
        for (String line : SPEC.strip().split("\n")) {
            String[] f = line.strip().split(" +");
            int x = Integer.parseInt(f[1]), y = Integer.parseInt(f[2]);
            int w = Integer.parseInt(f[3]), h = Integer.parseInt(f[4]);
            int color = 0xff000000 | Integer.parseInt(f[5], 16);
            for (int yy = y; yy < y + h; yy++)
                java.util.Arrays.fill(expected, yy * 512 + x, yy * 512 + x + w, color);
        }
        assertArrayEquals(expected, image.getRGB(0, 0, 512, 512, null, 0, 512));
    }

    @Test void sourceRegionsMatchResourceContractAndBlitScalesUvRectangle() throws Exception {
        Path source = FactoryPanelScreenSourceTest.SOURCE.resolve("FactoryPanelAtlas.java");
        assertTrue(Files.isRegularFile(source), "central atlas UV definitions are required");
        String text = Files.readString(source);
        for (String line : SPEC.strip().split("\n")) {
            String[] f = line.strip().split(" +");
            assertTrue(text.contains(f[0] + "(" + f[1] + ", " + f[2] + ", " + f[3] + ", " + f[4] + ")"), line);
        }
        assertTrue(text.contains("textures/gui/factory_panel_monitor.png"));
        assertTrue(text.contains("graphics.blit(TEXTURE, x, y, width, height, region.u, region.v,"));
        assertTrue(text.contains("region.width, region.height, SIZE, SIZE)"));
    }
}
