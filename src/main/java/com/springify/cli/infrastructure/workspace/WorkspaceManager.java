package com.springify.cli.infrastructure.workspace;

import com.springify.cli.domain.GeneratedFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Materializes generated files on disk, isolating all I/O from the rest of the application.
 */
public final class WorkspaceManager {

    /**
     * Physically creates, under {@code baseDirectory}, the folders and files described in {@code files}.
     *
     * @param baseDirectory working directory the files will be written into
     * @param files         files to create, with paths relative to {@code baseDirectory}
     * @throws IOException if any directory or file cannot be created/written
     */
    public void write(Path baseDirectory, List<GeneratedFile> files) throws IOException {
        for (GeneratedFile file : files) {
            Path targetPath = resolveSafely(baseDirectory, file.path());

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(targetPath, file.content());
        }
    }

    /**
     * Resolves the relative path, guaranteeing it cannot escape {@code baseDirectory}
     * (path traversal protection, e.g. "../../etc/passwd").
     */
    private Path resolveSafely(Path baseDirectory, String relativePath) throws IOException {
        Path base = baseDirectory.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();

        if (!resolved.startsWith(base)) {
            throw new IOException("Invalid file path returned by the LLM: " + relativePath);
        }

        return resolved;
    }
}
