package com.ownclaw.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The database and the directory it is in are the service's alone. The database holds every
 * conversation, the password hashes, the vault's encrypted credentials and the key that decrypts
 * them; made under systemd's default umask it was readable by every account on the host, and so
 * was each upload beside it. What is open is closed at every start, and said; a database that is
 * not there yet is made private before SQLite first opens it.
 */
class DatabaseFilesArePrivateTest {

    @TempDir Path tmp;

    private ListAppender<ILoggingEvent> logged;
    private Logger initializerLog;

    @BeforeEach
    void listen() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here");
        logged = new ListAppender<>();
        logged.start();
        initializerLog = (Logger) LoggerFactory.getLogger(DatabaseInitializer.class);
        initializerLog.addAppender(logged);
    }

    @AfterEach
    void stopListening() {
        if (initializerLog != null) initializerLog.detachAppender(logged);
    }

    private static String mode(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
    }

    private static Path file(Path p, String mode) throws Exception {
        Files.writeString(p, "x");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
        return p;
    }

    private List<String> warnings() {
        return logged.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a database directory and files open to other accounts are made their owner's alone, each said in the log; a second start finds nothing to say")
    void whatIsOpenIsClosed() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path db = file(dir.resolve("ownclaw.db"), "rw-r--r--");
        file(dir.resolve("ownclaw.db-wal"), "rw-r--r--");
        file(dir.resolve("ownclaw.db-shm"), "rw-rw-r--");
        Path upload = file(Files.createDirectories(dir.resolve("uploads")).resolve("statement.pdf"), "rw-r--r--");

        DatabaseInitializer.keepPrivate(db);

        assertEquals("rwx------", mode(dir), "the directory: what is inside is out of every other account's reach");
        assertEquals("rw-------", mode(db));
        assertEquals("rw-------", mode(dir.resolve("ownclaw.db-wal")));
        assertEquals("rw-------", mode(dir.resolve("ownclaw.db-shm")));
        assertEquals("rw-r--r--", mode(upload), "a file inside is left as it is: the directory closes it");
        assertEquals(4, warnings().size(), warnings().toString());
        assertTrue(warnings().getFirst().contains("was open to other accounts on this host (rwxr-xr-x): it is now its owner's alone (rwx------)"),
                warnings().getFirst());

        logged.list.clear();
        DatabaseInitializer.keepPrivate(db);
        assertEquals(List.of(), warnings(), "what is private is left, and not said again");
        // Mutations: the directory not looked at -> it stays rwxr-xr-x and the upload is readable by
        // all; the WAL and shared-memory files not looked at -> they stay readable.
    }

    @Test
    @DisplayName("a database that is not there yet is made private before SQLite first opens it, and the WAL and shared-memory files SQLite makes beside it are as private")
    void aNewDatabaseIsPrivateFromItsFirstStart() throws Exception {
        Path db = Files.createDirectories(tmp.resolve("data")).resolve("ownclaw.db");

        DatabaseInitializer.keepPrivate(db);
        assertEquals("rw-------", mode(db));

        // The application's own connection parameters (WAL), and a write, with the connection
        // held open: the WAL and shared-memory files are there while it is.
        MigratedDatabase.at(db, MigratedDatabase.productionParameters());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db + MigratedDatabase.productionParameters());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO system_settings (key, value) VALUES ('private.probe', 'x')");
            assertTrue(Files.exists(Path.of(db + "-wal")), "the premise: the WAL is there while the connection is open");
            assertEquals("rw-------", mode(Path.of(db + "-wal")));
            assertEquals("rw-------", mode(Path.of(db + "-shm")));
        }
        assertEquals("rw-------", mode(db), "the migrations kept it so");
        // Mutation: no empty database made before SQLite opens it -> SQLite makes it rw-r--r-- under
        // the test's umask, and its WAL and shared-memory files with it.
    }

    @Test
    @DisplayName("a database file that is a link is not followed: what it points at is left, and the log says so")
    void aLinkIsNotFollowed() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path elsewhere = file(tmp.resolve("elsewhere.db"), "rw-r--r--");
        Path db = Files.createSymbolicLink(dir.resolve("ownclaw.db"), elsewhere);

        DatabaseInitializer.keepPrivate(db);

        assertEquals("rw-r--r--", mode(elsewhere));
        assertTrue(warnings().stream().anyMatch(w -> w.contains("is a link: its permissions are not changed here")), warnings().toString());
    }
}
