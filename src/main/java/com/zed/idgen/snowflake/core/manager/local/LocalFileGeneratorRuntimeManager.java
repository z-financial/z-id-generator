package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.AbstractGeneratorRuntimeManager;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Single-machine runtime manager for local file registration.
 * <p>
 * The manager creates one running. Placeholder file under
 * ./uid-register/[baseDir] to make accidental multiprocess local usage
 * visible. It does not parse YAML directly; LocalFileRegistrationLoader owns
 * file layout and registration validation.
 */
public class LocalFileGeneratorRuntimeManager extends AbstractGeneratorRuntimeManager {
    private static final String RUNNING_PLACEHOLDER_FILE = "running.placeholder";

    private final Path placeholderPath;

    public LocalFileGeneratorRuntimeManager(String baseDir) {
        this(new LocalFileRegistrationLoader(baseDir));
    }

    LocalFileGeneratorRuntimeManager(Path localConfigRoot, String baseDir) {
        this(new LocalFileRegistrationLoader(localConfigRoot, baseDir));
    }

    LocalFileGeneratorRuntimeManager(LocalFileRegistrationLoader registrationLoader) {
        this(registrationLoader, registrationLoader == null ? null : registrationLoader.basePath());
    }

    LocalFileGeneratorRuntimeManager(RegistrationLoader registrationLoader, Path placeholderBasePath) {
        super(registrationLoader);
        if (placeholderBasePath == null) {
            throw new InvalidConfigException("local runtime place holder base path must not be null");
        }
        this.placeholderPath = placeholderBasePath.resolve(RUNNING_PLACEHOLDER_FILE);
        createRunningPlaceholder();
    }

    @Override
    protected void onShutdown() {
        deleteRunningPlaceholder();
    }

    private void createRunningPlaceholder() {
        try {
            Files.createFile(placeholderPath);
            Files.writeString(placeholderPath, runningPlaceholderContent(), StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new InvalidConfigException("local runtime placeholder already exists: " + placeholderPath
                    + ". Another local runtime may be running. If not, inspect and delete this file manually.", e);
        } catch (IOException e) {
            tryDeletePlaceholderAfterFailedCreate();
            throw new InvalidConfigException("failed to create local runtime placeholder: " + placeholderPath, e);
        }
    }

    private String runningPlaceholderContent() {
        return "createdAt: " + Instant.now() + System.lineSeparator()
                + "pid: " + ProcessHandle.current().pid() + System.lineSeparator()
                + "user: " + System.getProperty("user.name", "unknown") + System.lineSeparator()
                + "host: " + currentHostName() + System.lineSeparator();
    }

    private String currentHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private void tryDeletePlaceholderAfterFailedCreate() {
        try {
            Files.deleteIfExists(placeholderPath);
        } catch (IOException ignored) {
            // The original create/write failure is more useful to the caller.
        }
    }

    private void deleteRunningPlaceholder() {
        try {
            Files.deleteIfExists(placeholderPath);
        } catch (IOException e) {
            throw new InvalidConfigException("failed to delete local runtime placeholder: " + placeholderPath, e);
        }
    }
}
