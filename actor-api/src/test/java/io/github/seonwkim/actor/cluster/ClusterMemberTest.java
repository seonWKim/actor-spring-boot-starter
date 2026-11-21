package io.github.seonwkim.actor.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClusterMemberTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "pekko://app@10.0.0.1:2551, 10.0.0.1, 2551",
        "pekko://app@[::1]:2551, ::1, 2551",
        "pekko://app@[fe80:0:0:0:0:0:0:1]:25520, fe80:0:0:0:0:0:0:1, 25520",
        "pekko://app@::1:2551, ::1, 2551",
    })
    void hostAndPortAreParsedFromTheAddress(String address, String host, int port) {
        ClusterMember member = new ClusterMember(address, Set.of(), MemberStatus.UP);

        assertEquals(Optional.of(host), member.host());
        assertEquals(OptionalInt.of(port), member.port());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"pekko://app", "pekko://app@"})
    void addressWithoutHostHasNoHostOrPort(String address) {
        ClusterMember member = new ClusterMember(address, Set.of(), MemberStatus.UP);

        assertEquals(Optional.empty(), member.host());
        assertEquals(OptionalInt.empty(), member.port());
    }
}
