package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a container skill can see of the uploads directory: the files it was handed, each
 * read-only, and nothing else. The directory holds every file every user has sent.
 */
class DynamicSkillMountTest {

    /** A container runtime that records what it was asked to run, and runs nothing. */
    static final class Recording extends ContainerSandbox {
        Map<String, String> volumes;
        String stdin;
        Recording() { super(new OwnClawConfig()); }
        @Override public boolean isAvailable() { return true; }
        @Override public String ensureImage(List<String> packages, String pip, Path dir, String image,
                                            SandboxManager.ProgressCallback cb) { return "test-image"; }
        @Override public SandboxResult execute(String image, String python, Path script, Path dir,
                                               String stdinJson, Map<String, String> env, int timeout,
                                               SandboxManager.ProgressCallback cb,
                                               Map<String, String> extraVolumes) {
            volumes = extraVolumes;
            stdin = stdinJson;
            return new SandboxResult(0, "{\"success\": true, \"output\": \"read it\"}", "", 1, false);
        }
    }

    @Test
    @DisplayName("a container skill is given the handed file alone, never the uploads directory")
    @SuppressWarnings("unchecked")
    void onlyTheHandedFileIsMounted(@TempDir Path tmp) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("CREATE TABLE file_attachments (id TEXT PRIMARY KEY, user_id TEXT, original_name TEXT, "
                + "stored_name TEXT, content_type TEXT, size_bytes INTEGER, uploaded_at TEXT)");
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        String handed = files.store("u1", "statement.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)));
        files.store("u2", "someone_elses.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)));

        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var container = new Recording();
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                null, new PythonEnvironmentService(new OwnClawConfig()), List.of(), null,
                List.of("poppler-utils"), null, container, files);

        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of(handed)));
        assertTrue(result.success(), result.output());

        String hostPath = files.getFilePath(handed).toAbsolutePath().toString();
        String containerPath = "/uploads/" + files.getFilePath(handed).getFileName();
        assertEquals(Map.of(hostPath, containerPath), container.volumes,
                "exactly the handed file, at the path the skill is told");
        assertFalse(container.volumes.containsKey(files.getUploadsDir().toAbsolutePath().toString()),
                "the uploads directory holds every user's files");

        var attached = (List<Map<String, String>>) new ObjectMapper().readValue(container.stdin, Map.class)
                .get("_attached_files");
        assertEquals(1, attached.size());
        assertEquals(hostPath, attached.get(0).get("path"));
        assertEquals(containerPath, attached.get(0).get("container_path"));
        assertEquals(Map.of(hostPath, containerPath), DynamicSkill.containerMounts(attached),
                "the mounts are built from the very list the skill is handed");
    }

    @Test
    @DisplayName("a self-healed retry still has the file: the fix was made so the skill could read it")
    void theRetryKeepsTheFile(@TempDir Path tmp) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("CREATE TABLE file_attachments (id TEXT PRIMARY KEY, user_id TEXT, original_name TEXT, "
                + "stored_name TEXT, content_type TEXT, size_bytes INTEGER, uploaded_at TEXT)");
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        String handed = files.store("u1", "statement.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)));

        // First run: the module is missing. The retry after installing it succeeds.
        var runs = new java.util.ArrayList<Map<String, String>>();
        var container = new ContainerSandbox(new OwnClawConfig()) {
            @Override public boolean isAvailable() { return true; }
            @Override public String ensureImage(List<String> packages, String pip, Path dir, String image,
                                                SandboxManager.ProgressCallback cb) { return "test-image"; }
            @Override public SandboxResult execute(String image, String python, Path script, Path dir,
                                                   String stdinJson, Map<String, String> env, int timeout,
                                                   SandboxManager.ProgressCallback cb,
                                                   Map<String, String> extraVolumes) {
                runs.add(extraVolumes);
                return runs.size() == 1
                        ? new SandboxResult(1, "", "ModuleNotFoundError: No module named 'pdfplumber'", 1, false)
                        : new SandboxResult(0, "{\"success\": true, \"output\": \"read it\"}", "", 1, false);
            }
        };
        var installs = new PythonEnvironmentService(new OwnClawConfig()) {
            @Override public boolean installPackages(Path skillDir, String skillName, List<String> packages) {
                return true;
            }
        };
        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                null, installs, List.of(), null, List.of("poppler-utils"), null, container, files);

        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of(handed)));
        assertTrue(result.success(), result.output());
        assertEquals(2, runs.size(), "the first run and the self-healed retry");
        assertNotNull(runs.get(1), "the retry is given the file too");
        assertEquals(runs.get(0), runs.get(1));
    }

    @Test
    @DisplayName("a task with no file mounts nothing")
    void noFileNoMount(@TempDir Path tmp) throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var container = new Recording();
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                null, new PythonEnvironmentService(new OwnClawConfig()), List.of(), null,
                List.of("poppler-utils"), null, container, null);

        skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of()));
        assertNull(container.volumes);
        assertFalse(container.stdin.contains("_attached_files"));
    }
}
