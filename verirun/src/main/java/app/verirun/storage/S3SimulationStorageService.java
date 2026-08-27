package app.verirun.storage;

import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class S3SimulationStorageService implements SimulationStorageService {

    private final S3Template s3Template;
    private final String bucketName;

    public S3SimulationStorageService(S3Template s3Template,
                                      @Value("${app.storage.bucket-name}") String bucketName) {
        this.s3Template = s3Template;
        this.bucketName = bucketName;
    }

    @Override
    public void writeInput(UUID jobId, Input input, String content) {
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        s3Template.upload(bucketName, inputKey(jobId, input), inputStream);
    }

    @Override
    public Resource readInput(UUID jobId, Input input) {
        return s3Template.download(bucketName, inputKey(jobId, input));
    }

    @Override
    public void deleteInput(UUID jobId, Input input) {
        s3Template.deleteObject(bucketName, inputKey(jobId, input));
    }

    @Override
    public void uploadOutput(UUID jobId, Output output, Path localFile) {
        try (InputStream inputStream = Files.newInputStream(localFile)) {
            s3Template.upload(bucketName, outputKey(jobId, output), inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read simulation output for upload", e);
        }
    }

    @Override
    public Resource downloadOutput(UUID jobId, Output output) {
        return s3Template.download(bucketName, outputKey(jobId, output));
    }

    private String inputKey(UUID jobId, Input input) {
        return jobPrefix(jobId) + "/inputs/" + input.fileName();
    }

    private String outputKey(UUID jobId, Output output) {
        return jobPrefix(jobId) + "/outputs/" + output.fileName();
    }

    private String jobPrefix(UUID jobId) {
        return "verirun/v2/jobs/" + jobId;
    }
}
