import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Main {
    static final List<byte[]> LEAK = new ArrayList<>();
    public static void main(String[] args) throws InterruptedException {
        long pid = ProcessHandle.current().pid();
        System.out.println("[TargetApp] PID=" + pid);
        System.out.println("[TargetApp] Running. Press Ctrl+C to exit.");

        while (true) {
            // 관찰이 잘 되도록 메모리 할당/해제 패턴
            byte[] block = new byte[ThreadLocalRandom.current().nextInt(256 * 1024, 512 * 1024)];
            LEAK.add(block);
            if (LEAK.size() > 200) LEAK.clear(); // 완전 누수 방지 + GC 유도
            Thread.sleep(1000);
        }
    }
}