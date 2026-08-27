package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * File system capabilities that a client may support.
 *
 * See protocol docs: [FileSystem](https://agentclientprotocol.com/protocol/initialization#filesystem)
 */
public record FileSystemCapabilities(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("readTextFile") Boolean readTextFile,
        @JsonProperty("writeTextFile") Boolean writeTextFile) {
}
