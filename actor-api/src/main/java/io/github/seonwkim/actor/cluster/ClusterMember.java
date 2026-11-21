package io.github.seonwkim.actor.cluster;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * A node of the cluster at the time of an event or a {@link SpringCluster} snapshot.
 *
 * @param address the node's address, e.g. {@code pekko://app@10.0.0.1:2551}
 * @param roles the node's roles
 * @param status the node's status
 */
public record ClusterMember(String address, Set<String> roles, MemberStatus status) {
    public ClusterMember {
        roles = Set.copyOf(roles);
    }

    /**
     * The host part of {@link #address()}: {@code 10.0.0.1} for {@code pekko://app@10.0.0.1:2551}, {@code
     * ::1} (without brackets) for {@code pekko://app@[::1]:2551}. Empty for an address without a host, such
     * as the local member outside cluster mode ({@code pekko://app}).
     */
    public Optional<String> host() {
        String hostAndPort = hostAndPort();
        if (hostAndPort == null) {
            return Optional.empty();
        }
        if (hostAndPort.startsWith("[")) {
            int end = hostAndPort.indexOf(']');
            return end < 0 ? Optional.empty() : Optional.of(hostAndPort.substring(1, end));
        }
        int colon = hostAndPort.lastIndexOf(':');
        return Optional.of(colon < 0 ? hostAndPort : hostAndPort.substring(0, colon));
    }

    /**
     * The port part of {@link #address()}: {@code 2551} for {@code pekko://app@10.0.0.1:2551}. Empty for an
     * address without a port, such as the local member outside cluster mode ({@code pekko://app}).
     */
    public OptionalInt port() {
        String hostAndPort = hostAndPort();
        if (hostAndPort == null) {
            return OptionalInt.empty();
        }
        int colon = hostAndPort.lastIndexOf(':');
        int bracket = hostAndPort.lastIndexOf(']');
        if (colon < 0 || colon < bracket) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(hostAndPort.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** What follows {@code @} in the address, or null when it has none. */
    @Nullable private String hostAndPort() {
        int at = address.indexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at + 1);
    }
}
