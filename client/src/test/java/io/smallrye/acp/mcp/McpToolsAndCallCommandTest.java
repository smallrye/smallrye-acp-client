package io.smallrye.acp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class McpToolsAndCallCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonArgumentsAndParamsAreMerged() throws Exception {
        var args = McpCallCommand.toolArguments("{\"map\":\"annotation\",\"mode\":\"strict\"}",
                List.of("mode=compat", "limit=10", "tags=[\"a\",\"b\"]", "key=@Transactional", "flag=true",
                        "text=a=b"));
        assertEquals(MAPPER.readTree("{\"map\":\"annotation\",\"mode\":\"compat\",\"limit\":10,\"tags\":[\"a\",\"b\"],"
                + "\"key\":\"@Transactional\",\"flag\":true,\"text\":\"a=b\"}"), args);
    }

    @Test
    void noArguments() {
        assertEquals(MAPPER.createObjectNode(), McpCallCommand.toolArguments(null, null));
    }

    @Test
    void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> McpCallCommand.toolArguments("{oops", null));
        assertThrows(IllegalArgumentException.class, () -> McpCallCommand.toolArguments("[1]", null));
        assertThrows(IllegalArgumentException.class, () -> McpCallCommand.toolArguments(null, List.of("novalue")));
    }

    @Test
    void renderTextAndOtherContent() throws Exception {
        String rendered = McpCallCommand.render(MAPPER.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"},"
                + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"AAAA\"},"
                + "{\"type\":\"resource_link\",\"uri\":\"file:///a\"}]}"));
        assertEquals(String.join(System.lineSeparator(), "hello", "[image image/png, 4 base64 chars]",
                "[resource link] file:///a"), rendered);
    }

    @Test
    void renderStructuredContentWhenNoText() throws Exception {
        String rendered = McpCallCommand.render(MAPPER.readTree("{\"content\":[],\"structuredContent\":{\"a\":1}}"));
        assertEquals(MAPPER.readTree("{\"a\":1}"), MAPPER.readTree(rendered));
        assertEquals("(empty result)", McpCallCommand.render(MAPPER.readTree("{}")));
    }

    @Test
    void parametersOfTheInputSchema() throws Exception {
        var lines = McpToolsCommand.parameters(MAPPER.readTree("{\"type\":\"object\",\"properties\":{"
                + "\"map\":{\"type\":\"string\",\"description\":\"Map to search\",\"enum\":[\"annotation\",\"class\"]},"
                + "\"limit\":{\"type\":\"integer\",\"default\":5},"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"map\"]}"));
        assertEquals(List.of(
                "map*  string    Map to search [annotation|class]",
                "limit integer   (default: 5)",
                "tags  string[]"), lines);
        assertEquals(List.of("(no parameters)"), McpToolsCommand.parameters(MAPPER.readTree("{\"type\":\"object\"}")));
    }
}
