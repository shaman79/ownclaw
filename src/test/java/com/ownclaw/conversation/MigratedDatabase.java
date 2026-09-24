package com.ownclaw.conversation;

import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An SQLite file with the real schema: the master changelog the application runs at startup,
 * applied by Liquibase.
 * <p>
 * A hand-built schema holds the columns its test thought of, so a changeset left out of the
 * master changelog, or a column a query names that no changeset adds, passes there and fails on
 * the first start in production.
 */
public final class MigratedDatabase {

    /** Held, so the level is not lost with a collected logger. */
    private static final Logger LIQUIBASE = Logger.getLogger("liquibase");

    static {
        LIQUIBASE.setLevel(Level.WARNING);
    }

    private MigratedDatabase() {}

    public static JdbcTemplate at(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            // Its progress report goes to the logger silenced above, not to the suite's output.
            Scope.child(Scope.Attr.ui.name(), new LoggerUIService(), () ->
                    new Liquibase("db/changelog/db.changelog-master.yaml",
                            new ClassLoaderResourceAccessor(), database).update(""));
        }
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }
}
