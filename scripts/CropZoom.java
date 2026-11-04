import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 截一小块并放大保存，用来肉眼核对图标这种细节。java scripts/CropZoom.java x y w h scale out.png */
public class CropZoom {
    public static void main(String[] a) throws Exception {
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]);
        int w = Integer.parseInt(a[2]), h = Integer.parseInt(a[3]);
        int s = Integer.parseInt(a[4]);
        BufferedImage img = new Robot().createScreenCapture(new Rectangle(x, y, w, h));
        BufferedImage out = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, 0, 0, w * s, h * s, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[5]));
        System.out.println("saved " + a[5] + " " + (w * s) + "x" + (h * s));
    }
}
