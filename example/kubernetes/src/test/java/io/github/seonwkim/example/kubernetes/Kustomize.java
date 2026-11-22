package io.github.seonwkim.example.kubernetes;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.Yaml;

/** Renders a kustomization with {@code kubectl kustomize} and reads the result. */
final class Kustomize {

    private Kustomize() {}

    /** Renders {@code dir} (relative to the module); skips the test when kubectl is not installed. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> render(String dir) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("kubectl", "kustomize", dir)
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            assumeTrue(false, "kubectl is not installed");
            throw e;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("kubectl kustomize " + dir).isZero();
        List<Map<String, Object>> resources = new ArrayList<>();
        new Yaml().loadAll(output).forEach(document -> resources.add((Map<String, Object>) document));
        return resources;
    }

    static Map<String, Object> resource(List<Map<String, Object>> resources, String kind, String name) {
        return resources.stream()
                .filter(r -> kind.equals(r.get("kind")) && name.equals(get(r, "metadata", "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " " + name));
    }

    /** Walks maps by key and lists by index. */
    @SuppressWarnings("unchecked")
    static Object get(Object node, Object... path) {
        Object current = node;
        for (Object step : path) {
            current = step instanceof Integer index
                    ? ((List<Object>) current).get(index)
                    : ((Map<String, Object>) Objects.requireNonNull(current, "missing " + step)).get(step);
        }
        return current;
    }

    /** The ConfigMap's {@code application.yaml}, parsed. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> applicationYaml(Map<String, Object> configMap) {
        return (Map<String, Object>) new Yaml().load((String) get(configMap, "data", "application.yaml"));
    }

    /** Runs {@code scripts/<filter>} on {@code input}; skips the test when jq is not installed. */
    static Object jq(String filter, Object input, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("jq", "-c"));
        command.addAll(List.of(args));
        command.addAll(List.of("-f", "scripts/" + filter));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            assumeTrue(false, "jq is not installed");
            throw e;
        }
        try (var stdin = process.getOutputStream()) {
            stdin.write(json(input).getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("jq -f scripts/" + filter).isZero();
        return new Yaml().load(output); // JSON is YAML
    }

    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> json(String.valueOf(e.getKey())) + ":" + json(e.getValue()))
                    .collect(joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Kustomize::json).collect(joining(",", "[", "]"));
        }
        if (value instanceof String string) {
            StringBuilder quoted = new StringBuilder("\"");
            string.chars()
                    .forEach(c -> quoted.append(
                            c == '"' || c == '\\'
                                    ? "\\" + (char) c
                                    : c < 0x20 ? String.format("\\u%04x", c) : (char) c));
            return quoted.append('"').toString();
        }
        return String.valueOf(value); // numbers, booleans, null
    }
}
