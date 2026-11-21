package io.github.seonwkim.actor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.seonwkim.actor.autoconfigure.ActorConfiguration;
import io.github.seonwkim.actor.internal.PekkoActorSystem;
import io.github.seonwkim.actor.serialization.JsonSerializable;
import java.nio.charset.StandardCharsets;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.apache.pekko.serialization.Serialization;
import org.apache.pekko.serialization.SerializationExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * By default, messages between nodes are written with the starter's own mapper: a setting the application
 * makes for its REST API (here a naming strategy) does not change the format nodes exchange.
 */
@SpringBootTest(classes = {ActorConfiguration.class, ActorObjectMapperSerializationTest.TestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActorObjectMapperSerializationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper restApiObjectMapper() {
            return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    public record Greeting(String userName) implements JsonSerializable {}

    @Autowired
    private SpringActorSystem actorSystem;

    @Test
    void jsonMessagesIgnoreTheApplicationObjectMappersSettings() {
        Serialization serialization = SerializationExtension.get(Adapter.toClassic(PekkoActorSystem.raw(actorSystem)));

        byte[] bytes = serialization.serialize(new Greeting("ada")).get();

        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"userName\":\"ada\"");
    }
}
