package app.verirun.exception;

import java.io.IOException;

public class InputMaterializationException extends IOException {

    public InputMaterializationException(String logicalName, String category) {
        super("Failed to materialize " + logicalName + ": " + category);
    }

    public InputMaterializationException(String logicalName, String category, Throwable cause) {
        super("Failed to materialize " + logicalName + ": " + category, cause);
    }
}
