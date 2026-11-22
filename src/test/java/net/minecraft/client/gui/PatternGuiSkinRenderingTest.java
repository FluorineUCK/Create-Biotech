package net.minecraft.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

/** Runs the actual skin -> vanilla sprite traversal, replacing only the final GPU submission. */
class PatternGuiSkinRenderingTest {
    private Object previousMinecraft;
    @BeforeEach void preserveClientSingleton() throws Exception {
        previousMinecraft = field(Minecraft.class, "instance").get(null);
    }
    @AfterEach void restoreClientSingleton() throws Exception {
        field(Minecraft.class, "instance").set(null, previousMinecraft);
    }
    @Test void editorWindowSkinHasBoundedDrawSubmissions() throws Exception {
        RecordingGraphics graphics = graphics();
        long start = System.nanoTime();
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, 0, 0, 560, 360);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, 0, 0, 560, 21);
        System.out.println("[skin-perf] editor frame submissions=" + graphics.submissions
            + " traversalNs=" + (System.nanoTime() - start));
        assertTrue(graphics.submissions <= 64,
            "An empty editor must not emit one GPU submission per source pixel: " + graphics.submissions);
    }

    @Test void coreWindowSkinHasBoundedDrawSubmissions() throws Exception {
        RecordingGraphics graphics = graphics();
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, 0, 0, 480, 316);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, 0, 0, 480, 23);
        System.out.println("[skin-perf] Core frame submissions=" + graphics.submissions);
        assertTrue(graphics.submissions <= 64, "Core skin submissions: " + graphics.submissions);
    }

    @Test void shippedSkinsKeepVanillaPixelsAtWindowNodeAndSmallSizes() throws Exception {
        RecordingGraphics graphics = graphics();
        for (ResourceLocation id : graphics.testSprites.scalings.keySet()) {
            for (int[] size : new int[][]{{100, 38}, {480, 316}, {1, 1}, {3, 10}, {5, 5}, {7, 3}}) {
                assertMatchesVanilla(graphics, id, size[0], size[1]);
            }
        }
    }

    @Test void higherResolutionReplacementDoesNotStretchAWholePatternedTile() throws Exception {
        RecordingGraphics graphics = graphics();
        BufferedImage replacement = new BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 6; x++) for (int y = 0; y < 6; y++)
            replacement.setRGB(x, y, 0xff000000 | x * 30 << 16 | y * 30);
        graphics.testSprites.images.put(PatternGuiSkin.SURFACE, replacement);
        assertMatchesVanilla(graphics, PatternGuiSkin.SURFACE, 31, 23);
    }

    @Test void changingResourceMetadataIsObservedWithoutASkinCache() throws Exception {
        RecordingGraphics graphics = graphics();
        assertMatchesVanilla(graphics, PatternGuiSkin.SURFACE, 31, 23);
        graphics.testSprites.scalings.put(PatternGuiSkin.SURFACE, new GuiSpriteScaling.Stretch());
        assertMatchesVanilla(graphics, PatternGuiSkin.SURFACE, 31, 23);
    }

    @Test void nonzeroAtlasOffsetsKeepTheSamePixels() throws Exception {
        RecordingGraphics graphics = graphics();
        graphics.testSprites.atlasWidth = 256; graphics.testSprites.atlasHeight = 512;
        graphics.testSprites.originX = 37; graphics.testSprites.originY = 71;
        for (ResourceLocation id : graphics.testSprites.scalings.keySet())
            assertMatchesVanilla(graphics, id, 117, 43);
    }

    @Test void oneTexelWideButMisalignedRegionMustKeepRepeating() throws Exception {
        RecordingGraphics graphics = graphics();
        BufferedImage replacement = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        replacement.setRGB(0, 0, 0xffff0000); replacement.setRGB(1, 0, 0xff00ff00);
        replacement.setRGB(0, 1, 0xff0000ff); replacement.setRGB(1, 1, 0xffffffff);
        graphics.testSprites.images.put(PatternGuiSkin.SURFACE, replacement);
        graphics.testSprites.scalings.put(PatternGuiSkin.SURFACE,
            new GuiSpriteScaling.NineSlice(4, 4, new GuiSpriteScaling.NineSlice.Border(1, 1, 1, 1)));
        // Center spans physical [0.5, 1.5]: length one is not the same as one aligned texel.
        assertMatchesVanilla(graphics, PatternGuiSkin.SURFACE, 20, 12);
    }

    @Test void emptySurfacesDoNotSubmitAnyQuads() throws Exception {
        RecordingGraphics graphics = graphics();
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, 0, 0, 0, 20);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, 0, 0, 20, 0);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, 0, 0, -1, 20);
        assertEquals(0, graphics.submissions);
    }

    private static void assertMatchesVanilla(RecordingGraphics graphics, ResourceLocation id,
        int width, int height) {
        graphics.capture(width, height);
        graphics.blitSprite(id, 0, 0, width, height);
        int[] vanilla = graphics.pixels;
        graphics.capture(width, height);
        PatternGuiSkin.blit(graphics, id, 0, 0, width, height);
        assertArrayEquals(vanilla, graphics.pixels, id + " " + width + "x" + height);
    }

    private static RecordingGraphics graphics() throws Exception {
        RecordingGraphics graphics = allocate(RecordingGraphics.class);
        TestSprites sprites = allocate(TestSprites.class);
        sprites.scalings = new HashMap<>();
        sprites.images = new HashMap<>();
        sprites.sprite = allocate(TextureAtlasSprite.class);
        sprites.contents = allocate(SpriteContents.class);
        field(TextureAtlasSprite.class, "contents").set(sprites.sprite, sprites.contents);
        field(TextureAtlasSprite.class, "atlasLocation").set(sprites.sprite,
            ResourceLocation.withDefaultNamespace("textures/atlas/gui.png"));
        field(TextureAtlasSprite.class, "u1").setFloat(sprites.sprite, 1);
        field(TextureAtlasSprite.class, "v1").setFloat(sprites.sprite, 1);
        for (String name : new String[]{"surface", "header", "slot", "slot_selected", "button/normal",
            "button/hovered", "button/pressed", "button/disabled", "button/toggled"}) {
            var metadata = JsonParser.parseString(Files.readString(Path.of(
                "art/biocomputing/pack/assets/create_biotech/textures/gui/sprites/pattern/" + name + ".png.mcmeta")))
                .getAsJsonObject().getAsJsonObject("gui").get("scaling");
            sprites.scalings.put(ResourceLocation.fromNamespaceAndPath("create_biotech", "pattern/" + name),
                GuiSpriteScaling.CODEC.parse(JsonOps.INSTANCE, metadata).getOrThrow());
            sprites.images.put(ResourceLocation.fromNamespaceAndPath("create_biotech", "pattern/" + name),
                ImageIO.read(Path.of("art/biocomputing/pack/assets/create_biotech/textures/gui/sprites/pattern/" + name + ".png").toFile()));
        }
        field(GuiGraphics.class, "sprites").set(graphics, sprites);
        graphics.testSprites = sprites;
        Minecraft minecraft = allocate(Minecraft.class);
        field(Minecraft.class, "guiSprites").set(minecraft, sprites);
        field(Minecraft.class, "instance").set(null, minecraft);
        return graphics;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        // No Minecraft singleton, texture manager, or OpenGL context is needed for this traversal.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeType, "theUnsafe").get(null);
        return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static final class TestSprites extends GuiSpriteManager {
        Map<ResourceLocation, GuiSpriteScaling> scalings;
        Map<ResourceLocation, BufferedImage> images;
        TextureAtlasSprite sprite;
        SpriteContents contents;
        BufferedImage image;
        int atlasWidth, atlasHeight, originX, originY;
        GuiSpriteScaling selected;
        private TestSprites() { super(null); }
        @Override public TextureAtlasSprite getSprite(ResourceLocation id) {
            selected = scalings.get(id); image = images.get(id);
            try {
                field(SpriteContents.class, "width").setInt(contents, image.getWidth());
                field(SpriteContents.class, "height").setInt(contents, image.getHeight());
                int aw = atlasWidth == 0 ? image.getWidth() : atlasWidth;
                int ah = atlasHeight == 0 ? image.getHeight() : atlasHeight;
                field(TextureAtlasSprite.class, "x").setInt(sprite, originX);
                field(TextureAtlasSprite.class, "y").setInt(sprite, originY);
                field(TextureAtlasSprite.class, "u0").setFloat(sprite, (float) originX / aw);
                field(TextureAtlasSprite.class, "u1").setFloat(sprite, (float) (originX + image.getWidth()) / aw);
                field(TextureAtlasSprite.class, "v0").setFloat(sprite, (float) originY / ah);
                field(TextureAtlasSprite.class, "v1").setFloat(sprite, (float) (originY + image.getHeight()) / ah);
            } catch (Exception exception) { throw new AssertionError(exception); }
            return sprite;
        }
        @Override public GuiSpriteScaling getSpriteScaling(TextureAtlasSprite ignored) { return selected; }
    }

    private static final class RecordingGraphics extends GuiGraphics {
        int submissions;
        TestSprites testSprites;
        int[] pixels;
        int captureWidth, captureHeight;
        void capture(int width, int height) {
            captureWidth = width; captureHeight = height; pixels = new int[width * height];
        }
        private RecordingGraphics() { super(null, null); }
        @Override void innerBlit(ResourceLocation atlas, int x1, int x2, int y1, int y2, int z,
            float minU, float maxU, float minV, float maxV) {
            submissions++;
            if (pixels == null) return;
            BufferedImage image = testSprites.image;
            for (int x = Math.max(0, x1); x < Math.min(captureWidth, x2); x++) {
                for (int y = Math.max(0, y1); y < Math.min(captureHeight, y2); y++) {
                    double u = minU + (maxU - minU) * ((x - x1 + 0.5) / (x2 - x1));
                    double v = minV + (maxV - minV) * ((y - y1 + 0.5) / (y2 - y1));
                    int aw = testSprites.atlasWidth == 0 ? image.getWidth() : testSprites.atlasWidth;
                    int ah = testSprites.atlasHeight == 0 ? image.getHeight() : testSprites.atlasHeight;
                    int sx = Math.clamp((long) Math.floor(u * aw) - testSprites.originX, 0, image.getWidth() - 1);
                    int sy = Math.clamp((long) Math.floor(v * ah) - testSprites.originY, 0, image.getHeight() - 1);
                    pixels[y * captureWidth + x] = image.getRGB(sx, sy);
                }
            }
        }
    }
}
