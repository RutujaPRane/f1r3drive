package io.f1r3fly.f1r3drive.app.linux.fuse;

import io.f1r3fly.f1r3drive.filesystem.FileSystemAction;
import io.f1r3fly.f1r3drive.errors.*;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.Statvfs;
import io.f1r3fly.f1r3drive.finderextensions.FinderSyncExtensionServiceServer;
import io.f1r3fly.f1r3drive.filesystem.FileSystem;
import io.f1r3fly.f1r3drive.blockchain.client.F1r3flyBlockchainClient;
import io.f1r3fly.f1r3drive.filesystem.InMemoryFileSystem;
import io.f1r3fly.f1r3drive.filesystem.OperationContext;
import io.f1r3fly.f1r3drive.filesystem.utils.PathUtils;
import jnr.ffi.Pointer;
import jnr.ffi.types.mode_t;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import jnr.posix.util.Platform;

public class F1r3DriveFuse extends FuseStubFS {

    private static final Logger LOGGER = LoggerFactory.getLogger(F1r3DriveFuse.class);

    private FileSystem fileSystem;
    private F1r3flyBlockchainClient f1R3FlyBlockchainClient;
    private FinderSyncExtensionServiceServer finderSyncExtensionServiceServer;

    public F1r3DriveFuse(F1r3flyBlockchainClient f1R3FlyBlockchainClient) {
        super(); // no need to call Fuse constructor?
        this.f1R3FlyBlockchainClient = f1R3FlyBlockchainClient; // doesnt have a state, so can be reused between mounts
    }

    /**
     * Extract the F1r3fly icon from JAR resources to a temporary file for use as volume icon
     * @return Path to the extracted icon file, or null if extraction failed
     */
    private String extractIconFromJar() {
        try {
            // Create a temporary file to store the icon
            Path tempIconPath = Files.createTempFile("f1r3fly-icon", ".icns");
            
            // Copy the icon from the JAR to the temporary file
            try (InputStream is = getClass().getResourceAsStream("/icons/f1r3fly.icns")) {
                if (is == null) {
                    LOGGER.warn("F1r3fly icon file not found in JAR resources at /icons/f1r3fly.icns");
                    return null;
                }
                Files.copy(is, tempIconPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.debug("Successfully extracted icon to: {}", tempIconPath);
            }
            
            // Mark for deletion on exit
            tempIconPath.toFile().deleteOnExit();
            
            return tempIconPath.toString();
        } catch (IOException e) {
            LOGGER.error("Failed to extract F1r3fly icon from JAR", e);
            return null;
        }
    }

    /**
     * Common error handling for FUSE operations
     */
    @FunctionalInterface
    private interface FuseOperation {
        int execute() throws Exception;
    }

    private int executeWithErrorHandling(String path, FileSystemAction action, FuseOperation operation) {
        return OperationContext.withContext(action, path, () -> {
            boolean isTrace = action == FileSystemAction.FUSE_GETATTR || action == FileSystemAction.FUSE_READ
                || action == FileSystemAction.FUSE_WRITE;
            if (isTrace) {
                LOGGER.trace("Started {}", action);
            } else {
                LOGGER.debug("Started {}", action);
            }

            // Check if filesystem is mounted first
            if (notMounted()) {
                LOGGER.debug("FileSystem not mounted");
                return -ErrorCodes.EIO();
            }

            try {
                int result = operation.execute();
                if (result == 0) {
                    if (isTrace) {
                        LOGGER.trace("Completed {} successfully", action);
                    } else {
                        LOGGER.debug("Completed {} successfully", action);
                    }
                } else {
                    LOGGER.debug("Completed {} with result: {}", action, result);
                }
                return result;
            } catch (FileAlreadyExists e) {
                if (isTrace) {
                    LOGGER.trace("File/Directory already exists", e);
                } else {
                    LOGGER.debug("File/Directory already exists", e);
                }
                return -ErrorCodes.EEXIST();
            } catch (PathNotFound e) {
                if (isTrace) {
                    LOGGER.trace("Path not found", e);
                } else {
                    LOGGER.debug("Path not found", e);
                }
                return -ErrorCodes.ENOENT();
            } catch (OperationNotPermitted e) {
                if (isTrace) {
                    LOGGER.trace("Operation not permitted");
                } else {
                    LOGGER.debug("Operation not permitted");
                }
                return -ErrorCodes.EPERM();
            } catch (PathIsNotAFile e) {
                if (isTrace) {
                    LOGGER.trace("Path is not a file", e);
                } else {
                    LOGGER.info("Path is not a file", e);
                }
                return -ErrorCodes.EISDIR();
            } catch (PathIsNotADirectory e) {
                if (isTrace) {
                    LOGGER.trace("Path is not a directory", e);
                } else {
                    LOGGER.info("Path is not a directory", e);
                }
                return -ErrorCodes.ENOTDIR();
            } catch (DirectoryNotEmpty e) {
                if (isTrace) {
                    LOGGER.trace("Directory is not empty");
                } else {
                    LOGGER.info("Directory is not empty");
                }
                return -ErrorCodes.ENOTEMPTY();
            } catch (IOException e) {
                if (isTrace) {
                    LOGGER.trace("IO error", e);
                } else {
                    LOGGER.warn("IO error", e);
                }
                return -ErrorCodes.EIO();
            } catch (Exception e) {
                if (isTrace) {
                    LOGGER.trace("Unexpected error", e);
                } else {
                    LOGGER.error("Unexpected error", e);
                }
                return -ErrorCodes.EIO();
            }
        });
    }

    @Override
    public int create(String path, @mode_t long mode, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_CREATE, () -> {
            // Reject creation of Apple metadata files
            if (PathUtils.isAppleMetadataFile(path)) {
                LOGGER.debug("Rejecting creation of Apple metadata file: {}", path);
                return -ErrorCodes.EACCES();
            }
            fileSystem.createFile(path, mode);
            return 0;
        });
    }

