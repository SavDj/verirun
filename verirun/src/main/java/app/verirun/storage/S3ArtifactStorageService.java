package app.verirun.storage;

import io.awspring.cloud.s3.S3Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class S3ArtifactStorageService implements ArtifactStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ArtifactStorageService.class);

    private final S3Template s3Template;
    private final ResourceLoader resourceLoader;
    private final String bucketName;

    public S3ArtifactStorageService(S3Template s3Template,
                                    ResourceLoader resourceLoader,
                                    @Value("${app.storage.bucket-name}") String bucketName) {
        this.s3Template = s3Template;
        this.resourceLoader = resourceLoader;
        this.bucketName = bucketName;
    }

    @Override
    public void uploadArtifact(String jobId, String fileName, Path localFile) {
        String key = jobId + "/" + fileName;
        try (InputStream inputStream = Files.newInputStream(localFile)) {
            s3Template.upload(bucketName, key, inputStream);
            log.info("Uploaded {} to S3", key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload artifact to S3", e);
        }
    }

    @Override
    public Resource downloadArtifact(String jobId, String fileName) {
        String s3Uri = "s3://" + bucketName + "/" + jobId + "/" + fileName;
        return resourceLoader.getResource(s3Uri);
    }
}