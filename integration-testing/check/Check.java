/**
 * A single integration test check against an ACP agent.
 */
public interface Check {

    String name();

    CheckResult run(AcpTestConfig config) throws Exception;

    record CheckResult(String name, boolean passed, String detail) {

        static CheckResult pass(String name, String detail) {
            return new CheckResult(name, true, detail);
        }

        static CheckResult fail(String name, String detail) {
            return new CheckResult(name, false, detail);
        }

        void print() {
            String icon = passed ? "PASS" : "FAIL";
            System.out.printf("  [%s] %s — %s%n", icon, name, detail);
        }
    }
}
