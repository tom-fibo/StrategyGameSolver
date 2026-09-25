/**
 * Environment check: confirms Java runs, assertions are on, and shows the
 * resources available to the engine (cores for threads, heap for tables/trees).
 */
public class Main {
    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true; // only executes when the JVM runs with -ea

        Runtime rt = Runtime.getRuntime();
        System.out.println("Java version : " + System.getProperty("java.version"));
        System.out.println("CPU cores    : " + rt.availableProcessors());
        System.out.println("Max heap     : " + rt.maxMemory() / (1024 * 1024) + " MB");
        System.out.println("Assertions   : " + (assertionsOn ? "on" : "OFF (add -ea to the JVM args)"));
    }
}
