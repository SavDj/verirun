package app.verirun.storage;

import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.PropertiesS3ObjectContentTypeResolver;
import io.awspring.cloud.s3.S3ObjectConverter;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static app.verirun.storage.SimulationStorageService.Input.DESIGN;
import static app.verirun.storage.SimulationStorageService.Input.TESTBENCH;
import static app.verirun.storage.SimulationStorageService.Output.MODEL;
import static app.verirun.storage.SimulationStorageService.Output.SIMULATION_LOG;
import static app.verirun.storage.SimulationStorageService.Output.WAVEFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class S3SimulationStorageServiceIntegrationTest {

    private static final String ACCESS_KEY = "access-key";
    private static final String SECRET_KEY = "secret-key";
    private static final String BUCKET = "verirun-test";

    private static final GenericContainer<?> S3_MOCK = new GenericContainer<>("adobe/s3mock:5.1.0").withExposedPorts(9090).waitingFor(Wait.forHttp("/favicon.ico").forPort(9090).forStatusCode(200));

    private static S3Client s3Client;
    private static S3Presigner s3Presigner;
    private static S3SimulationStorageService storageService;

    @BeforeAll
    static void setUpStorage() {
        S3_MOCK.start();

        URI endpoint = URI.create("http://" + S3_MOCK.getHost() + ":" + S3_MOCK.getMappedPort(9090));

        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));

        S3Configuration s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        s3Client = S3Client.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).serviceConfiguration(s3Configuration).build();

        s3Presigner = S3Presigner.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).serviceConfiguration(s3Configuration).build();

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        S3Template s3Template = new S3Template(s3Client, new InMemoryBufferingS3OutputStreamProvider(s3Client, new PropertiesS3ObjectContentTypeResolver()), mock(S3ObjectConverter.class), s3Presigner);

        storageService = new S3SimulationStorageService(s3Template, BUCKET);
    }

    @AfterAll
    static void tearDownStorage() {
        if (s3Presigner != null) {
            s3Presigner.close();
        }

        if (s3Client != null) {
            s3Client.close();
        }

        S3_MOCK.stop();
    }

    @Test
    void typedWrites_shouldUseExactKeysAndPreserveContent(@TempDir Path tempDir) throws IOException {

        UUID jobId = UUID.randomUUID();
        String prefix = "verirun/v2/jobs/" + jobId;

        String design = "module café; endmodule";
        String testbench = "module tb_λ; endmodule";
        byte[] simulationLog = "simulation log".getBytes(StandardCharsets.UTF_8);
        byte[] waveform = "waveform bytes".getBytes(StandardCharsets.UTF_8);
        byte[] model = "model bytes".getBytes(StandardCharsets.UTF_8);

        storageService.writeInput(jobId, DESIGN, design);
        storageService.writeInput(jobId, TESTBENCH, testbench);

        Path simulationLogFile = tempDir.resolve("simulation.log");
        Path waveformFile = tempDir.resolve("waveform.vcd");
        Path modelFile = tempDir.resolve("model.zip");

        Files.write(simulationLogFile, simulationLog);
        Files.write(waveformFile, waveform);
        Files.write(modelFile, model);

        storageService.uploadOutput(jobId, SIMULATION_LOG, simulationLogFile);
        storageService.uploadOutput(jobId, WAVEFORM, waveformFile);
        storageService.uploadOutput(jobId, MODEL, modelFile);

        assertThat(rawObject(prefix + "/inputs/design.sv")).isEqualTo(design.getBytes(StandardCharsets.UTF_8));
        assertThat(rawObject(prefix + "/inputs/testbench.sv")).isEqualTo(testbench.getBytes(StandardCharsets.UTF_8));
        assertThat(rawObject(prefix + "/outputs/simulation.log")).isEqualTo(simulationLog);
        assertThat(rawObject(prefix + "/outputs/waveform.vcd")).isEqualTo(waveform);
        assertThat(rawObject(prefix + "/outputs/model.zip")).isEqualTo(model);
    }

    @Test
    void typedReads_shouldUseExactKeys() throws IOException {
        UUID jobId = UUID.randomUUID();
        String prefix = "verirun/v2/jobs/" + jobId;

        byte[] design = "design content".getBytes(StandardCharsets.UTF_8);
        byte[] testbench = "testbench content".getBytes(StandardCharsets.UTF_8);
        byte[] simulationLog = "simulation log".getBytes(StandardCharsets.UTF_8);
        byte[] waveform = "waveform bytes".getBytes(StandardCharsets.UTF_8);
        byte[] model = "model bytes".getBytes(StandardCharsets.UTF_8);

        putRawObject(prefix + "/inputs/design.sv", design);
        putRawObject(prefix + "/inputs/testbench.sv", testbench);
        putRawObject(prefix + "/outputs/simulation.log", simulationLog);
        putRawObject(prefix + "/outputs/waveform.vcd", waveform);
        putRawObject(prefix + "/outputs/model.zip", model);

        assertThat(read(storageService.readInput(jobId, DESIGN))).isEqualTo(design);
        assertThat(read(storageService.readInput(jobId, TESTBENCH))).isEqualTo(testbench);
        assertThat(read(storageService.downloadOutput(jobId, SIMULATION_LOG))).isEqualTo(simulationLog);
        assertThat(read(storageService.downloadOutput(jobId, WAVEFORM))).isEqualTo(waveform);
        assertThat(read(storageService.downloadOutput(jobId, MODEL))).isEqualTo(model);
    }

    @Test
    void deleteInput_shouldDeleteOnlyRequestedInput() {
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();

        String firstPrefix = "verirun/v2/jobs/" + firstJob;
        String secondPrefix = "verirun/v2/jobs/" + secondJob;

        String designKey = firstPrefix + "/inputs/design.sv";
        String testbenchKey = firstPrefix + "/inputs/testbench.sv";
        String otherJobDesignKey = secondPrefix + "/inputs/design.sv";
        String outputKey = firstPrefix + "/outputs/model.zip";

        putRawObject(designKey, "design".getBytes(StandardCharsets.UTF_8));
        putRawObject(testbenchKey, "testbench".getBytes(StandardCharsets.UTF_8));
        putRawObject(otherJobDesignKey, "other design".getBytes(StandardCharsets.UTF_8));
        putRawObject(outputKey, "model".getBytes(StandardCharsets.UTF_8));

        storageService.deleteInput(firstJob, DESIGN);

        assertThat(rawObjectExists(designKey)).isFalse();
        assertThat(rawObjectExists(testbenchKey)).isTrue();
        assertThat(rawObjectExists(otherJobDesignKey)).isTrue();
        assertThat(rawObjectExists(outputKey)).isTrue();
    }

    private static byte[] read(Resource resource) throws IOException {
        try (var inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static void putRawObject(String key, byte[] content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(content));
    }

    private static byte[] rawObject(String key) throws IOException {
        try (var response = s3Client.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build())) {
            return response.readAllBytes();
        }
    }

    private static boolean rawObjectExists(String key) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(key).build()).contents().stream().anyMatch(object -> object.key().equals(key));
    }
}