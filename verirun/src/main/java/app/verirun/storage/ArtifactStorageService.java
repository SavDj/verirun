package app.verirun.storage;

import org.springframework.core.io.Resource;
import java.nio.file.Path;

public interface ArtifactStorageService {
    void uploadArtifact(String jobId, String fileName, Path localFile);
    Resource downloadArtifact(String jobId, String fileName);
}