    @Override
    public int getattr(String path, FileStat stat) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_GETATTR, () -> {
            // Explicitly reject Apple metadata files
            if (PathUtils.isAppleMetadataFile(path)) {
                LOGGER.debug("Rejecting Apple metadata file: {}", path);
                return -ErrorCodes.ENOENT();
            }
            fileSystem.getAttributes(path, FuseAdapter.fromFuseFileStat(stat), FuseAdapter.fromFuseContext(getContext()));
            return 0;
        });
    }

    @Override
    public int mkdir(String path, @mode_t long mode) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_MKDIR, () -> {
            fileSystem.makeDirectory(path, mode);
            return 0;
        });
    }

    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_READ, () -> {
            return fileSystem.readFile(path, FuseAdapter.fromFusePointer(buf), size, offset);
        });
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_READDIR, () -> {
            fileSystem.readDirectory(path, FuseAdapter.fromFuseFillDir(buf, filter));
            return 0;
        });
    }

    @Override
    public int statfs(String path, Statvfs stbuf) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_READ, () -> {
            fileSystem.getFileSystemStats(path, FuseAdapter.fromFuseStatVfs(stbuf));
            return super.statfs(path, stbuf);
        });
    }

    @Override
    public int rename(String path, String newName) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_RENAME, () -> {
            fileSystem.renameFile(path, newName);
            return 0;
        });
    }

    @Override
    public int rmdir(String path) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_RMDIR, () -> {
            fileSystem.removeDirectory(path);
            return 0;
        });
    }

    @Override
    public int truncate(String path, long offset) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_TRUNCATE, () -> {
            fileSystem.truncateFile(path, offset);
            return 0;
        });
    }

    @Override
    public int unlink(String path) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_UNLINK, () -> {
            fileSystem.unlinkFile(path);
            return 0;
        });
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_OPEN, () -> {
            // Reject opening Apple metadata files
            if (PathUtils.isAppleMetadataFile(path)) {
                LOGGER.debug("Rejecting open of Apple metadata file: {}", path);
                return -ErrorCodes.ENOENT();
            }
            fileSystem.openFile(path);
            LOGGER.debug("Opened file {}", path);
            return 0;
        });
    }

    @Override
    public int write(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_WRITE, () -> {
            return fileSystem.writeFile(path, FuseAdapter.fromFusePointer(buf), size, offset);
        });
    }

    @Override
    public int flush(String path, FuseFileInfo fi) {
        return executeWithErrorHandling(path, FileSystemAction.FUSE_FLUSH, () -> {
            fileSystem.flushFile(path);
            return 0;
        });
    }

    public void mountAndUnlockRootDirectory(Path mountPoint, boolean blocking, boolean debug, String revAddress, String privateKey, String[] mountOptions) {
        // Run unlock in background after waiting for mount to complete
        Thread unlockThread = new Thread(() -> {
            try {
                // Wait for filesystem to be mounted
                LOGGER.debug("Background thread waiting for filesystem to be mounted...");
                while (notMounted()) {
                    Thread.sleep(100); // Check every 100ms
                }
                LOGGER.debug("Filesystem is now mounted, proceeding with unlock operation");
                
                // Now unlock the directory
                fileSystem.unlockRootDirectory(revAddress, privateKey);
            } catch (InterruptedException e) {
                LOGGER.warn("Unlock background thread was interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("Error in background unlock thread for revAddress: {}", revAddress, e);
            }

        });
        
        unlockThread.setName("UnlockDirectory-" + revAddress);
        unlockThread.setDaemon(true); // Don't prevent JVM shutdown
        unlockThread.start();
        
        LOGGER.debug("Started background unlock thread for revAddress: {}", revAddress);
        mount(mountPoint, blocking, debug, mountOptions);
    }

    public void mount(Path mountPoint, boolean blocking, boolean debug, String[] mountOptions) {
        LOGGER.debug("Called Mounting F1r3DriveFuse on {} with opts {}", mountPoint, Arrays.toString(mountOptions));

        try {
            // Ensure mount point exists and is accessible
            File mountPointFile = mountPoint.toFile();
            if (!mountPointFile.exists()) {
                LOGGER.debug("Mount point does not exist, creating: {}", mountPoint);
                if (!mountPointFile.mkdirs()) {
                    throw new RuntimeException("Failed to create mount point directory: " + mountPoint);
                }
            }
            if (!mountPointFile.isDirectory()) {
                throw new RuntimeException("Mount point is not a directory: " + mountPoint);
            }
            if (!mountPointFile.canRead() || !mountPointFile.canWrite()) {
                throw new RuntimeException("Mount point is not accessible (read/write): " + mountPoint);
            }
            LOGGER.debug("Mount point verified: {}", mountPoint);
            
            LOGGER.debug("Creating InMemoryFileSystem...");
            this.fileSystem = new InMemoryFileSystem(f1R3FlyBlockchainClient);
            LOGGER.debug("Created InMemoryFileSystem successfully");

            LOGGER.debug("Creating FinderSyncExtensionServiceServer...");
            this.finderSyncExtensionServiceServer = new FinderSyncExtensionServiceServer(
                this::handleChange, this::handleUnlockRevDirectory, 54000);
            LOGGER.debug("Created FinderSyncExtensionServiceServer successfully");

            LOGGER.debug("Waiting for background operations to complete...");
            waitOnBackgroundThread();

            LOGGER.debug("Starting FinderSyncExtensionServiceServer...");
            finderSyncExtensionServiceServer.start();
            LOGGER.debug("Started FinderSyncExtensionServiceServer successfully");

            LOGGER.debug("Mounting FUSE filesystem with options: {}", Arrays.toString(mountOptions));
            super.mount(mountPoint, blocking, debug, mountOptions);

            LOGGER.info("Successfully mounted F1r3DriveFuse on {} with name {}", mountPoint, mountPoint.getFileName().toString());

        } catch (RuntimeException e) {
            LOGGER.error("Runtime error during mount: {}", e.getMessage(), e);
            cleanupResources();
            throw e;
        } catch (Throwable e) {
            LOGGER.error("Error mounting F1r3DriveFuse on {}: {}", mountPoint, e.getMessage(), e);
            cleanupResources();
            throw new RuntimeException("Failed to mount F1r3DriveFuse", e);
        }
    }

    private void cleanupResources() {
        LOGGER.debug("Starting cleanup of resources...");
        // destroy background tasks and queue
        if (this.fileSystem != null) {
            LOGGER.debug("Terminating filesystem...");
            this.fileSystem.terminate();
            this.fileSystem = null;
            LOGGER.debug("Filesystem terminated and set to null");
        } else {
            LOGGER.debug("Filesystem was already null, skipping termination");
        }
        if (this.finderSyncExtensionServiceServer != null) {
            LOGGER.debug("Stopping FinderSyncExtensionServiceServer...");
            this.finderSyncExtensionServiceServer.stop();
            LOGGER.debug("FinderSyncExtensionServiceServer stopped");
        } else {
            LOGGER.debug("FinderSyncExtensionServiceServer was already null, skipping stop");
        }
        LOGGER.debug("Resource cleanup completed");
    }

    public void waitOnBackgroundThread() {
        try {
            if (fileSystem != null) {
                LOGGER.debug("Waiting for background deploy operations to complete...");
                fileSystem.waitOnBackgroundDeploy();
                LOGGER.debug("Background deploy operations completed successfully");
            } else {
                LOGGER.warn("waitOnBackgroundThread called but fileSystem is null");
            }
        } catch (Throwable e) {
            LOGGER.error("Error waiting for background thread operations to complete", e);
        }
    }

    @Override
    public void umount() {
        // Use compareAndSet to ensure only one unmount attempt (consistent with base class)
        if (!mounted.compareAndSet(true, false)) {
            LOGGER.debug("F1r3DriveFuse is already unmounted or being unmounted, skipping");
            return;
        }
        
        // Reset mounted flag back to true so that super.umount() can handle it properly
        mounted.set(true);
        
        LOGGER.debug("Called Umounting F1r3DriveFuse. Mounted: {}, filesystem {}", mounted.get(), fileSystem != null);
        try {
            LOGGER.debug("Waiting for background operations to complete before unmount...");
            waitOnBackgroundThread();
            LOGGER.debug("Background operations completed, calling super.umount()...");
            super.umount();
            LOGGER.debug("super.umount() completed, starting cleanup...");
            cleanupResources();
            LOGGER.info("Successfully unmounted F1r3DriveFuse");
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error during unmount: {}", e.getMessage(), e);
            // Still cleanup on error
            try {
                cleanupResources();
            } catch (Exception cleanupError) {
                LOGGER.error("Error during cleanup after unmount failure", cleanupError);
            }
            throw e;
        } catch (Throwable e) {
            LOGGER.error("Error unmounting F1r3DriveFuse: {}", e.getMessage(), e);
            // Still cleanup on error
            try {
                cleanupResources();
            } catch (Exception cleanupError) {
                LOGGER.error("Error during cleanup after unmount failure", cleanupError);
            }
            throw new RuntimeException("Failed to unmount F1r3DriveFuse", e);
        }
    }

    protected boolean notMounted() {
        boolean mountedFlag = mounted.get();
        boolean fileSystemExists = fileSystem != null;
        boolean isNotMounted = !mountedFlag || !fileSystemExists;

        if (isNotMounted) {
            LOGGER.warn("Filesystem is not mounted. mounted.get()={}, fileSystem!=null={}", mountedFlag,
                fileSystemExists);
            // Add stack trace to help debug why filesystem becomes null
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("notMounted() called from:", new Exception("Stack trace"));
            }
        }

        return isNotMounted;
    }

    private FinderSyncExtensionServiceServer.Result handleChange(String tokenFilePath) {
        LOGGER.debug("Called onChange for path: {}", tokenFilePath);
        if (notMounted()) {
            LOGGER.warn("handleChange - FileSystem not mounted for path: {}", tokenFilePath);
            return FinderSyncExtensionServiceServer.Result.error("FileSystem not mounted");
        }

        try {
            String normalizedTokenFilePath = tokenFilePath.replace(mountPoint.toFile().getAbsolutePath(), "");
            fileSystem.changeTokenFile(normalizedTokenFilePath);
            LOGGER.debug("Successfully changed token file: {}", normalizedTokenFilePath);
            return FinderSyncExtensionServiceServer.Result.success();
        } catch (Exception e) {
            LOGGER.error("Error exchanging token file: {}", tokenFilePath, e);
            return FinderSyncExtensionServiceServer.Result.error(e.getMessage());
        }
    }

    private FinderSyncExtensionServiceServer.Result handleUnlockRevDirectory(String revAddress, String privateKey) {
        LOGGER.debug("Called handleUnlockRevDirectory for revAddress: {}", revAddress);

        if (notMounted()) {
            LOGGER.warn("handleUnlockRevDirectory - FileSystem not mounted for revAddress: {}", revAddress);
            return FinderSyncExtensionServiceServer.Result.error("FileSystem not mounted");
        }
        try {
            fileSystem.unlockRootDirectory(revAddress, privateKey);
            LOGGER.debug("Successfully unlocked directory for revAddress: {}", revAddress);
            return FinderSyncExtensionServiceServer.Result.success();
        } catch (Exception e) {
            LOGGER.error("Error unlocking directory for revAddress: {}", revAddress, e);
            return FinderSyncExtensionServiceServer.Result.error(e.getMessage());
        }
    }

}
