package com.ownclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ensures the data directory exists before Liquibase / HikariCP try to open the SQLite file, and
 * that it and the database are the service's alone. Runs as a BeanFactoryPostProcessor -- before
 * any beans (including DataSource) are created.
 */
@Configuration
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    /** What the database's files may grant besides their owner: nothing. */
    private static final Set<PosixFilePermission> OTHERS = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    @Bean
    static BeanFactoryPostProcessor dataDirectoryInitializer(Environment env) {
        return (ConfigurableListableBeanFactory factory) -> {
            String dbPath = env.getProperty("ownclaw.database.path", "./data/ownclaw.db");
            Path parentDir = Path.of(dbPath).toAbsolutePath().normalize().getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                try {
                    Files.createDirectories(parentDir);
                    log.info("Created data directory: {}", parentDir);
                } catch (Exception e) {
                    log.error("Failed to create data directory: {}", e.getMessage());
                }
            }
            keepPrivate(Path.of(dbPath));
        };
    }

    /**
     * The database and the directory it is in are the service's alone: no group or other account
     * on the host may read, write or list them.
     * <p>
     * The database holds everything -- every conversation, the accounts' password hashes, the
     * vault's encrypted credentials and, in system_settings, the key that decrypts them. SQLite
     * made it with the service's umask, which systemd leaves at 022: readable by every account on
     * the machine, and so was each upload beside it. What is open to others is closed here, at
     * every start, and said in the log; an empty database is made private before SQLite first
     * opens it, and SQLite gives its WAL and shared-memory files the database file's permissions.
     * Only what this process's user owns is changed: anything else is said and left.
     */
    static void keepPrivate(Path database) {
        Path db = database.toAbsolutePath().normalize();
        Path dir = db.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            try {
                // The directory itself, wherever a link to it leads: what is inside is then out of
                // every other account's reach, whatever each file's own permissions.
                restrict(dir.toRealPath(), "The database's directory");
            } catch (IOException e) {
                log.warn("The database's directory {} could not be checked: {}", dir, e.getMessage());
            }
        }
        if (!Files.exists(db, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(db, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException | UnsupportedOperationException ignored) {
                // Made meanwhile, or a file system with no POSIX permissions: nothing to do here.
            } catch (IOException e) {
                log.warn("The database {} could not be made private before its first start: {}", db, e.getMessage());
            }
        }
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            Path file = Path.of(db + suffix);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(file)) {
                log.warn("The database file {} is a link: its permissions are not changed here", file);
                continue;
            }
            restrict(file, "The database file");
        }
    }

    /** Take every permission of the group and of other accounts off {@code path}, when this process's user owns it. */
    private static void restrict(Path path, String what) {
        try {
            Set<PosixFilePermission> now = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            var open = EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission p : now) if (OTHERS.contains(p)) open.add(p);
            if (open.isEmpty()) return;
            String owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
            String me = System.getProperty("user.name");
            if (!owner.equals(me)) {
                log.warn("{} {} is open to other accounts on this host ({}), and is {}'s, not {}'s: it is left as it is. "
                        + "Make it its owner's alone by hand.", what, path, PosixFilePermissions.toString(now), owner, me);
                return;
            }
            var kept = EnumSet.copyOf(now);
            kept.removeAll(OTHERS);
            Files.setPosixFilePermissions(path, kept);
            log.warn("{} {} was open to other accounts on this host ({}): it is now its owner's alone ({}).",
                    what, path, PosixFilePermissions.toString(now), PosixFilePermissions.toString(kept));
        } catch (UnsupportedOperationException notPosix) {
            // A file system with no POSIX permissions: there is nothing to take off.
        } catch (IOException e) {
            log.warn("{} {} could not be made private: {}", what, path, e.getMessage());
        }
    }
}
