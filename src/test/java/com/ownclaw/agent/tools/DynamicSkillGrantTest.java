package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.RealBubblewrap;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a directly-run skill is given to read besides the read-only host: its own environment
 * and exactly the files its task was handed -- by the real path it is told in
 * {@code _attached_files}, the same on the first run and on a self-healed retry. A record that
 * points anywhere else is dropped, and the task goes on without it.
 */
class DynamicSkillGrantTest {

    @TempDir Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A sandbox that records every launch and answers from a script. */
    static final class Recording implements SandboxManager {
        final List<List<Path>> readOnly = new ArrayList<>();
        final List<String> stdin = new ArrayList<>();
        private final Deque<SandboxResult> answers;

        Recording(SandboxResult... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public SandboxResult execute(String python, Path script, Path dir, String in, Map<String, String> env,
                                     int timeout, ProgressCallback cb, List<Path> ro) {
            readOnly.add(List.copyOf(ro));
            stdin.add(in);
            return answers.isEmpty()
                    ? new SandboxResult(0, "{\"success\": true, \"output\": \"read it\"}", "", 1, false)
                    : answers.poll();
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }
    }

    /** Uploads and their records, as FileStorageService keeps them. */
    private record Store(FileStorageService files, JdbcTemplate jdbc, Path db, Path uploads) {
        String row(String storedName) {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO file_attachments (id, user_id, original_name, stored_name, content_type,"
                    + " size_bytes) VALUES (?, 'u1', 'statement.pdf', ?, 'application/pdf', 1)", id, storedName);
            return id;
        }

        String store(String user, String name, String content) throws Exception {
            return files.store(user, name, "text/plain",
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private Store store(Path dataDir) throws Exception {
        Path db = dataDir.resolve("ownclaw.db");
        var jdbc = MigratedDatabase.at(db);
        var config = new OwnClawConfig();
        config.getDatabase().setPath(db.toString());
        Path uploads = Files.createDirectories(FileStorageService.uploadsDirFor(config));
        return new Store(new FileStorageService(jdbc, config), jdbc, db, uploads);
    }

    private PythonEnvironmentService environment(Path envDir, List<List<String>> installs) {
        return new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()) {
            @Override
            public PythonResolution resolveExecution(Path skillDir, String skillName) {
                return new PythonResolution("python3", Map.of(), envDir);
            }

            @Override
            public boolean installPackages(Path skillDir, String skillName, List<String> packages) {
                installs.add(packages);
                return true;
            }
        };
    }

    private DynamicSkill skill(Path skillDir, SandboxManager sandbox, PythonEnvironmentService env,
                               FileStorageService files) throws Exception {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        return new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                sandbox, env, List.of(), null, List.of(), null, null, files);
    }

    private List<String> attachedPaths(String stdin) throws Exception {
        var out = new ArrayList<String>();
        JsonNode attached = mapper.readTree(stdin).get("_attached_files");
        if (attached != null) attached.forEach(f -> out.add(f.get("path").asText()));
        return out;
    }

    @Test
    @DisplayName("the run and its self-healed retry read the same things: the env dir and the handed file")
    void runAndRetryGetTheSameGrant() throws Exception {
        Store s = store(Files.createDirectories(tmp.resolve("data")));
        String handed = s.store("u1", "statement.txt", "handed-content");
        String other = s.store("u2", "someone_elses.txt", "canary-other-upload");
        Path otherPath = s.files().getFilePath(other).toRealPath();
        Path handedPath = s.files().getFilePath(handed).toRealPath();

        Path envDir = Files.createDirectories(tmp.resolve("skills/_envs/pdf_text"));
        var installs = new ArrayList<List<String>>();
        var sandbox = new Recording(
                new SandboxResult(1, "", "ModuleNotFoundError: No module named 'pdfplumber'\n", 1, false));
        var skill = skill(tmp.resolve("skills/generated/pdf_text"), sandbox, environment(envDir, installs), s.files());

        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of(handed)));

        assertTrue(result.success(), result.output());
        assertEquals(List.of(List.of("pdfplumber")), installs);
        assertEquals(2, sandbox.readOnly.size(), "the first run and the self-healed retry");
        assertEquals(List.of(envDir, handedPath), sandbox.readOnly.get(0));
        assertEquals(sandbox.readOnly.get(0), sandbox.readOnly.get(1), "the retry is given exactly what the run was");
        for (List<Path> ro : sandbox.readOnly) {
            assertFalse(ro.contains(otherPath), "an upload this task was not handed");
            assertFalse(ro.contains(s.uploads()) || ro.contains(s.uploads().toRealPath()), "the whole uploads directory");
        }
        for (String in : sandbox.stdin) {
            assertEquals(List.of(handedPath.toString()), attachedPaths(in),
                    "the skill is told the very path that is bound");
        }
    }

