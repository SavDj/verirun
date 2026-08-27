package app.verirun.service;

import app.verirun.exception.InputMaterializationException;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Input;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationInputMaterializerTest {

    @Mock
    private SimulationStorageService storageService;

    @TempDir
    Path workspace;

    private SimulationInputMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new SimulationInputMaterializer(storageService);
    }

    @Test
    void materialize_shouldMaterializeDesignOnlyWhenTestbenchIsNotExpected() throws IOException {
        UUID jobId = UUID.randomUUID();
        String design = "module design;\nendmodule\n";
        when(storageService.readInput(jobId, Input.DESIGN)).thenReturn(new ByteArrayResource(design.getBytes(StandardCharsets.UTF_8)));

        materializer.materialize(jobId, false, workspace);

        assertThat(Files.readString(workspace.resolve("design.sv"))).isEqualTo(design);
        assertThat(workspace.resolve("testbench.sv")).doesNotExist();
        verify(storageService, never()).readInput(jobId, Input.TESTBENCH);
    }

    @Test
    void materialize_shouldMaterializeDesignAndTestbenchWhenExpected() throws IOException {
        UUID jobId = UUID.randomUUID();
        String design = "module design;\nendmodule\n";
        String testbench = "module testbench;\nendmodule\n";

        when(storageService.readInput(jobId, Input.DESIGN)).thenReturn(new ByteArrayResource(design.getBytes(StandardCharsets.UTF_8)));
        when(storageService.readInput(jobId, Input.TESTBENCH)).thenReturn(new ByteArrayResource(testbench.getBytes(StandardCharsets.UTF_8)));

        materializer.materialize(jobId, true, workspace);

        assertThat(Files.readString(workspace.resolve("design.sv"))).isEqualTo(design);
        assertThat(Files.readString(workspace.resolve("testbench.sv"))).isEqualTo(testbench);
    }

    @Test
    void materialize_shouldTranslateStorageLookupFailure() {
        UUID jobId = UUID.randomUUID();
        RuntimeException storageFailure = new RuntimeException("storage unavailable");

        when(storageService.readInput(jobId, Input.DESIGN)).thenThrow(storageFailure);

        assertThatThrownBy(() -> materializer.materialize(jobId, false, workspace))
                .isInstanceOf(InputMaterializationException.class)
                .hasMessageContaining("design.sv")
                .hasMessageContaining("storage lookup")
                .hasCause(storageFailure);
    }

    @Test
    void materialize_shouldTranslateInputCopyFailure() {
        UUID jobId = UUID.randomUUID();
        IOException copyFailure = new IOException("stream failed");

        Resource failedRead = new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw copyFailure;
            }
        };

        when(storageService.readInput(jobId, Input.DESIGN)).thenReturn(failedRead);

        assertThatThrownBy(() -> materializer.materialize(jobId, false, workspace))
                .isInstanceOf(InputMaterializationException.class)
                .hasMessageContaining("design.sv")
                .hasMessageContaining("input copy")
                .hasCause(copyFailure);
    }

    @Test
    void materialize_shouldRejectContentLengthMismatchWithoutPublishingInput() {
        UUID jobId = UUID.randomUUID();
        byte[] design = "module design;\nendmodule\n".getBytes(StandardCharsets.UTF_8);

        Resource designWithIncorrectLength = new ByteArrayResource(design) {
            @Override
            public long contentLength() {
                return design.length + 1L;
            }
        };

        when(storageService.readInput(jobId, Input.DESIGN)).thenReturn(designWithIncorrectLength);

        assertThatThrownBy(() -> materializer.materialize(jobId, false, workspace))
                .isInstanceOf(InputMaterializationException.class)
                .hasMessageContaining("design.sv")
                .hasMessageContaining("content length mismatch");

        assertThat(workspace.resolve("design.sv")).doesNotExist();
    }
}
