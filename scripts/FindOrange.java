import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 在桌面截图里找"橙色方块"（应用图标的底色），返回所有命中聚类的包围盒，
 * 用来定位桌面图标而不靠肉眼估坐标。
 * 用法：java scripts/FindOrange.java shot.png
 */
public class FindOrange {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int w = img.getWidth(), h = img.getHeight();
        int step = 4;
        int[][] grid = new int[(w / step) + 2][(h / step) + 2];
        int gw = grid.length, gh = grid[0].length;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                // 朱橙 ~ 琥珀：红高、绿中、蓝低，且足够饱和
                if (r > 200 && g > 70 && g < 190 && b < 110) grid[x / step][y / step] = 1;
            }
        }
        // 简单连通块（4 邻域）
        boolean[][] seen = new boolean[gw][gh];
        for (int i = 0; i < gw; i++) {
            for (int j = 0; j < gh; j++) {
                if (grid[i][j] == 0 || seen[i][j]) continue;
                int minI = i, maxI = i, minJ = j, maxJ = j, count = 0;
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[]{i, j}); seen[i][j] = true;
                while (!q.isEmpty()) {
                    int[] p = q.poll();
                    count++;
                    minI = Math.min(minI, p[0]); maxI = Math.max(maxI, p[0]);
                    minJ = Math.min(minJ, p[1]); maxJ = Math.max(maxJ, p[1]);
                    int[][] nb = {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= gw || n[1] >= gh) continue;
                        if (!seen[n[0]][n[1]] && grid[n[0]][n[1]] == 1) { seen[n[0]][n[1]] = true; q.add(n); }
                    }
                }
                int bw = (maxI - minI + 1) * step, bh = (maxJ - minJ + 1) * step;
                // 图标本体大概 40~200 px；太小的当噪点
                if (count > 20 && bw > 20 && bh > 20) {
                    System.out.printf("橙色块: x=%d y=%d w=%d h=%d 采样点=%d%n",
                            minI * step, minJ * step, bw, bh, count);
                }
            }
        }
    }
}
