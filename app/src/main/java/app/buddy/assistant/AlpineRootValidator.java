package app.buddy.assistant;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Validates required paths using Alpine's path namespace.
 *
 * Absolute symlinks in an extracted rootfs are guest-absolute, not Android-host-absolute.
 * Resolve every component beneath the supplied root and reject traversal outside it.
 */
final class AlpineRootValidator {
    private static final int MAX_SYMLINKS = 40;

    private AlpineRootValidator() {
    }

    static boolean isValid(File rootDirectory) {
        if (rootDirectory == null) return false;
        Path root = rootDirectory.toPath().toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(root)) return false;
            return isRegularFile(root, "/etc/alpine-release")
                    && isRegularFile(root, "/bin/sh")
                    && isRegularFile(root, "/usr/bin/env")
                    && isDirectory(root, "/root")
                    && isDirectory(root, "/tmp");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isRegularFile(Path root, String guestPath) throws IOException {
        Path resolved = resolve(root, guestPath);
        return resolved != null
                && Files.readAttributes(resolved, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isRegularFile();
    }

    private static boolean isDirectory(Path root, String guestPath) throws IOException {
        Path resolved = resolve(root, guestPath);
        return resolved != null
                && Files.readAttributes(resolved, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isDirectory();
    }

    /**
     * Resolve a guest-absolute path without ever following a symlink on the Android host.
     * Absolute link targets restart at root; relative targets restart at the link's parent.
     */
    private static Path resolve(Path root, String guestPath) throws IOException {
        if (guestPath == null || !guestPath.startsWith("/")) return null;

        Deque<String> pending = components(guestPath);
        Path current = root;
        int symlinks = 0;

        while (!pending.isEmpty()) {
            String component = pending.removeFirst();
            if (component.isEmpty() || ".".equals(component)) continue;
            if ("..".equals(component)) {
                if (current.equals(root)) return null;
                current = current.getParent();
                if (current == null || !current.startsWith(root)) return null;
                continue;
            }

            Path candidate = current.resolve(component).normalize();
            if (!candidate.startsWith(root)) return null;
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(candidate, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException missingOrUnreadable) {
                return null;
            }

            if (attributes.isSymbolicLink()) {
                if (++symlinks > MAX_SYMLINKS) return null;
                Path target = Files.readSymbolicLink(candidate);
                Deque<String> targetParts = components(target.toString());
                if (target.isAbsolute()) {
                    current = root;
                }
                targetParts.addAll(pending);
                pending = targetParts;
                continue;
            }

            if (!pending.isEmpty() && !attributes.isDirectory()) return null;
            current = candidate;
        }
        return current.startsWith(root) ? current : null;
    }

    private static Deque<String> components(String path) {
        Deque<String> result = new ArrayDeque<>();
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (!part.isEmpty()) result.addLast(part);
        }
        return result;
    }
}
