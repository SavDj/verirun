package app.verirun.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Profile("worker")
public class DockerExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutor.class);

    private final DockerClient dockerClient;
    private final String verilatorImage;

    public DockerExecutor(DockerClient dockerClient,
                          @Value("${app.docker.verilator-image:verirun/verilator-uvm:latest}") String verilatorImage) {
        this.dockerClient = dockerClient;
        this.verilatorImage = verilatorImage;
    }

    public ContainerResult runBuild(Path jobDir, String[] buildCmd,
                                    boolean generateModelOnly, long maxMemoryBytes, long cpuLimit,
                                    int buildTimeoutSeconds, int maxLogSize, String jobId) {

        HostConfig hostConfig = createHostConfig(jobDir, maxMemoryBytes, cpuLimit, false);
        ContainerResult result = executeContainer(buildCmd, hostConfig, buildTimeoutSeconds, maxLogSize, jobId, "build");

        if (!result.passed() || generateModelOnly) {
            return result;
        }

        return new ContainerResult(true, result.logs(), 0, true);
    }

    public ContainerResult runSimulation(Path jobDir, String[] simCmd,
                                         long maxMemoryBytes, long cpuLimit,
                                         int runTimeoutSeconds, int maxLogSize,
                                         String buildLogs, String jobId) {

        HostConfig hostConfig = createHostConfig(jobDir, maxMemoryBytes, cpuLimit, true);
        ContainerResult result = executeContainer(simCmd, hostConfig, runTimeoutSeconds, maxLogSize, jobId, "simulation");

        return new ContainerResult(
                result.passed(),
                buildLogs + "\n=== SIMULATION OUTPUT ===\n" + result.logs(),
                result.exitCode()
        );
    }

    private HostConfig createHostConfig(Path jobDir, long maxMemoryBytes, long cpuLimit, boolean readOnlyRootfs) {
        return HostConfig.newHostConfig()
                .withBinds(Bind.parse(jobDir.toAbsolutePath() + ":/workspace"))
                .withMemory(maxMemoryBytes)
                .withCpuCount(cpuLimit)
                .withNetworkMode("none")
                .withPidsLimit(64L)
                .withReadonlyRootfs(readOnlyRootfs)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"));
    }

    private ContainerResult executeContainer(String[] cmd, HostConfig hostConfig,
                                             int timeoutSeconds, int maxLogSize, String jobId, String phase) {
        String containerId = null;
        try {
            CreateContainerResponse container = dockerClient.createContainerCmd(verilatorImage)
                    .withCmd(cmd)
                    .withHostConfig(hostConfig)
                    .withLabels(Map.of("app", "verirun", "jobId", jobId, "phase", phase))
                    .withWorkingDir("/workspace")
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(waitCallback);

            boolean completed = waitCallback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                killContainer(containerId);
                return new ContainerResult(false, "Execution timed out after " + timeoutSeconds + " seconds", -1);
            }

            Long exitCode = dockerClient.inspectContainerCmd(containerId).exec().getState().getExitCodeLong();
            String logs = fetchLogs(containerId, maxLogSize);

            boolean passed = exitCode != null && (exitCode == 0 || (phase.equals("simulation") && exitCode == 127));
            return new ContainerResult(passed, logs, exitCode != null ? exitCode.intValue() : -1);

        } catch (Exception e) {
            log.error("{} container execution failed", phase, e);
            return new ContainerResult(false, "Container error: " + e.getMessage(), -1);
        } finally {
            if (containerId != null) {
                cleanupContainer(containerId, phase);
            }
        }
    }

    private void killContainer(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
            WaitContainerResultCallback killCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(killCallback);
            killCallback.awaitCompletion(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to kill container {}", containerId, e);
        }
    }

    private String fetchLogs(String containerId, int maxLogSize) throws InterruptedException {
        LogToStringCallback logCallback = new LogToStringCallback(maxLogSize);
        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withSince(0)
                .exec(logCallback)
                .awaitCompletion(5, TimeUnit.SECONDS);
        return logCallback.toString();
    }

    private void cleanupContainer(String containerId, String label) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.debug("Cleaned up {} container {}", label, containerId);
        } catch (NotFoundException e) {
            log.debug("{} container {} already removed", label, containerId);
        } catch (Exception e) {
            log.warn("Failed to cleanup {} container {}", label, containerId, e);
        }
    }

    public record ContainerResult(boolean passed, String logs, int exitCode, boolean needsSimulation) {
        public ContainerResult(boolean passed, String logs, int exitCode) {
            this(passed, logs, exitCode, false);
        }
    }

    private static class LogToStringCallback extends ResultCallbackTemplate<LogToStringCallback, Frame> {
        private final StringBuilder output = new StringBuilder();
        private final int maxLogSize;
        private boolean truncated = false;

        public LogToStringCallback(int maxLogSize) {
            this.maxLogSize = maxLogSize;
        }

        @Override
        public void onNext(Frame item) {
            if (truncated) return;

            String payload = new String(item.getPayload());
            if (output.length() + payload.length() > maxLogSize) {
                output.append(payload, 0, maxLogSize - output.length());
                truncated = true;
            } else {
                output.append(payload);
            }
        }

        @Override
        public String toString() {
            if (truncated) {
                return output.toString() + "\n[TRUNCATED - log size limit exceeded]";
            }
            return output.toString();
        }
    }
}