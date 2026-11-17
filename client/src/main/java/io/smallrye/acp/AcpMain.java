package io.smallrye.acp;

import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandResult;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class AcpMain implements QuarkusApplication {

    @Override
    public int run(String... args) throws Exception {
        CommandResult result = AeshRuntimeRunner.builder()
                .command(AcpCommands.class)
                .args(args)
                .execute();
        // propagate the command outcome as the process exit status
        return result == null ? 0 : result.getExitCode();
    }
}
