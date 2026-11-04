import java.awt.*;

/** 打一次真实滚轮（Robot，和鼠标滚一格等价）。用法：java scripts/ScrollOne.java <x> <y> <notches> */
public class ScrollOne {
    public static void main(String[] args) throws Exception {
        int x = Integer.parseInt(args[0]);
        int y = Integer.parseInt(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        Robot r = new Robot();
        r.mouseMove(x, y);
        r.delay(200);
        r.mouseWheel(n);          // 正数 = 向下滚
        r.delay(50);
        System.out.println("scrolled " + n + " notch at " + x + "," + y);
    }
}
