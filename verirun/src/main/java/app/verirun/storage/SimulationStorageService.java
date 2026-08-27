package app.verirun.storage;

import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.util.UUID;

public interface SimulationStorageService {

    void writeInput(UUID jobId, Input input, String content);

    Resource readInput(UUID jobId, Input input);

    void deleteInput(UUID jobId, Input input);

    void uploadOutput(UUID jobId, Output output, Path localFile);

    Resource downloadOutput(UUID jobId, Output output);

    enum Input {
        DESIGN("design.sv"),
        TESTBENCH("testbench.sv");

        private final String fileName;

        Input(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    enum Output {
        SIMULATION_LOG("simulation.log"),
        WAVEFORM("waveform.vcd"),
        MODEL("model.zip");

        private final String fileName;

        Output(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }
}
