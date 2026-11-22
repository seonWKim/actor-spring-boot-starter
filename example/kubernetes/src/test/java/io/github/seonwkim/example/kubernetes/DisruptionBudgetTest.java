package io.github.seonwkim.example.kubernetes;

import static io.github.seonwkim.example.kubernetes.Kustomize.get;
import static io.github.seonwkim.example.kubernetes.Kustomize.jq;
import static io.github.seonwkim.example.kubernetes.Kustomize.render;
import static io.github.seonwkim.example.kubernetes.Kustomize.resource;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every overlay has a PodDisruptionBudget that lets a node drain take one of its pods at a time, and
 * blue-green.sh gives green a copy that selects green's pods, not blue's.
 */
class DisruptionBudgetTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podLabels(Map<String, Object> deployment) {
        return (Map<String, Object>) get(deployment, "spec", "template", "metadata", "labels");
    }

    @SuppressWarnings("unchecked")
    private static boolean selects(Map<String, Object> budget, Map<String, Object> labels) {
        Map<String, Object> matchLabels = (Map<String, Object>) get(budget, "spec", "selector", "matchLabels");
        return matchLabels.entrySet().stream().allMatch(e -> e.getValue().equals(labels.get(e.getKey())));
    }

    @ParameterizedTest
    @MethodSource("io.github.seonwkim.example.kubernetes.OverlaysTest#all")
    @SuppressWarnings("unchecked")
    void aDrainTakesOnePodAtATimeOfBlueAndOfGreen(String dir, String suffix) throws Exception {
        List<Map<String, Object>> resources = render(dir);
        Map<String, Object> blue = resource(resources, "Deployment", "spring-actor" + suffix);
        List<Map<String, Object>> budgets = resources.stream()
                .filter(r -> "PodDisruptionBudget".equals(r.get("kind")))
                .toList();

        assertThat(budgets).singleElement().satisfies(budget -> {
            assertThat(get(budget, "spec", "maxUnavailable")).isEqualTo(1);
            assertThat(selects(budget, podLabels(blue))).isTrue();
        });

        Map<String, Object> green = (Map<String, Object>)
                jq("blue-green-deployment.jq", blue, "--arg", "name", "spring-actor-green", "--arg", "image", "v2");
        Map<String, Object> greenBudgets = (Map<String, Object>) jq(
                "blue-green-pdb.jq",
                Map.of("items", budgets),
                "--arg",
                "blue",
                "spring-actor", // blue-green.sh's BLUE: the pods' app label
                "--arg",
                "green",
                "spring-actor-green");

        assertThat((List<Map<String, Object>>) greenBudgets.get("items"))
                .singleElement()
                .satisfies(budget -> {
                    assertThat(get(budget, "spec", "maxUnavailable")).isEqualTo(1);
                    assertThat(selects(budget, podLabels(green))).isTrue();
                    assertThat(selects(budget, podLabels(blue)))
                            .as("not blue's pods")
                            .isFalse();
                });
    }
}
