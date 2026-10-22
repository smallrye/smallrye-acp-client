package io.smallrye.agentclientprotocol.sdk.client.transport;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.TextContent;

/**
 * Custom serializer that adds the required {@code "type": "text"} discriminator
 * when serializing {@link TextContent} for the ACP protocol.
 */
class TextContentSerializer extends StdSerializer<TextContent> {

    TextContentSerializer() {
        super(TextContent.class);
    }

    @Override
    public void serialize(TextContent value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "text");
        gen.writeStringField("text", value.text());
        if (value.annotations() != null) {
            gen.writeObjectField("annotations", value.annotations());
        }
        gen.writeEndObject();
    }
}