    @Test
    @DisplayName("records that name anything but a regular file directly in uploads are dropped, and the task runs")
    void badRecordsAreDroppedNotThrown() throws Exception {
        Store s = store(Files.createDirectories(tmp.resolve("data")));
        String handed = s.store("u1", "statement.txt", "handed-content");
        String other = s.store("u2", "someone_elses.txt", "canary-other-upload");
        Path handedPath = s.files().getFilePath(handed).toRealPath();

        String traversal = s.row("../ownclaw.db");
        String gone = s.store("u1", "deleted.txt", "x");
        Files.delete(s.files().getFilePath(gone));
        Files.createSymbolicLink(s.uploads().resolve("linked-db.pdf"), s.db());
        String linkToDb = s.row("linked-db.pdf");
        Files.createSymbolicLink(s.uploads().resolve("alias.pdf"), s.files().getFilePath(other));
        String linkToOther = s.row("alias.pdf");
        Files.createDirectories(s.uploads().resolve("folder.pdf"));
        String directory = s.row("folder.pdf");

        var sandbox = new Recording();
        var skill = skill(tmp.resolve("skills/generated/pdf_text"), sandbox,
                environment(null, new ArrayList<>()), s.files());
        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null,
                List.of(traversal, gone, linkToDb, handed, linkToOther, directory, "no-such-id")));

        assertTrue(result.success(), result.output());
        assertEquals(List.of(List.of(handedPath)), sandbox.readOnly,
                "only the handed file -- and no environment, since this skill has none");
        assertEquals(List.of(handedPath.toString()), attachedPaths(sandbox.stdin.get(0)));
    }

    @Test
    @DisplayName("a data directory reached through a link is handed by its real path, told and bound alike")
    void aLinkedDataDirectoryIsHandedByItsRealPath() throws Exception {
        Path realData = Files.createDirectories(tmp.resolve("disk/ownclaw-data"));
        Path data = Files.createSymbolicLink(tmp.resolve("data"), realData);
        Store s = store(data);
        String handed = s.store("u1", "statement.txt", "handed-content");
        Path real = realData.resolve("uploads").resolve(s.files().getFilePath(handed).getFileName());
        assertNotEquals(real, s.files().getFilePath(handed), "the stored path goes through the link");

        var sandbox = new Recording();
        var skill = skill(tmp.resolve("skills/generated/pdf_text"), sandbox,
                environment(null, new ArrayList<>()), s.files());
        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null,
                List.of(handed)));

        assertTrue(result.success(), result.output());
        assertEquals(List.of(List.of(real)), sandbox.readOnly);
        assertEquals(List.of(real.toString()), attachedPaths(sandbox.stdin.get(0)));
    }

    @Test
    @DisplayName("jailed for real: the skill reads its handed file by the path it is told, and sees no other upload")
    void theHandedFileThroughTheRealJail() throws Exception {
        RealBubblewrap.assumeWorks();
        // Where the jail shows the host, so only its tmpfs can hide what is here: under /tmp,
        // which each run has its own of, the hiding would pass whatever the jail did.
        Path base = JailTestKit.visibleDir("grant-it-");
        try {
            handedFileThroughTheRealJail(base);
        } finally {
            SkillJail.deleteTree(base);
        }
    }

    private void handedFileThroughTheRealJail(Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("opt-ownclaw"));
        Files.writeString(root.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        Store s = store(Files.createDirectories(root.resolve("data")));
        String handed = s.store("u1", "statement.txt", "handed-content");
        s.store("u2", "someone_elses.txt", "canary-other-upload");
        String traversal = s.row("../ownclaw.db");
        Path generated = Files.createDirectories(root.resolve("skills/generated"));
        Path skillDir = Files.createDirectories(generated.resolve("pdf_text"));
        Files.writeString(skillDir.resolve("SKILL.yaml"), "name: pdf_text\n");

        SkillJail jail = RealBubblewrap.requiredJail(new SkillJail.Layout(root, s.db(), generated, null));
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getDatabase().setPath(s.db().toString());
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg, jail);
        env.init();
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                new ProcessSandbox(jail), env, List.of(), null, List.of(), null, null, s.files());
        // Prints only facts about what it could reach, never contents it should not have.
        Files.writeString(skillDir.resolve("skill.py"), """
                import json, os
                def run(params):
                    files = params.get('_attached_files', [])
                    def readable(p):
                        try:
                            open(p, 'rb').read(1)
                            return True
                        except Exception:
                            return False
                    return {'output': json.dumps({
                        'told': [f['path'] for f in files],
                        'handed': [open(f['path']).read() for f in files],
                        'listing': sorted(os.listdir(os.path.dirname(files[0]['path']))) if files else None,
                        'db': readable(params['db']),
                    })}
                """);

        var result = skill.execute(Map.of("db", s.db().toString()), new ToolExecutionContext("u1", "t1", null,
                () -> false, null, List.of(traversal, handed)));

        assertTrue(result.success(), result.output());
        JsonNode seen = mapper.readTree(result.output());
        Path handedPath = s.files().getFilePath(handed).toRealPath();
        assertEquals(List.of(handedPath.toString()), mapper.convertValue(seen.get("told"), List.class));
        assertEquals(List.of("handed-content"), mapper.convertValue(seen.get("handed"), List.class));
        assertEquals(List.of(handedPath.getFileName().toString()), mapper.convertValue(seen.get("listing"), List.class),
                "the uploads directory must show only the handed file");
        assertFalse(seen.get("db").asBoolean(), "the database is readable inside the jail");
        assertEquals(0, jail.status().directRuns() + jail.status().refusedRuns(),
                "every launch was jailed, and the tampered record did not refuse the run");
    }
}
