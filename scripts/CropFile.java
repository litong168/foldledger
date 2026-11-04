import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 裁剪「已保存的图片文件」并放大（CropZoom 是抓实时屏幕，两者别混用）。java scripts/CropFile.java in.png x y w h scale out.png */
public class CropFile {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
        int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
        int s = Integer.parseInt(a[5]);
        BufferedImage part = src.getSubimage(x, y, w, h);
        BufferedImage out = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(part, 0, 0, w * s, h * s, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[6]));
        System.out.println("saved " + a[6] + " from " + a[0]);
    }
}
