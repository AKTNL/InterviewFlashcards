package com.interviewflashcards.sync;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MutationDigestTest {
    @Test void canonicalizesNestedObjectKeysButPreservesArrayOrderAndValueTypes() throws Exception {
        var mapper = new ObjectMapper(); var service = new MutationService(null, mapper, Clock.systemUTC());
        assertThat(service.digest(mapper.readTree("{\"b\":{\"x\":1,\"a\":2},\"a\":[1,2]}")))
                .isEqualTo(service.digest(mapper.readTree("{\"a\":[1,2],\"b\":{\"a\":2,\"x\":1}}")));
        assertThat(service.digest(List.of(1, 2))).isNotEqualTo(service.digest(List.of(2, 1)));
        assertThat(service.digest(Map.of("a", 1))).isNotEqualTo(service.digest(Map.of("a", "1")));
    }
}
