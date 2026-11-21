package io.github.seonwkim.actor.autoconfigure.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.ActorDataSource;
import io.github.seonwkim.actor.autoconfigure.ActorConfiguration;
import io.github.seonwkim.actor.jdbc.JdbcGenerationStore;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The JDBC stores and their tables use the @ActorDataSource when there is one; the application keeps its own. */
class ActorDataSourceTest {

    private final String applicationDatabase = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private final String actorDatabase = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ActorConfiguration.class, DataSourceAutoConfiguration.class, StoreConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + applicationDatabase,
                    "spring.actor.generation.color=blue",
                    "spring.actor.generation.store=jdbc",
                    "spring.actor.jdbc.initialize-schema=always");

    @Configuration(proxyBeanMethods = false)
    static class ActorDatabase {
        @Bean(defaultCandidate = false)
        @ActorDataSource
        DataSource actorDataSource(@Value("${test.actor-database}") String url) {
            return new DriverManagerDataSource(url, "sa", "");
        }
    }

    @Test
    void theStoresTablesAreOnTheActorDataSourceAndTheApplicationKeepsItsOwn() {
        runner.withUserConfiguration(ActorDatabase.class)
                .withPropertyValues("test.actor-database=" + actorDatabase)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcGenerationStore.class);
                    assertThat(hasGenerationTable(actorDatabase)).isTrue();
                    assertThat(hasGenerationTable(applicationDatabase)).isFalse();
                    // Spring Boot still auto-configured the application's DataSource from spring.datasource.url.
                    assertThat(context.getBean(DataSource.class)
                                    .getConnection()
                                    .getMetaData()
                                    .getURL())
                            .isEqualTo(applicationDatabase.substring(0, applicationDatabase.indexOf(';')));
                });
    }

    @Test
    void withoutOneTheStoresUseTheApplicationsDataSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JdbcGenerationStore.class);
            assertThat(hasGenerationTable(applicationDatabase)).isTrue();
        });
    }

    private static boolean hasGenerationTable(String url) {
        Integer tables = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""))
                .queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'ACTOR_GENERATION'",
                        Integer.class);
        return tables != null && tables > 0;
    }
}
