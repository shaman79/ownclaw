package com.ownclaw.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * What the tests of credential grants share: the migrated database opened as production opens it,
 * foreign keys on; a vault that says what it decrypted; and skills on disk as skill_create leaves
 * them, loaded by the real registry and run against a sandbox that starts nothing and keeps what
 * each launch was handed.
 */
public final class GrantsKit {

    private GrantsKit() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The migrated database at {@code file}, every connection opened with foreign keys on, as
     * production's are (the JDBC URL in application.yaml): {@code credential_grants.user_id} and
     * {@code credential_vault.user_id} reference {@code users(id)}, and
     * {@link MigratedDatabase#at} opens its connections without the pragma, where a grant for an
     * account that does not exist is written and nobody hears of it.
     */
    public static JdbcTemplate strict(Path file) throws Exception {
        MigratedDatabase.at(file);
        return open(file);
    }

    private static JdbcTemplate open(Path file) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file + "?foreign_keys=true"));
        Integer on = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        if (on == null || on != 1) throw new AssertionError("foreign keys are not on: " + on);
        return jdbc;
    }

    /**
     * A copy of the database {@code filled} at {@code file}, opened as {@link #strict} opens one:
     * what a class filled once -- its accounts and what their vaults hold -- for each of its tests
     * to change as it likes. Storing a credential derives its account's key twice, a third of a
     * second on a busy machine, and a test's vault of four cost more than the test.
     */
    public static JdbcTemplate copy(Path filled, Path file) throws IOException {
        Files.copy(filled, file);
        for (String suffix : List.of("-wal", "-shm")) {
            Path beside = filled.resolveSibling(filled.getFileName() + suffix);
            if (Files.exists(beside)) Files.copy(beside, file.resolveSibling(file.getFileName() + suffix));
        }
        return open(file);
    }

    /**
     * Grants with no database behind them, where every account has granted every skill everything:
     * for a test of how a skill's credentials travel, whose stand-in vault holds no rows either.
     */
    public static CredentialGrantService everythingGranted() {
        return new CredentialGrantService(null) {
            @Override
            public List<String> ungranted(String userId, String skill, Collection<String> keys) {
                return List.of();
            }
        };
    }

    /** An account: the row a vault's salt lives on, and that a grant's user_id references. */
    public static void user(JdbcTemplate jdbc, String id) {
        jdbc.update("INSERT INTO users (id, display_name) VALUES (?, ?)", id, "Account " + id);
    }

    /** Every row of credential_grants, as {@code user skill CREDENTIAL type}, in that order. */
    public static List<String> rows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT user_id || ' ' || skill_name || ' ' || credential || ' ' || grant_type "
                + "FROM credential_grants ORDER BY user_id, skill_name, credential", String.class);
    }

    /** What changeset 028's row says now, or null when there is none. */
    public static String existing(JdbcTemplate jdbc) {
        List<String> value = jdbc.queryForList("SELECT value FROM system_settings WHERE key = ?", String.class,
                CredentialGrantService.EXISTING);
        return value.isEmpty() ? null : value.getFirst();
    }

    /**
     * The real vault over {@code jdbc}, started, that keeps what every read of values decrypted:
     * {@code account:NAME}, in the order asked. Storing a credential reads the account's secret
     * ones for the scrubbing set, so a test empties {@link #decrypted} once its vault is filled.
     */
    public static final class Vault extends CredentialVault {
        public final List<String> decrypted = new CopyOnWriteArrayList<>();

        public Vault(JdbcTemplate jdbc) {
            super(jdbc);
            init();
        }

        @Override
        public Map<String, String> getCredentials(String userId, List<String> keys) {
            Map<String, String> values = super.getCredentials(userId, keys);
            for (String key : keys) {
                if (values.containsKey(key)) decrypted.add(userId + ":" + key);
            }
            return values;
        }
    }

    /** One launch a sandbox was asked for. */
    public record Launch(Path script, String stdin, Map<String, String> env) {

        /** Whether it is a run of the skill, by its runner: not the load check of a skill_create. */
        public boolean isRun() {
            return script.getFileName().toString().startsWith("_runner_");
        }

        /** The credentials it was handed with its input, by name; null when it was handed no input. */
        public Map<String, String> credentials() {
            if (stdin == null) return null;
            try {
                JsonNode handed = JSON.readTree(stdin).get("_credentials");
                var out = new LinkedHashMap<String, String>();
                if (handed != null) handed.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
                return out;
            } catch (IOException e) {
                throw new AssertionError("a launch's input is no JSON: " + stdin, e);
            }
        }
    }

    /** A sandbox that starts nothing: it keeps each launch, and answers that the skill ran. */
    public static final class Launches implements SandboxManager {
        public final List<Launch> all = new CopyOnWriteArrayList<>();
        /** What a launch answers: success, unless a test says otherwise. */
        public volatile Function<Launch, SandboxResult> answer =
                launch -> new SandboxResult(0, "{\"success\": true, \"output\": \"ran\"}", "", 1, false);

        @Override
        public SandboxResult execute(String python, Path script, Path dir, String stdin, Map<String, String> env,
                                     int timeout, ProgressCallback progress, List<Path> readOnly) {
            Launch launch = new Launch(script, stdin, Map.copyOf(env));
            all.add(launch);
            return answer.apply(launch);
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        /** The runs of skills among the launches: every one but a skill_create's checks. */
        public List<Launch> runs() {
            return all.stream().filter(Launch::isRun).toList();
        }
    }

    /**
     * A skills directory with the real registry and the real skill manager over it, their sandbox
     * a {@link Launches}: a skill is written and registered as skill_create does it, and read from
     * its SKILL.yaml as a start reads it.
     */
    public static final class Skills {
        public final OwnClawConfig config = new OwnClawConfig();
        public final Path generated;
        public final Launches launches = new Launches();
        public final ToolRegistry tools;
        public final DynamicSkillRegistry registry;
        public final SkillManager manager;
        /** The packages a self-heal installed, each call's. */
        public final List<List<String>> installed = new CopyOnWriteArrayList<>();
        /** An environment service that sets nothing up: every skill runs on "python3", and an install is only kept. */
        public final PythonEnvironmentService python;
        private final CredentialVault vault;

        /** @param root where the skills directory is; made by the first skill, not before */
        public Skills(Path root, ToolRegistry tools, CredentialVault vault) {
            this.generated = root.resolve("skills/generated");
            this.tools = tools;
            this.vault = vault;
            config.getSkills().setGeneratedPath(generated.toString());
            python = new PythonEnvironmentService(config, JailTestKit.off()) {
                @Override
                public PythonResolution resolveExecution(Path skillDir, String skillName) {
                    return new PythonResolution("python3", Map.of(), null, null);
                }

                @Override
                public PythonResolution resolveExecution(Path skillDir, String skillName, BooleanSupplier stopped,
                                                         Runnable waiting) {
                    return new PythonResolution("python3", Map.of(), null, null);
                }

                @Override
                public void installPackages(Path skillDir, String skillName, List<String> packages,
                                            BooleanSupplier stopped, Runnable waiting) {
                    installed.add(List.copyOf(packages));
                }

                @Override
                public String getSystemPython() {
                    return "python3";
                }

                @Override
                public boolean removeEnvironments(String skillName) {
                    return true;
                }
            };
            registry = registry();
            manager = new SkillManager(registry, tools, config, launches, python, null, null);
        }

        /** Another registry over the same directory, tools and vault, not started: what a restart makes. */
        public DynamicSkillRegistry registry() {
            return new DynamicSkillRegistry(config, tools, launches, null, python, vault, null);
        }

        /**
         * skill_create of {@code name} declaring {@code credentials} -- as the model writes them: a
         * list, or one string -- through the real skill manager; the skill it registered.
         */
        public DynamicSkill create(String name, Object credentials) {
            var params = new LinkedHashMap<String, Object>();
            params.put("name", name);
            params.put("description", "Test skill " + name + ".");
            params.put("parameters", "{}");
            params.put("code", "def run(params):\n    return {'output': 'ran'}\n");
            if (credentials != null) params.put("credentials", credentials);
            String said = manager.createSkill(params);
            if (said.startsWith("ERROR")) throw new AssertionError(said);
            return registry.getDynamic(name).orElseThrow();
        }

        /** The files of a skill written where a start finds them; nothing is registered. */
        public Path onDisk(String name, String credentialsYaml) throws IOException {
            Path dir = Files.createDirectories(generated.resolve(name));
            Files.writeString(dir.resolve("SKILL.yaml"), "name: " + name + "\ndescription: Test skill " + name + ".\n"
                    + (credentialsYaml == null ? "" : "credentials: " + credentialsYaml + "\n"));
            Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {'output': 'ran'}\n");
            return dir;
        }
    }

    /** A run of {@code skill} for {@code userId}, called directly, as a test run from the Skills page calls it. */
    public static ToolResult run(DynamicSkill skill, String userId) {
        return skill.execute(Map.of("to", "someone"), new ToolExecutionContext(userId, "t1", null, () -> false));
    }
}
