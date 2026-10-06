package io.smallrye.agentclientprotocol.sdk.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.smallrye.agentclientprotocol.sdk.client.AcpAsyncClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ContentChunk;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.Implementation;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.PermissionOption;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.PermissionOptionKind;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.PromptRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.PromptResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.RequestPermissionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.SessionNotification;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.StopReason;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.TextContent;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ToolCallUpdate;

class MockAcpAgentTest {

    @Test
    void initializeHandshake() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            agent.on("initialize", params -> new InitializeResponse(
                    null,
                    null,
                    new Implementation("test-agent", "1.0"),
                    null,
                    1));

            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .build();
            client.connect().join();

            InitializeResponse resp = client.initialize().join();

            assertEquals("test-agent", resp.agentInfo().name());
            assertEquals("1.0", resp.agentInfo().version());
            assertEquals(1, resp.protocolVersion());

            List<JsonNode> requests = agent.requests();
            assertEquals(1, requests.size());
            assertEquals("initialize", requests.getFirst().get("method").asText());
            assertEquals("2.0", requests.getFirst().get("jsonrpc").asText());
        }
    }

    @Test
    void fullSessionLifecycle() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            agent.on("initialize", params -> new InitializeResponse(1));
            agent.on("session/new", params -> new NewSessionResponse("session-42"));
            agent.on("session/prompt", params -> new PromptResponse(StopReason.END_TURN));
            agent.on("session/close", params -> new CloseSessionResponse(null));

            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .build();
            client.connect().join();

            client.initialize().join();

            NewSessionResponse session = client.newSession(
                    new NewSessionRequest("/tmp", List.of())).join();
            assertEquals("session-42", session.sessionId());

            PromptResponse prompt = client.prompt(
                    new PromptRequest(List.of(new TextContent("hello")), "session-42")).join();
            assertEquals(StopReason.END_TURN, prompt.stopReason());

            client.closeSession(new CloseSessionRequest("session-42")).join();

            assertEquals(4, agent.requests().size());
            assertEquals("initialize", agent.requests().get(0).get("method").asText());
            assertEquals("session/new", agent.requests().get(1).get("method").asText());
            assertEquals("session/prompt", agent.requests().get(2).get("method").asText());
            assertEquals("session/close", agent.requests().get(3).get("method").asText());
        }
    }

    @Test
    void agentSendsSessionUpdateNotification() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            agent.on("initialize", params -> new InitializeResponse(1));
            agent.on("session/new", params -> new NewSessionResponse("s1"));

            CompletableFuture<SessionNotification> received = new CompletableFuture<>();

            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .onSessionUpdate(received::complete)
                    .build();
            client.connect().join();
            client.initialize().join();
            client.newSession(new NewSessionRequest("/tmp", List.of())).join();

            agent.sessionUpdate("s1", "agent_message_chunk",
                    new ContentChunk(Map.of("type", "text", "text", "Hello from agent")));

            SessionNotification notification = received.get(2, TimeUnit.SECONDS);
            assertNotNull(notification);
            assertEquals("s1", notification.sessionId());
            assertNotNull(notification.update());
        }
    }

    @Test
    void agentSendsPermissionRequest() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            agent.on("initialize", params -> new InitializeResponse(1));
            agent.on("session/new", params -> new NewSessionResponse("s1"));

            CompletableFuture<String> permissionGranted = new CompletableFuture<>();

            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .withPermissionMode("allow_once")
                    .onPermissionRequest((req, optionId) -> permissionGranted.complete(optionId))
                    .build();
            client.connect().join();
            client.initialize().join();
            client.newSession(new NewSessionRequest("/tmp", List.of())).join();

            var permissionRequest = new RequestPermissionRequest(
                    List.of(
                            new PermissionOption(PermissionOptionKind.ALLOW_ONCE, "Allow once", "opt-allow"),
                            new PermissionOption(PermissionOptionKind.REJECT_ONCE, "Reject once", "opt-reject")),
                    "s1",
                    new ToolCallUpdate("tc-1"));

            JsonNode reply = agent.requestPermission(permissionRequest).get(2, TimeUnit.SECONDS);

            String selectedOption = permissionGranted.get(2, TimeUnit.SECONDS);
            assertEquals("opt-allow", selectedOption);

            assertNotNull(reply);
            assertNotNull(reply.get("outcome"));
            assertEquals("opt-allow", reply.get("outcome").get("optionId").asText());
        }
    }

    @Test
    void unhandledMethodReturnsJsonRpcError() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .build();
            client.connect().join();

            CompletionException ex = assertThrows(CompletionException.class,
                    () -> client.initialize().join());
            assertNotNull(ex.getCause());
        }
    }

    @Test
    void requestsCapturedInOrder() throws Exception {
        try (MockAcpAgent agent = MockAcpAgent.start()) {
            agent.on("initialize", params -> new InitializeResponse(1));
            agent.on("session/new", params -> new NewSessionResponse("s1"));

            AcpAsyncClient client = AcpClient.async(agent.transport())
                    .withRequestTimeout(Duration.ofSeconds(2))
                    .build();
            client.connect().join();

            client.initialize().join();
            client.newSession(new NewSessionRequest("/tmp", List.of())).join();

            List<JsonNode> requests = agent.requests();
            assertEquals(2, requests.size());

            JsonNode initReq = requests.get(0);
            assertEquals("initialize", initReq.get("method").asText());
            assertNotNull(initReq.get("id"));
            assertNotNull(initReq.get("params"));

            JsonNode sessionReq = requests.get(1);
            assertEquals("session/new", sessionReq.get("method").asText());
            assertEquals("/tmp", sessionReq.get("params").get("cwd").asText());
        }
    }
}
