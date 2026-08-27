package app.verirun.service;

import app.verirun.exception.InputMaterializationException;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Input;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SimulationInputMaterializer {

    private final SimulationStorageService storageService;

    public SimulationInputMaterializer(SimulationStorageService storageService) {
        this.storageService = storageService;
    }

    public void materialize(UUID jobId, boolean testbenchExpected, Path workspace) throws InputMaterializationException {
        List<Input> inputs = testbenchExpected ? List.of(Input.DESIGN, Input.TESTBENCH) : List.of(Input.DESIGN);

        for (Input input : inputs) {
            copyToPartialFile(jobId, input, workspace);
        }

        for (Input input : inputs) {
            promotePartialFile(input, workspace);
        }
    }

    private void copyToPartialFile(UUID jobId, Input input, Path workspace) throws InputMaterializationException {
        Resource resource;
        long declaredLength;

        try {
            resource = storageService.readInput(jobId, input);
            declaredLength = resource.contentLength();
        } catch (IOException | RuntimeException e) {
            throw new InputMaterializationException(input.fileName(), "storage lookup failed", e);
        }

        Path partialPath = partialPath(workspace, input);
        long copied;

        try (InputStream source = resource.getInputStream(); OutputStream target = Files.newOutputStream(partialPath)) {
            copied = source.transferTo(target);
        } catch (IOException | RuntimeException e) {
            throw new InputMaterializationException(input.fileName(), "input copy failed", e);
        }

        if (copied != declaredLength) {
            throw new InputMaterializationException(input.fileName(), "content length mismatch");
        }
    }

    private void promotePartialFile(Input input, Path workspace) throws InputMaterializationException {
        try {
            Files.move(partialPath(workspace, input), workspace.resolve(input.fileName()));
        } catch (IOException e) {
            throw new InputMaterializationException(input.fileName(), "workspace promotion failed", e);
        }
    }

    private Path partialPath(Path workspace, Input input) {
        return workspace.resolve(input.fileName() + ".partial");
    }
}
