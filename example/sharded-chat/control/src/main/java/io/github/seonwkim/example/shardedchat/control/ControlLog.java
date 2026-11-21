package io.github.seonwkim.example.shardedchat.control;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The control app's own log, kept in memory like the nodes' output ({@code GET /api/logs/control}). */
@Component
public class ControlLog {

    private final LogBuffer buffer = new LogBuffer();

    public ControlLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n%ex");
        layout.start();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                buffer.add(layout.doLayout(event).stripTrailing());
            }
        };
        appender.setContext(context);
        appender.setName("memory");
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    public LogBuffer buffer() {
        return buffer;
    }
}
