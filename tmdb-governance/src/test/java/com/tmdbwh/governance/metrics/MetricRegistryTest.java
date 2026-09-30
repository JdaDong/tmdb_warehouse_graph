package com.tmdbwh.governance.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 指标口径注册表与一致性校验。 */
class MetricRegistryTest {

    private static MetricDefinition definition(String hocon) {
        return MetricDefinition.from(ConfigFactory.parseString(hocon));
    }

    @Test
    void definitionParsesAllFields() {
        MetricDefinition metric = definition("name=movie_popularity table=dws.dws_movie_metric_1d"
                + " expression=\"avg(popularity)\" owner=data-platform description=\"热度\"");

        assertThat(metric.getName()).isEqualTo("movie_popularity");
        assertThat(metric.getDatabase()).isEqualTo("dws");
        assertThat(metric.getTableName()).isEqualTo("dws_movie_metric_1d");
        assertThat(metric.getExpression()).isEqualTo("avg(popularity)");
        assertThat(metric.getOwner()).isEqualTo("data-platform");
    }

    @Test
    void blankFieldsAreRejected() {
        assertThatThrownBy(() -> definition("name=\"\" table=t expression=e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateNameWithDifferentExpressionIsRejected() {
        // 同一个名字两种算法是"报表互相打架"的根源，必须在加载阶段就拒绝
        assertThatThrownBy(() -> new MetricRegistry(List.of(
                definition("name=m table=t expression=\"avg(x)\""),
                definition("name=m table=t expression=\"sum(x)\""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("两种算法");
    }

    @Test
    void duplicateNameWithSameExpressionIsAllowed() {
        assertThat(new MetricRegistry(List.of(
                definition("name=m table=t expression=\"avg(x)\""),
                definition("name=m table=t expression=\"avg(x)\""))).getDefinitions()).hasSize(2);
    }

    @Test
    void lookupByName() {
        MetricRegistry registry = new MetricRegistry(List.of(definition("name=m table=t expression=e")));
        assertThat(registry.get("m").getExpression()).isEqualTo("e");
        assertThatThrownBy(() -> registry.get("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void columnsAreExtractedFromExpression() {
        assertThat(MetricRegistry.columnsIn("revenue / budget")).containsExactly("revenue", "budget");
        // 函数名不应被当成字段
        assertThat(MetricRegistry.columnsIn("avg(popularity)")).containsExactly("popularity");
    }

    @Test
    void staticValidationDetectsDuplicatesWithoutDatabase() {
        MetricRegistry registry = new MetricRegistry(List.of(
                definition("name=m table=t expression=e"),
                definition("name=m table=t expression=e")));

        List<MetricRegistry.Issue> issues = registry.validate(null);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getType()).isEqualTo("DUPLICATE");
    }

    @Test
    void bundledDefinitionsLoadAndAreConsistent() {
        MetricRegistry registry = MetricRegistry.load();
        assertThat(registry.getDefinitions()).isNotEmpty();
        assertThat(registry.validate(null)).isEmpty();
    }
}
