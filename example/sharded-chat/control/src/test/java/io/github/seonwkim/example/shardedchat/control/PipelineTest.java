package io.github.seonwkim.example.shardedchat.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineTest {

    private static Pipeline.Node blue(boolean up) {
        return new Pipeline.Node("blue", true, up);
    }

    private static Pipeline.Node green(boolean live, boolean up) {
        return new Pipeline.Node("green", live, up);
    }

    @Test
    void withOnlyTheActiveColorRunningTheOtherColorCanBeDeployedAndNothingElse() {
        Pipeline next = Pipeline.of(List.of(blue(true), blue(true), blue(true)), "blue", 2);
        assertThat(next).isEqualTo(new Pipeline("green", null, null));
    }

    @Test
    void theStandbyCanBeActivatedOnlyOnceEveryOneOfItsRunningNodesIsWarmedUp() {
        List<Pipeline.Node> warming = List.of(blue(true), green(true, true), green(true, false));
        assertThat(Pipeline.of(warming, "blue", 2)).isEqualTo(new Pipeline(null, null, "green"));

        List<Pipeline.Node> warm = List.of(blue(true), green(true, true), green(true, true), green(false, false));
        assertThat(Pipeline.of(warm, "blue", 2)).isEqualTo(new Pipeline(null, "green", "green"));
    }

    @Test
    void afterTheSwitchTheOldColorCanBeActivatedAgainOrRetired() {
        List<Pipeline.Node> both = List.of(blue(true), blue(true), green(true, true), green(true, true));
        assertThat(Pipeline.of(both, "green", 2)).isEqualTo(new Pipeline(null, "blue", "blue"));
    }

    @Test
    void beforeTheFirstActivationThePipelineOffersNothing() {
        assertThat(Pipeline.of(List.of(blue(true)), null, 2)).isEqualTo(new Pipeline(null, null, null));
    }
}
