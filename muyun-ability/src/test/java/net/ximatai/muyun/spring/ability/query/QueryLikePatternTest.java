package net.ximatai.muyun.spring.ability.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLikePatternTest {
    @Test
    void shouldMatchSubstringsWithoutTreatingUserTextAsWildcards() {
        assertThat(QueryLikePattern.containsLiteral("青禾")).isEqualTo("%青禾%");
        assertThat(QueryLikePattern.containsLiteral("50%_\\"))
                .isEqualTo("%50\\%\\_\\\\%");
    }
}
