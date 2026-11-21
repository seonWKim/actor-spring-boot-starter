package io.github.seonwkim.actor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Marks the {@code DataSource} the starter uses instead of the application's: the JDBC peer registry, lease
 * store and generation store, the event journal and snapshots, and their tables. Projections stay on the
 * application's {@code DataSource}, because a projection writes its offset in the same transaction as its read
 * model.
 *
 * <p>Declare it with {@code @Bean(defaultCandidate = false)} so it doesn't replace the application's
 * {@code DataSource}: Spring Boot then still auto-configures that one from {@code spring.datasource.*}.
 */
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier public @interface ActorDataSource {}
