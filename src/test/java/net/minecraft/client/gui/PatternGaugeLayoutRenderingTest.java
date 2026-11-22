package net.minecraft.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Runs the real atlas blits; only the GPU submission is replaced with a pixel buffer. */
class PatternGaugeLayoutRenderingTest {
    @Test void nativeRecipeBoardAndAddressKeepTheirPixelsWithThreeAtlasSubmissions() throws Exception {
        Class<?> layout=Class.forName("com.nobodiiiii.createbiotech.foundation.gui.PatternGaugeLayout");
        var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        var graphics=(Pixels)((sun.misc.Unsafe)f.get(null)).allocateInstance(Pixels.class);
        graphics.result=new BufferedImage(200,160,BufferedImage.TYPE_INT_ARGB);
        layout.getMethod("render",GuiGraphics.class,int.class,int.class).invoke(null,graphics,0,0);
        assertEquals(3,graphics.submissions,"native-sized atlas blits, not tiled per texel");
        assertEquals(graphics.source.getRGB(129,5),graphics.result.getRGB(97,5));
        assertEquals(graphics.source.getRGB(108,36),graphics.result.getRGB(76,36));
        assertEquals(graphics.source.getRGB(142,189),graphics.result.getRGB(110,109));
        ImageIO.write(graphics.result,"png",Path.of(System.getProperty("java.io.tmpdir"),"pattern-gauge-frame.png").toFile());
    }
    private static final class Pixels extends GuiGraphics {
        int submissions;BufferedImage source,result;
        private Pixels(){super(null,null);}
        @Override void innerBlit(ResourceLocation texture,int x1,int x2,int y1,int y2,int z,float u0,float u1,float v0,float v1) {
            assertEquals("create_biotech",texture.getNamespace());
            try {if(source==null)source=ImageIO.read(Path.of("src/main/resources/assets",texture.getNamespace(),texture.getPath()).toFile());}
            catch(Exception e){throw new AssertionError(e);}
            submissions++;
            for(int x=Math.max(0,x1);x<Math.min(result.getWidth(),x2);x++)for(int y=Math.max(0,y1);y<Math.min(result.getHeight(),y2);y++) {
                int sx=(int)((u0+(u1-u0)*(x-x1+.5)/(x2-x1))*source.getWidth());
                int sy=(int)((v0+(v1-v0)*(y-y1+.5)/(y2-y1))*source.getHeight());
                result.setRGB(x,y,source.getRGB(sx,sy));
            }
        }
    }
}
