import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 从 app/src/main/res/drawable/ic_launcher_*.xml 的矢量原稿重绘桌面端图标。
 *
 * 为什么要重绘：桌面端原来用的是 icon.png —— 一块**满铺直角方块**，
 * 任务栏里跟手机端的圆角图标完全不是一个东西。这里按同一份 108vp 视口重绘，
 * 再套上启动器那种圆角遮罩（radius = 23.5% 边长），做到两端一致。
 *
 * 产出：
 *   desktop/src/desktopMain/resources/icon.png   512×512（窗口/托盘图标）
 *   scripts/app-icon.ico                          多尺寸（exe 图标，16~256）
 *
 * 用法：java scripts/GenIcon.java
 */
public class GenIcon {

    /** 自适应图标视口 108×108；所有坐标直接沿用 xml 里的数值 */
    static final double VP = 108.0;
    /** 圆角半径占比：启动器圆角方形遮罩的常用值 */
    static final double CORNER = 0.235;

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        Path pngOut = Paths.get(root, "desktop/src/desktopMain/resources/icon.png");
        Path icoOut = Paths.get(root, "scripts/app-icon.ico");

        BufferedImage master = render(1024);
        ImageIO.write(master, "png", pngOut.toFile());
        System.out.println("PNG  " + pngOut + "  " + master.getWidth() + "x" + master.getHeight());

        int[] sizes = {256, 128, 64, 48, 32, 24, 16};
        List<byte[]> frames = new ArrayList<>();
        for (int s : sizes) {
            BufferedImage img = render(s);
            // 256 用 PNG 压缩，其余用 32 位 BMP（老版本 Explorer 对 PNG 小图兼容性差）
            frames.add(s >= 256 ? pngBytes(img) : dibBytes(img));
        }
        writeIco(icoOut.toFile(), sizes, frames);
        System.out.println("ICO  " + icoOut + "  " + Arrays.toString(sizes));
    }

    // ---------------- 绘制 ----------------

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double k = size / VP;
        g.scale(k, k);

        // 圆角遮罩：外面留透明
        double r = CORNER * VP;
        RoundRectangle2D mask = new RoundRectangle2D.Double(0, 0, VP, VP, r, r);
        g.clip(mask);

        // 背景：朱橙 → 亮橙 → 琥珀 → 金（对角线性）
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(0, 0), new Point2D.Double(VP, VP),
                new float[]{0f, 0.40f, 0.72f, 1f},
                new Color[]{new Color(0xF04E0A), new Color(0xFF7A18),
                        new Color(0xFFA51F), new Color(0xFFCE4A)}));
        g.fill(new Rectangle2D.Double(0, 0, VP, VP));

        // 右上角暖光：M108,0 L108,44 C86,40 70,24 66,0 Z
        Path2D glow = new Path2D.Double();
        glow.moveTo(108, 0);
        glow.lineTo(108, 44);
        glow.curveTo(86, 40, 70, 24, 66, 0);
        glow.closePath();
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(108, 0), new Point2D.Double(70, 40),
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 0x2E), new Color(255, 255, 255, 0x00)}));
        g.fill(glow);

        // 涟漪：两道扁椭圆
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xFF, 0xF6, 0xE0, 0x8C));
        g.draw(new Ellipse2D.Double(54 - 24, 73 - 6, 48, 12));
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xFF, 0xF6, 0xE0, 0x66));
        g.draw(new Ellipse2D.Double(54 - 13, 73 - 3.4, 26, 6.8));

        // 水滴主体：M54,24 C60.5,31.5 71,38 71,45 A17,17 0 1 1 37,45 C37,38 47.5,31.5 54,24 Z
        Path2D drop = new Path2D.Double();
        drop.moveTo(54, 24);
        drop.curveTo(60.5, 31.5, 71, 38, 71, 45);
        double kappa = 0.5522847498307936 * 17;
        // 下半圆：右 → 底 → 左
        drop.curveTo(71, 45 + kappa, 54 + kappa, 62, 54, 62);
        drop.curveTo(54 - kappa, 62, 37, 45 + kappa, 37, 45);
        drop.curveTo(37, 38, 47.5, 31.5, 54, 24);
        drop.closePath();
        g.setPaint(new GradientPaint(54, 29, Color.WHITE, 54, 62, new Color(0xFFE9C4)));
        g.fill(drop);

        // ¥：斜两笔 + 竖 + 两横
        g.setColor(new Color(0xEF5F0A));
        g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(48, 35, 54, 41));
        g.draw(new Line2D.Double(60, 35, 54, 41));
        g.draw(new Line2D.Double(54, 41, 54, 55));
        g.draw(new Line2D.Double(48, 45.5, 60, 45.5));
        g.draw(new Line2D.Double(48, 51, 60, 51));

        g.dispose();
        return img;
    }

    // ---------------- ICO 封装 ----------------

    static byte[] pngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** 32 位 BMP（BITMAPINFOHEADER + 自下而上的 BGRA + AND 掩码） */
    static byte[] dibBytes(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int rowSize = w * 4;
        int maskRow = ((w + 31) / 32) * 4;
        int size = 40 + rowSize * h + maskRow * h;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(size);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            le32(out, 40);              // biSize
            le32(out, w);               // biWidth
            le32(out, h * 2);           // biHeight（包含 AND 掩码）
            le16(out, 1);               // biPlanes
            le16(out, 32);              // biBitCount
            le32(out, 0);               // BI_RGB
            le32(out, rowSize * h + maskRow * h);
            le32(out, 0); le32(out, 0); le32(out, 0); le32(out, 0);
            for (int y = h - 1; y >= 0; y--) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    out.write(argb & 0xFF);          // B
                    out.write((argb >> 8) & 0xFF);   // G
                    out.write((argb >> 16) & 0xFF);  // R
                    out.write((argb >>> 24) & 0xFF); // A
                }
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < maskRow; x++) out.write(0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static void writeIco(File f, int[] sizes, List<byte[]> frames) throws IOException {
        Files.createDirectories(f.toPath().getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            le16(out, 0); le16(out, 1); le16(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int s = sizes[i];
                int len = frames.get(i).length;
                out.writeByte(s >= 256 ? 0 : s);   // 0 表示 256
                out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(0);                   // 调色板
                out.writeByte(0);                   // 保留
                le16(out, 1);                        // planes
                le16(out, 32);                       // bpp
                le32(out, len);
                le32(out, offset);
                offset += len;
            }
            for (byte[] b : frames) out.write(b);
        }
    }

    static void le16(DataOutputStream o, int v) throws IOException {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }

    static void le32(DataOutputStream o, int v) throws IOException {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }
}
