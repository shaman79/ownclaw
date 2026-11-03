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
        migrate(file, liquibase -> liquibase.update(""));
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }

    /**
     * The schema as it was before the changesets of one changelog file ran: for a test of what that
     * file does to rows already there. {@link #at} on the same file then runs it and the rest.
     *
     * @param changelog the file's name, e.g. "023-ops-and-scheduled-out-of-chats.sql"
     */
    public static JdbcTemplate before(Path file, String changelog) throws Exception {
        migrate(file, liquibase -> {
            var unrun = liquibase.listUnrunChangeSets(null, null);
            int first = 0;
            while (first < unrun.size() && !unrun.get(first).getFilePath().endsWith(changelog)) first++;
            if (first == unrun.size()) throw new IllegalArgumentException("no changeset of " + changelog);
            liquibase.update(first, "");
        });
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }

    private interface Run {
        void on(Liquibase liquibase) throws Exception;
    }

    private static void migrate(Path file, Run run) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            // Its progress report goes to the logger silenced above, not to the suite's output.
            Scope.child(Scope.Attr.ui.name(), new LoggerUIService(), () ->
                    run.on(new Liquibase("db/changelog/db.changelog-master.yaml",
                            new ClassLoaderResourceAccessor(), database)));
        }
    }

    /**
     * Every row of a chat saved from now on at a moment of its own, a minute after the one before
     * -- the first at 06:35:08 on 8 October 2026, UTC -- as rows saved seconds apart are, where a
     * test saves several in one second: a frame carrying another row's time, or the time it was
     * sent, then says a time no row of the chat has.
     */
    public static void eachRowAtItsOwnMoment(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TRIGGER each_row_at_its_own_moment AFTER INSERT ON conversations BEGIN
                UPDATE conversations SET timestamp = datetime('2026-10-08 06:34:08',
                    '+' || (SELECT COUNT(*) FROM conversations) || ' minutes') WHERE id = NEW.id;
            END
            """);
    }
}
