package io.github.seonwkim.example.shardedchat.control;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The nodes as JVMs on this machine (bin/start). Each gets its color's seed nodes, so blue and green form
 * two clusters that never meet, its color, the shared generation store (an H2 server in this app), and
 * the deployer password through the environment. Node n listens on HTTP 8080+n and cluster port 2550+n.
 */
@Component
@ConditionalOnProperty(name = "control.platform", havingValue = "local", matchIfMissing = true)
public class LocalPlatform implements Platform {

    private final ControlProperties properties;
    private final GenerationStoreServer store;
    private final String password;
    private final Map<Integer, Process> processes = new ConcurrentHashMap<>();
    private final Map<Integer, LogBuffer> logs = new ConcurrentHashMap<>();

    public LocalPlatform(ControlProperties properties, GenerationStoreServer store) {
        this.properties = properties;
        this.store = store;
        this.password = UUID.randomUUID().toString();
    }

    /** The deployer password this app gives the nodes it starts. */
    @Override
    public String deployerPassword() {
        return password;
    }

    @Override
    public boolean runsNodes() {
        return true;
    }

    @Override
    public Map<String, String> commands() {
        return Map.of();
    }

    @Override
    public int[] slots(String color) {
        return properties.slots(color);
    }

    @Override
    public String colorOf(int node) {
        return properties.colorOf(node);
    }

    /** A stopped node of that color after the first nodes, else the next number no node has used. */
    @Override
    public int nextNumber(String color, List<NodeProcess> known) {
        int first = properties.nodesPerColor() * 2 + 1;
        return known.stream()
                .filter(n -> n.node >= first && n.color.equals(color) && !alive(n.node))
                .mapToInt(n -> n.node)
                .min()
                .orElseGet(() -> Math.max(
                        first, known.stream().mapToInt(n -> n.node + 1).max().orElse(first)));
    }

    @Override
    public synchronized void start(int node, String color, String version) throws IOException {
        if (alive(node)) {
            return;
        }
        String seeds = Arrays.stream(properties.slots(color))
                .mapToObj(n -> "pekko://sharded-chat@127.0.0.1:" + ControlProperties.remotePort(n))
                .collect(Collectors.joining(","));
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.addAll(Arrays.asList(properties.javaOptions().split("\\s+")));
        command.addAll(List.of(
                "-jar",
                Path.of(properties.nodeJar()).toAbsolutePath().toString(),
                "--sharded-chat.node=" + node,
                "--sharded-chat.version=" + version,
                "--sharded-chat.start-with-nodes=" + properties.nodesPerColor(),
                "--server.port=" + ControlProperties.httpPort(node),
                "--spring.actor.remote.port=" + ControlProperties.remotePort(node),
                "--spring.actor.cluster.seed-nodes=" + seeds,
                "--spring.actor.generation.color=" + color,
                "--spring.actor.generation.store=jdbc",
                // A database behind TCP is not "embedded", so the starter creates its tables only when told to.
                "--spring.actor.jdbc.initialize-schema=always",
                "--spring.datasource.url=" + store.url()));
        Path dir = Path.of(properties.nodeDir());
        Files.createDirectories(dir);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Through the environment, so the password does not show in the process list.
        builder.environment().put("SHARDED_CHAT_DEPLOYER_PASSWORD", password);
        Process process = builder.start();
        Files.writeString(dir.resolve("node-" + node + ".pid"), String.valueOf(process.pid()));
        // The output stays in memory (no log file): the last lines of each run, a restart starts afresh.
        LogBuffer log = new LogBuffer();
        Thread.ofVirtual().name("node-" + node + "-output").start(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(log::add);
            } catch (IOException | UncheckedIOException e) {
                // The process ended.
            }
        });
        processes.put(node, process);
        logs.put(node, log);
    }

    @Override
    public synchronized boolean stop(int node) {
        Process process = processes.get(node);
        if (process == null || !process.isAlive()) {
            return false;
        }
        process.destroy();
        return true;
    }

    @Override
    public void retire(String color, List<NodeProcess> live) {
        live.forEach(n -> stop(n.node));
    }

    @Override
    public boolean alive(int node) {
        Process process = processes.get(node);
        return process != null && process.isAlive();
    }

    @Override
    public Map<Integer, Started> discover() {
        return Map.of();
    }

    @Override
    public String baseUrl(int node) {
        return "http://127.0.0.1:" + ControlProperties.httpPort(node);
    }

    @Override
    public String nameOf(int node) {
        return "node-" + node;
    }

    @Override
    public int remotePort(int node) {
        return ControlProperties.remotePort(node);
    }

    @Override
    public List<String> log(int node) {
        LogBuffer log = logs.get(node);
        return log == null ? List.of() : log.lines();
    }

    @PreDestroy
    void stopAll() throws InterruptedException {
        processes.values().forEach(Process::destroy);
        for (Process p : processes.values()) {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
    }
}
