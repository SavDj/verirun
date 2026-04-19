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

    private static final String VERILATOR_IMAGE = "verirun/verilator-uvm:latest";

    public DockerExecutor(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public ContainerResult runBuild(Path jobDir, String[] buildCmd,
                                    boolean generateModelOnly, long maxMemoryBytes, long cpuLimit,
                                    int buildTimeoutSeconds, int maxLogSize, String jobId) {
        String buildContainerId = null;

        try {
            CreateContainerResponse buildContainer = dockerClient.createContainerCmd(VERILATOR_IMAGE)
                    .withCmd(buildCmd)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(Bind.parse(jobDir.toAbsolutePath() + ":/workspace"))
                            .withMemory(maxMemoryBytes)
                            .withCpuCount(cpuLimit)
                            .withNetworkMode("none")
                            .withPidsLimit(64L)
                            .withReadonlyRootfs(false)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges")))
                    .withLabels(Map.of("app", "verirun", "jobId", jobId, "phase", "build"))
                    .withVolumes(new com.github.dockerjava.api.model.Volume("/workspace"))
                    .withWorkingDir("/workspace")
                    .exec();

            buildContainerId = buildContainer.getId();

            dockerClient.startContainerCmd(buildContainerId).exec();

            WaitContainerResultCallback callback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(buildContainerId).exec(callback);

            boolean completed = callback.awaitCompletion(buildTimeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                dockerClient.killContainerCmd(buildContainerId).exec();
                WaitContainerResultCallback killCallback = new WaitContainerResultCallback();
                dockerClient.waitContainerCmd(buildContainerId).exec(killCallback);
                killCallback.awaitCompletion(5, TimeUnit.SECONDS);
                return new ContainerResult(false, "Build timed out after " + buildTimeoutSeconds + " seconds", -1);
            }

            Long buildExitCode = dockerClient.inspectContainerCmd(buildContainerId)
                    .exec().getState().getExitCodeLong();
            LogToStringCallback logCallback = new LogToStringCallback(maxLogSize);
            dockerClient.logContainerCmd(buildContainerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withSince(0)
                    .exec(logCallback)
                    .awaitCompletion(5, TimeUnit.SECONDS);

            String buildLogs = logCallback.toString();

            if (buildExitCode == null || buildExitCode != 0) {
                return new ContainerResult(false, "BUILD FAILED:\n" + buildLogs,
                        buildExitCode != null ? buildExitCode.intValue() : -1);
            }

            if (!generateModelOnly) {
                return new ContainerResult(true, buildLogs, 0, true);
            }

            return new ContainerResult(true, buildLogs, 0);

        } catch (Exception e) {
            log.error("Build container execution failed", e);
            return new ContainerResult(false, "Container error: " + e.getMessage(), -1);
        } finally {
            if (buildContainerId != null) {
                cleanupContainer(buildContainerId, "build");
            }
        }
    }

    public ContainerResult runSimulation(Path jobDir, String[] simCmd,
                                         long maxMemoryBytes, long cpuLimit,
                                         int runTimeoutSeconds, int maxLogSize,
                                         String buildLogs, String jobId) {
        String runContainerId = null;

        try {
            CreateContainerResponse runContainer = dockerClient.createContainerCmd(VERILATOR_IMAGE)
                    .withCmd(simCmd)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(Bind.parse(jobDir.toAbsolutePath() + ":/workspace"))
                            .withMemory(maxMemoryBytes)
                            .withCpuCount(cpuLimit)
                            .withNetworkMode("none")
                            .withPidsLimit(64L)
                            .withReadonlyRootfs(true)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges")))
                    .withLabels(Map.of("app", "verirun", "jobId", jobId, "phase", "simulation"))
                    .withVolumes(new com.github.dockerjava.api.model.Volume("/workspace"))
                    .withWorkingDir("/workspace")
                    .exec();

            runContainerId = runContainer.getId();

            dockerClient.startContainerCmd(runContainerId).exec();

            WaitContainerResultCallback simCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(runContainerId).exec(simCallback);

            boolean simCompleted = simCallback.awaitCompletion(runTimeoutSeconds, TimeUnit.SECONDS);

            if (!simCompleted) {
                dockerClient.killContainerCmd(runContainerId).exec();
                WaitContainerResultCallback simKillCallback = new WaitContainerResultCallback();
                dockerClient.waitContainerCmd(runContainerId).exec(simKillCallback);
                simKillCallback.awaitCompletion(5, TimeUnit.SECONDS);
                return new ContainerResult(false,
                        buildLogs + "\n=== SIMULATION TIMED OUT ===", -1);
            }

            LogToStringCallback simLogCallback = new LogToStringCallback(maxLogSize);
            dockerClient.logContainerCmd(runContainerId)
                    .withStdOut(true).withStdErr(true)
                    .exec(simLogCallback)
                    .awaitCompletion(5, TimeUnit.SECONDS);
            String simLogs = simLogCallback.toString();
            Long simExitCode = dockerClient.inspectContainerCmd(runContainerId).exec().getState().getExitCodeLong();

            cleanupContainer(runContainerId, "simulation");
            runContainerId = null;

            return new ContainerResult(simExitCode != null && (simExitCode == 0 || simExitCode == 127),
                    buildLogs + "\n=== SIMULATION OUTPUT ===\n" + simLogs,
                    simExitCode != null ? simExitCode.intValue() : -1);

        } catch (Exception e) {
            log.error("Simulation container execution failed", e);
            return new ContainerResult(false, buildLogs + "\nContainer error: " + e.getMessage(), -1);
        } finally {
            if (runContainerId != null) {
                cleanupContainer(runContainerId, "simulation");
            }
        }
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

        public LogToStringCallback(int maxLogSize) {
            this.maxLogSize = maxLogSize;
        }

        @Override
        public void onNext(Frame item) {
            output.append(new String(item.getPayload()));
        }

        @Override
        public String toString() {
            String result = output.toString();
            if (result.length() > maxLogSize) {
                return result.substring(0, maxLogSize) + "\n[TRUNCATED - log size limit exceeded]";
            }
            return result;
        }
    }
}
