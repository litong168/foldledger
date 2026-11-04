import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 点击→画面变化 的延迟探针（桌面端"卡顿"取证用）。
 *
 * 用法：java scripts/LatencyProbe.java <点击X> <点击Y> <采样x> <采样y> <采样w> <采样h>
 *
 * 做三件事：
 *   1. 先截一小块基线；
 *   2. Robot 在指定坐标点一下（真实系统输入，和用户点一样）；
 *   3. 以最快速度反复截同一小块，记录画面第一次变化的时间差。
 * 采样区小（默认 400×300）才能做到逐帧级的判断。
 */
public class LatencyProbe {
    public static void main(String[] args) throws Exception {
        int cx = Integer.parseInt(args[0]);
        int cy = Integer.parseInt(args[1]);
        int rx = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        int ry = args.length > 3 ? Integer.parseInt(args[3]) : 200;
        int rw = args.length > 4 ? Integer.parseInt(args[4]) : 400;
        int rh = args.length > 5 ? Integer.parseInt(args[5]) : 300;

        Robot robot = new Robot();
        Rectangle rect = new Rectangle(rx, ry, rw, rh);

        BufferedImage base = robot.createScreenCapture(rect);
        long sum0 = sum(base);

        robot.mouseMove(cx, cy);
        robot.delay(120);
        long t0 = System.nanoTime();
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(35);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);

        long firstChange = -1;
        int samples = 0;
        while (System.nanoTime() - t0 < 3_000_000_000L) {
            BufferedImage cur = robot.createScreenCapture(rect);
            samples++;
            if (delta(base, cur) > 0) {   // 有任何像素变化
                firstChange = (System.nanoTime() - t0) / 1_000_000;
                break;
            }
        }
        // 再量一次"完全稳定"：连续两帧相同
        System.out.println("首帧变化: " + firstChange + " ms（采样 " + samples + " 次）");
    }

    static long sum(BufferedImage img) {
        long s = 0;
        for (int y = 0; y < img.getHeight(); y += 2)
            for (int x = 0; x < img.getWidth(); x += 2)
                s += img.getRGB(x, y);
        return s;
    }

    static int delta(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2)
            for (int x = 0; x < a.getWidth(); x += 2)
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }
}
