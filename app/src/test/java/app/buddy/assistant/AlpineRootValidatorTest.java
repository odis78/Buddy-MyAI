package app.buddy.assistant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AlpineRootValidatorTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void acceptsAlpineRootWithGuestAbsoluteSymlinks() throws Exception {
        Path root = validRoot();
        Files.createSymbolicLink(root.resolve("bin/sh"), Path.of("/bin/busybox"));
        Files.createSymbolicLink(root.resolve("usr/bin/env"), Path.of("/bin/busybox"));

        assertTrue(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void acceptsRelativeSymlinkTargets() throws Exception {
        Path root = validRoot();
        Files.createSymbolicLink(root.resolve("bin/sh"), Path.of("busybox"));

        assertTrue(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void rejectsMissingSymlinkTarget() throws Exception {
        Path root = validRoot();
        Files.createSymbolicLink(root.resolve("bin/sh"), Path.of("/bin/not-installed"));

        assertFalse(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void rejectsSymlinkTraversalOutsideRoot() throws Exception {
        Path root = validRoot();
        Files.createSymbolicLink(root.resolve("bin/sh"), Path.of("../../../../outside"));

        assertFalse(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void rejectsSymlinkLoops() throws Exception {
        Path root = validRoot();
        Files.createSymbolicLink(root.resolve("bin/sh"), Path.of("sh"));

        assertFalse(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void rejectsDirectoryWhereExecutableFileIsRequired() throws Exception {
        Path root = validRoot();
        Files.delete(root.resolve("bin/sh"));
        Files.createDirectory(root.resolve("bin/sh"));

        assertFalse(AlpineRootValidator.isValid(root.toFile()));
    }

    @Test public void rejectsMissingRequiredDirectories() throws Exception {
        Path root = validRoot();
        Files.delete(root.resolve("tmp"));

        assertFalse(AlpineRootValidator.isValid(root.toFile()));
    }

    private Path validRoot() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        Files.createDirectories(root.resolve("etc"));
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("usr/bin"));
        Files.createDirectories(root.resolve("root"));
        Files.createDirectories(root.resolve("tmp"));
        Files.write(root.resolve("etc/alpine-release"), "3.24.2".getBytes("UTF-8"));
        Files.write(root.resolve("bin/busybox"), new byte[]{1, 2, 3});
        Files.write(root.resolve("bin/sh"), new byte[]{1, 2, 3});
        Files.write(root.resolve("usr/bin/env"), new byte[]{1, 2, 3});
        return root;
    }
}
