package io.github.seonwkim.actor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API that may still change in a minor release, without a deprecation period, while it collects
 * feedback. The compatibility promise covers the core and cluster API; persistence, projections, streams,
 * metrics, the test module and the Kotlin layer are incubating. On a package, it covers every type in it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.PACKAGE,
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.ANNOTATION_TYPE
})
public @interface Incubating {}
