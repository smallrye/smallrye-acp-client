package io.smallrye.agentclientprotocol.sdk.registry;

public interface OutputHandler {

    void info(String message);

    void error(String message);

    static OutputHandler console() {
        return new OutputHandler() {
            @Override
            public void info(String message) {
                System.out.println(message);
            }

            @Override
            public void error(String message) {
                System.err.println(message);
            }
        };
    }
}