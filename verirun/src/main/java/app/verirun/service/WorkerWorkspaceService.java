package app.verirun.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class WorkerWorkspaceService {

    private final Path configuredRoot;

    public WorkerWorkspaceService(@Value("${app.workspace.base-path:./verirun-workspace}") String workspaceBasePath) {
        this.configuredRoot = Path.of(workspaceBasePath);
    }

    public Path createAttemptWorkspace() throws IOException {
        try {
            Files.createDirectories(configuredRoot);
            Path realRoot = configuredRoot.toRealPath();
            return Files.createTempDirectory(realRoot, "attempt-").toRealPath();
        } catch (IOException e) {
            throw new IOException("Failed to establish worker workspace", e);
        }
    }

    public void deleteAttemptWorkspace(Path attemptWorkspace) throws IOException {
        if (attemptWorkspace == null || !Files.exists(attemptWorkspace)) {
            return;
        }

        try {
            Path realRoot = configuredRoot.toRealPath();
            Path realAttempt = attemptWorkspace.toRealPath();

            if (realAttempt.equals(realRoot) || !realAttempt.startsWith(realRoot)) {
                throw new IOException("Refusing to delete an invalid worker workspace");
            }

            if (!FileSystemUtils.deleteRecursively(realAttempt)) {
                throw new IOException("Failed to delete worker workspace");
            }
        } catch (IOException e) {
            throw new IOException("Failed to clean up worker workspace", e);
        }
    }
}
