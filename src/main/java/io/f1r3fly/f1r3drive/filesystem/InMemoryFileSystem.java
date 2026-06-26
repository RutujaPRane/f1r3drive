package io.f1r3fly.f1r3drive.filesystem;

import casper.DeployServiceCommon;
import io.f1r3fly.f1r3drive.blockchain.wallet.RevWalletInfo;
import io.f1r3fly.f1r3drive.errors.*;
import io.f1r3fly.f1r3drive.blockchain.BlockchainContext;
import io.f1r3fly.f1r3drive.filesystem.bridge.*;
import io.f1r3fly.f1r3drive.background.state.StateChangeEvents;
import io.f1r3fly.f1r3drive.background.state.StateChangeEventsManager;
import io.f1r3fly.f1r3drive.background.state.StateChangeEventProcessor;
import io.f1r3fly.f1r3drive.blockchain.client.DeployDispatcher;
import io.f1r3fly.f1r3drive.blockchain.client.F1r3flyBlockchainClient;
import io.f1r3fly.f1r3drive.filesystem.common.Directory;
import io.f1r3fly.f1r3drive.filesystem.common.File;
import io.f1r3fly.f1r3drive.filesystem.common.Path;
import io.f1r3fly.f1r3drive.filesystem.deployable.BlockchainFile;
import io.f1r3fly.f1r3drive.filesystem.deployable.UnlockedWalletDirectory;
import io.f1r3fly.f1r3drive.filesystem.local.LockedWalletDirectory;
import io.f1r3fly.f1r3drive.filesystem.local.RootDirectory;
import io.f1r3fly.f1r3drive.filesystem.local.TokenDirectory;
import io.f1r3fly.f1r3drive.filesystem.local.TokenFile;
import io.f1r3fly.f1r3drive.filesystem.utils.PathUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InMemoryFileSystem implements FileSystem {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryFileSystem.class);

    @NotNull
    private final RootDirectory rootDirectory;
    @NotNull
    private final DeployDispatcher deployDispatcher;

    private final StateChangeEventsManager stateChangeEventsManager;

    public InMemoryFileSystem(F1r3flyBlockchainClient f1R3FlyBlockchainClient) throws F1r3DriveError {

        this.stateChangeEventsManager = new StateChangeEventsManager();
        this.stateChangeEventsManager.start();

        this.deployDispatcher = new DeployDispatcher(f1R3FlyBlockchainClient, stateChangeEventsManager);
        deployDispatcher.startBackgroundDeploy();

        this.rootDirectory = new RootDirectory();
        Set<Path> lockedRemoteDirectories = createRavAddressDirectories(this.deployDispatcher);
        for (Path LockedWalletDirectory : lockedRemoteDirectories) {
            try {
                rootDirectory.addChild(LockedWalletDirectory);
            } catch (OperationNotPermitted impossibleError) {
                logger.error("Unexpected error: {}", impossibleError.getMessage());
            }
        }

    }

    // Helper method to get path separator
    private String pathSeparator() {
        return PathUtils.getPathDelimiterBasedOnOS();
    }

    // Simplified path manipulation methods
    public String getLastComponent(String path) {
        // Remove trailing separators
        while (path.endsWith(pathSeparator()) && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }

        if (path.equals(pathSeparator())) {
            return pathSeparator();
        }

        int lastSeparatorIndex = path.lastIndexOf(pathSeparator());
        return lastSeparatorIndex == -1 ? path : path.substring(lastSeparatorIndex + 1);
    }

    public Directory getParentDirectory(String path) {
        String parentPath = getParentPath(path);
        if (parentPath == null) {
            return null;
        }

        try {
            Path parent = getPath(parentPath);
            if (!(parent instanceof Directory)) {
                throw new IllegalArgumentException("Parent path is not a directory: " + parentPath);
            }
            return (Directory) parent;
        } catch (PathNotFound e) {
            return null;
        }
    }

    private Directory getParentDirectoryInternal(String path) throws PathNotFound {
        String parentPath = getParentPath(path);
        if (parentPath == null) {
            throw new PathNotFound("No parent for root path: " + path);
        }

        Path parent = getPath(parentPath);
        if (!(parent instanceof Directory)) {
            throw new IllegalArgumentException("Parent path is not a directory: " + parentPath);
        }
        return (Directory) parent;
    }

    public Path findPath(String path) {
        return this.rootDirectory.find(path);
    }

    public Path getPath(String path) throws PathNotFound {
        Path element = findPath(path);

        if (element == null) {
            throw new PathNotFound(path);
        }

        return element;
    }

    public Directory getDirectoryByPath(String path) throws PathNotFound, PathIsNotADirectory {
        Path element = findPath(path);

        if (element == null) {
            throw new PathNotFound(path);
        }

        if (!(element instanceof Directory)) {
            throw new PathIsNotADirectory(path);
        }

        return (Directory) element;
    }

    public File getFileByPath(String path) throws PathNotFound, PathIsNotAFile {
        Path element = findPath(path);

        if (element == null) {
            throw new PathNotFound(path);
        }

        if (!(element instanceof File)) {
            throw new PathIsNotAFile(path);
        }

        return (File) element;
    }

    // Core file system operations
    public void createFile(String path, long mode)
            throws PathNotFound, FileAlreadyExists, OperationNotPermitted {
        Path maybeExist = findPath(path);

        if (maybeExist != null) {
            throw new FileAlreadyExists(path);
        }

        Directory parent = getParentDirectoryInternal(path);
        parent.mkfile(getLastComponent(path));
    }

    public void getAttributes(String path, FSFileStat stat, FSContext context) throws PathNotFound {
        Path p = findPath(path);
        if (p == null) {
            throw new PathNotFound(path);
        }
        p.getAttr(stat, context);
    }

    public void makeDirectory(String path, long mode)
            throws PathNotFound, FileAlreadyExists, OperationNotPermitted {
        Path maybeExist = findPath(path);
        if (maybeExist != null) {
            throw new FileAlreadyExists(path);
        }

        Directory parent = getParentDirectoryInternal(path);
        parent.mkdir(getLastComponent(path));
    }

    public int readFile(String path, FSPointer buf, long size, long offset)
            throws PathNotFound, PathIsNotAFile, IOException {
        File file = getFileByPath(path);
        return file.read(buf, size, offset);
    }

    public void readDirectory(String path, FSFillDir filter) throws PathNotFound, PathIsNotADirectory {
        Directory directory = getDirectoryByPath(path);
        directory.read(filter);
    }

    public void getFileSystemStats(String path, FSStatVfs stbuf) {
        if ("/".equals(path)) {
            int BLOCKSIZE = 4096;
            int FUSE_NAME_MAX = 255;

            long totalSpace = 100L * 1024 * 1024 * 1024;
            long UsableSpace = totalSpace;
            long tBlocks = totalSpace / BLOCKSIZE;
            long aBlocks = UsableSpace / BLOCKSIZE;
            stbuf.setBlockSize(BLOCKSIZE);
            stbuf.setFragmentSize(BLOCKSIZE);
            stbuf.setBlocks(tBlocks);
            stbuf.setBlocksAvailable(aBlocks);
            stbuf.setBlocksFree(aBlocks);
            stbuf.setMaxFilenameLength(FUSE_NAME_MAX);
        }
    }

    public void renameFile(String path, String newName) throws PathNotFound, OperationNotPermitted {
        Path p = getPath(path);
        Directory newParent = getParentDirectoryInternal(newName);

        Directory oldParent = p.getParent();
        String lastComponent = getLastComponent(newName);

        p.rename(lastComponent, newParent);

        if (oldParent != newParent) {
            newParent.addChild(p);
            oldParent.deleteChild(p);
        } else {
            newParent.addChild(p);
        }

        if (p instanceof BlockchainFile) {
            ((BlockchainFile) p).onChange();
        }
    }

    public void removeDirectory(String path)
            throws PathNotFound, PathIsNotADirectory, DirectoryNotEmpty, OperationNotPermitted {
        Directory directory = getDirectoryByPath(path);
        if (!directory.isEmpty()) {
            throw new DirectoryNotEmpty(path);
        }
        directory.delete();
        Directory parent = directory.getParent();
        if (parent != null) {
            parent.deleteChild(directory);
        }
    }

    public void truncateFile(String path, long offset) throws PathNotFound, PathIsNotAFile, IOException {
        File file = getFileByPath(path);
        file.truncate(offset);
    }

    public void unlinkFile(String path) throws PathNotFound, OperationNotPermitted {
        Path p = getPath(path);
        p.delete();
        Directory parent = p.getParent();
        if (parent != null) {
            parent.deleteChild(p);
        }
    }

    public void openFile(String path) throws PathNotFound, PathIsNotAFile, IOException {
        File file = getFileByPath(path);
        file.open();
    }

    public int writeFile(String path, FSPointer buf, long size, long offset)
            throws PathNotFound, PathIsNotAFile, IOException {
        File file = getFileByPath(path);
        return file.write(buf, size, offset);
    }

    public void flushFile(String path) throws PathNotFound, PathIsNotAFile {
        File file = getFileByPath(path);
        file.close();
    }

    // Utility methods
    @Override
    public File getFile(String path) {
        try {
            return getFileByPath(path);
        } catch (PathNotFound | PathIsNotAFile e) {
            return null;
        }
    }

    @Override
    public Directory getDirectory(String path) {
        try {
            return getDirectoryByPath(path);
        } catch (PathNotFound | PathIsNotADirectory e) {
            return null;
        }
    }

    @Override
    public boolean isRootPath(String path) {
        return pathSeparator().equals(path) || "".equals(path);
    }

    @Nullable
    @Override
    public String getParentPath(String path) {
        if (isRootPath(path)) {
            return null;
        }

        int lastSeparatorIndex = path.lastIndexOf(pathSeparator());
        if (lastSeparatorIndex <= 0) {
            return pathSeparator();
        }
        return path.substring(0, lastSeparatorIndex);
    }

    private List<String> parseRavAddressesFromGenesisBlock(F1r3flyBlockchainClient f1R3FlyBlockchainClient)
            throws F1r3DriveError {
        List<DeployServiceCommon.DeployInfo> deploys = f1R3FlyBlockchainClient.getGenesisBlock().getDeploysList();

        DeployServiceCommon.DeployInfo tokenInitializeDeploy = deploys.stream()
                .filter((deployInfo1 -> deployInfo1.getTerm().contains("systemVaultInitCh"))).findFirst().orElseThrow();

        String regex = "\\\"(1111[A-Za-z0-9]+)\\\"";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(tokenInitializeDeploy.getTerm());

        List<String> ravAddresses = new java.util.ArrayList<>();
        while (matcher.find()) {
            ravAddresses.add(matcher.group(1));
        }

        return ravAddresses;
    }

    private Set<Path> createRavAddressDirectories(DeployDispatcher deployDispatcher) throws F1r3DriveError {
        List<String> ravAddresses = parseRavAddressesFromGenesisBlock(deployDispatcher.getBlockchainClient());

        logger.debug("Addresses found in genesis block: {}", ravAddresses);

        Set<Path> children = new HashSet<>();

        for (String address : ravAddresses) {
            children.add(new LockedWalletDirectory(
                    new BlockchainContext(new RevWalletInfo(address, null), deployDispatcher), rootDirectory));
        }

        return children;
    }

    public void unlockRootDirectory(String revAddress, String privateKey) throws InvalidSigningKeyException {
        String searchPath = "/LOCKED-REMOTE-REV-" + revAddress;

        Path lockedRoot = getDirectory(searchPath);

        if (lockedRoot instanceof LockedWalletDirectory) {
            try {
                UnlockedWalletDirectory unlockedRoot = ((LockedWalletDirectory) lockedRoot).unlock(privateKey,
                        deployDispatcher);

                this.rootDirectory.deleteChild(lockedRoot);
                this.rootDirectory.addChild(unlockedRoot);

                TokenDirectory tokenDirectory = unlockedRoot.getTokenDirectory();
                if (tokenDirectory != null) {
                    stateChangeEventsManager.registerEventProcessor(StateChangeEvents.WalletBalanceChanged.class,
                            new StateChangeEventProcessor() {
                                @Override
                                public void processEvent(StateChangeEvents event) {
                                    if (event instanceof StateChangeEvents.WalletBalanceChanged walletBalanceChanged) {
                                        if (walletBalanceChanged.revAddress().equals(
                                                unlockedRoot.getBlockchainContext().getWalletInfo().revAddress())) {
                                            tokenDirectory.handleWalletBalanceChanged();
                                        }
                                    }
                                }
                            });
                } else {
                    logger.warn("Token directory is null for unlocked root directory: {}",
                            unlockedRoot.getBlockchainContext().getWalletInfo().revAddress());
                }

            } catch (OperationNotPermitted e) {
                logger.warn("Failed to unlock root directory: {}", revAddress, e);
            }

        } else {
            if (lockedRoot != null) {
                logger.warn("Root directory type: {}", lockedRoot.getClass());
                logger.warn("Root directory name: {}", lockedRoot.getName());
                logger.warn("Root directory parent: {}", lockedRoot.getParent());
            } else {
                logger.warn("Root directory is null - path not found: {}", searchPath);
                throw new PathNotFound(searchPath);
            }
        }
    }

    @Override
    public void changeTokenFile(String filePath) throws NoDataByPath {
        File file = getFile(filePath);
        if (file == null) {
            throw new NoDataByPath(filePath);
        }

        if (!(file instanceof TokenFile)) {
            throw new RuntimeException("File is not a token file: " + filePath);
        }

        TokenFile tokenFile = (TokenFile) file;
        Directory tokenDirectory = tokenFile.getParent();

        if (tokenDirectory == null) {
            throw new RuntimeException("Token directory is null: " + filePath);
        }

        if (!(tokenDirectory instanceof TokenDirectory)) {
            throw new RuntimeException("Token directory is not a token directory: " + filePath);
        }

        ((TokenDirectory) tokenDirectory).change(tokenFile);
    }

    @Override
    public void waitOnBackgroundDeploy() {
        deployDispatcher.waitOnEmptyQueue();
    }

    @Override
    public void terminate() {
        logger.info("Terminating filesystem");
        try {
            logger.debug("Waiting for background deployments to complete before termination...");
            waitOnBackgroundDeploy();
            logger.debug("Background deployments completed successfully");
        } catch (Throwable e) {
            logger.warn("Error waiting for background deployments during termination, continuing with cleanup", e);
        }

        try {
            logger.debug("Destroying deploy dispatcher...");
            this.deployDispatcher.destroy();
            logger.info("Destroyed deploy dispatcher");
        } catch (Throwable e) {
            logger.warn("Error destroying deploy dispatcher during termination", e);
        }

        try {
            logger.debug("Cleaning local cache...");
            this.rootDirectory.cleanLocalCache();
            logger.info("Cleaned local cache");
        } catch (Throwable e) {
            logger.warn("Error cleaning local cache during termination", e);
        }

        try {
            logger.debug("Shutting down state change events manager...");
            this.stateChangeEventsManager.shutdown();
            logger.info("Shut down state change events manager");
        } catch (Throwable e) {
            logger.warn("Error shutting down state change events manager during termination", e);
        }

        logger.info("Filesystem termination completed");
    }
}