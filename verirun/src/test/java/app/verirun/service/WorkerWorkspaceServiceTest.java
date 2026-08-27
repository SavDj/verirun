package app.verirun.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createAttemptWorkspace_shouldCreateDistinctDirectoriesUnderConfiguredRoot() throws IOException {
        Path root = tempDir.resolve("workspace");
        WorkerWorkspaceService service = new WorkerWorkspaceService(root.toString());

        Path first = service.createAttemptWorkspace();
        Path second = service.createAttemptWorkspace();

        assertThat(first).isDirectory().startsWith(root.toRealPath());
        assertThat(second).isDirectory().startsWith(root.toRealPath());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void deleteAttemptWorkspace_shouldDeleteAttemptRecursivelyWithoutDeletingRoot() throws IOException {
        Path root = tempDir.resolve("workspace");
        WorkerWorkspaceService service = new WorkerWorkspaceService(root.toString());

        Path attempt = service.createAttemptWorkspace();
        Path rootSentinel = Files.writeString(root.resolve("keep.txt"), "keep");

        Path nestedFile = attempt.resolve("obj_dir/nested/model.cpp");
        Files.createDirectories(nestedFile.getParent());
        Files.writeString(nestedFile, "generated model");

        service.deleteAttemptWorkspace(attempt);

        assertThat(attempt).doesNotExist();
        assertThat(rootSentinel).exists();
    }

}
