package io.github.seonwkim.actor.internal;

import io.github.seonwkim.actor.ActorDataSource;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

/** Finds the DataSource the starter's stores and journal use: the {@link ActorDataSource} bean, else the application's. */
public final class ActorDataSources {

    private ActorDataSources() {}

    public static DataSource resolve(BeanFactory beanFactory) {
        if (!(beanFactory instanceof ListableBeanFactory beans)) {
            return beanFactory.getBean(DataSource.class);
        }
        List<String> marked = Arrays.stream(beans.getBeanNamesForType(DataSource.class))
                .filter(name -> beans.findAnnotationOnBean(name, ActorDataSource.class) != null)
                .toList();
        if (marked.size() > 1) {
            throw new IllegalStateException("Only one DataSource can be @ActorDataSource, found " + marked);
        }
        return marked.isEmpty() ? beans.getBean(DataSource.class) : beans.getBean(marked.get(0), DataSource.class);
    }
}
