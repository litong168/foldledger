import java.awt.Robot;
import java.awt.event.KeyEvent;

/** 按 Win+D 显示桌面（Robot 注入，等价于用户按键）。java scripts/ShowDesktop.java */
public class ShowDesktop {
    public static void main(String[] a) throws Exception {
        Robot r = new Robot();
        r.keyPress(KeyEvent.VK_WINDOWS);
        r.delay(60);
        r.keyPress(KeyEvent.VK_D);
        r.delay(60);
        r.keyRelease(KeyEvent.VK_D);
        r.keyRelease(KeyEvent.VK_WINDOWS);
        r.delay(600);
        System.out.println("sent Win+D");
    }
}
