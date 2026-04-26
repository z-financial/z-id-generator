package com.zed.idgen.snowflake.core.util;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class PathUtil {
    private PathUtil() {
    }

    public static Path getReadableDir(String dirPath) {
        if (StringUtil.isEmpty(dirPath)) {
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

    /**
     * Validates a user-provided path value that must be exactly one safe path segment.
     * It rejects absolute paths, path traversal, path separators,
     * leading/trailing spaces, and common reserved filename chars.
     */
    public static Path requireSafeSinglePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path segment must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException("path segment must not have leading or trailing spaces: " + value);
        }
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("path segment must not be . or ..");
        }
        if (containsPathSeparator(value) || containsReservedFileNameCharacter(value)) {
            throw new IllegalArgumentException("path segment contains unsafe filename characters: " + value);
        }

        Path requestedPath;
        try {
            requestedPath = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path segment: " + value, e);
        }
        if (requestedPath.isAbsolute() || requestedPath.getNameCount() != 1) {
            throw new IllegalArgumentException("path segment must be a safe single directory or file name: " + value);
        }
        return requestedPath;
    }

    /**
     * Resolves a safe child segment under the given root and rejects any result
     * that would escape the root after normalization. The child argument should
     * come from requireSafeSinglePathSegment or an equivalent validation path.
     */
    public static Path resolveSafeChildSegment(Path root, Path childSegment) {
        if (root == null) {
            throw new IllegalArgumentException("root path must not be null");
        }
        if (childSegment == null) {
            throw new IllegalArgumentException("child path segment must not be null");
        }
        if (childSegment.isAbsolute() || childSegment.getNameCount() != 1) {
            throw new IllegalArgumentException("child path must be a safe single path segment: " + childSegment);
        }

        Path rootPath = root.toAbsolutePath().normalize();
        Path resolvedPath = rootPath.resolve(childSegment).normalize();
        if (!resolvedPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("child path escapes root path: " + childSegment);
        }
        return resolvedPath;
    }

    private static boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    private static boolean containsReservedFileNameCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\0' || ch == '<' || ch == '>' || ch == ':' || ch == '"' || ch == '|' || ch == '?' || ch == '*') {
                return true;
            }
        }
        return false;
    }
}
