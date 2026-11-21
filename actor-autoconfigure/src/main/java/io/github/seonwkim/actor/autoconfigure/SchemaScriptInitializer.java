package io.github.seonwkim.actor.autoconfigure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.jdbc.init.PlatformPlaceholderDatabaseDriverResolver;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Creates a module's tables from its schema file for the database in use ({@code @@platform@@} in
 * the location: h2, postgresql, mysql, ...), when {@code mode} allows it. Each module subclasses it,
 * so beans that need the tables can depend on their initializer by type.
 *
 * <p>On PostgreSQL the scripts run on one connection holding an advisory lock, so nodes that start
 * together create the tables one at a time: a concurrent {@code CREATE TABLE IF NOT EXISTS} fails there
 * on the catalog's unique index.
 */
public abstract class SchemaScriptInitializer extends DataSourceScriptDatabaseInitializer {

    /** The advisory lock every node takes before creating tables: any fixed number, the same everywhere. */
    static final long POSTGRES_LOCK_KEY = 0x6163746f72L; // "actor"

    protected SchemaScriptInitializer(DataSource dataSource, DatabaseInitializationMode mode, String schemaLocation) {
        super(dataSource, settings(dataSource, mode, schemaLocation));
    }

    private static DatabaseInitializationSettings settings(
            DataSource dataSource, DatabaseInitializationMode mode, String schemaLocation) {
        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setMode(mode);
        if (mode != DatabaseInitializationMode.NEVER) {
            // Finding the database's schema file opens a connection: only when it may run.
            settings.setSchemaLocations(
                    new PlatformPlaceholderDatabaseDriverResolver().resolveAll(dataSource, schemaLocation));
        }
        return settings;
    }

    @Override
    protected void runScripts(Scripts scripts) {
        try {
            if (!isPostgres()) {
                super.runScripts(scripts);
                return;
            }
            try (Connection connection = getDataSource().getConnection()) {
                runLocked(connection, scripts);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Could not create the actor tables", e);
        }
    }

    private void runLocked(Connection connection, Scripts scripts) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            // A session lock: it lasts until unlocked or the connection closes, across the script's commits.
            statement.execute("SELECT pg_advisory_lock(" + POSTGRES_LOCK_KEY + ")");
            try {
                populator(scripts).populate(connection);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + POSTGRES_LOCK_KEY + ")");
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private ResourceDatabasePopulator populator(Scripts scripts) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(scripts.isContinueOnError());
        populator.setSeparator(scripts.getSeparator());
        if (scripts.getEncoding() != null) {
            populator.setSqlScriptEncoding(scripts.getEncoding().name());
        }
        for (Resource resource : scripts) {
            populator.addScript(resource);
        }
        customize(populator);
        return populator;
    }

    // Its own connection, closed before the scripts run: a pool of one is enough either way.
    private boolean isPostgres() throws SQLException {
        try (Connection connection = getDataSource().getConnection()) {
            return connection
                    .getMetaData()
                    .getDatabaseProductName()
                    .toLowerCase(Locale.ROOT)
                    .contains("postgres");
        }
    }
}
