package com.zed.idgen.snowflake.core.util;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class PathUtil {
    public static Path getReadableDir(String dirPath) {
        if (dirPath == null || dirPath.isBlank()) {
            throw new IllegalArgumentException("dirPath must not be blank");
        }

        final Path path;
        try {
            path = Path.of(dirPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid directory path: " + dirPath, e);
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Directory does not exist: " + path);
        }

        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }

        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Directory is not readable: " + path);
        }

        return path;
    }

    public static Path getFilePath(Path basePath, String fileName) {
        Path requestedPath;
        try {
            requestedPath = Path.of(fileName);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid file name: " + fileName);
        }
        if (requestedPath.isAbsolute() || requestedPath.getNameCount() != 1) {
            throw new IllegalArgumentException("invalid filename, not a single filename: " + fileName);
        }

        return basePath.resolve(requestedPath).normalize();
    }
}