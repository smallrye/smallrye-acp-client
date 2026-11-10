package io.smallrye.acp.mcp.probe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal Server-Sent Events parsing.
 */
final class SseEvents {

    record Event(String name, String data) {
    }

    /** Accumulates lines and returns an event when a blank line terminates it. */
    static final class Parser {
        private String name = "message";
        private final StringBuilder data = new StringBuilder();

        Event accept(String line) {
            if (line.isEmpty()) {
                if (data.isEmpty()) {
                    return null;
                }
                Event event = new Event(name, data.toString());
                name = "message";
                data.setLength(0);
                return event;
            }
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
            return null;
        }
    }

    private SseEvents() {
    }

    static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        Parser parser = new Parser();
        for (String line : (body + "\n\n").split("\r?\n", -1)) {
            Event event = parser.accept(line);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    /** Returns the JSON-RPC response with the given id found in an event-stream body. */
    static JsonNode findResponse(String body, long id) throws IOException {
        for (Event event : parse(body)) {
            if (!event.data().trim().startsWith("{")) {
                continue;
            }
            JsonNode node = McpProbe.MAPPER.readTree(event.data());
            if (node.has("id") && node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }
}
