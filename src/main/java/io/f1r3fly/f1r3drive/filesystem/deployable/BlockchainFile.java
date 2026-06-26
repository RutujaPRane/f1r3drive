package io.f1r3fly.f1r3drive.filesystem.deployable;

import io.f1r3fly.f1r3drive.encryption.AESCipher;
import io.f1r3fly.f1r3drive.errors.OperationNotPermitted;
import io.f1r3fly.f1r3drive.blockchain.BlockchainContext;
import io.f1r3fly.f1r3drive.filesystem.bridge.FSPointer;
import io.f1r3fly.f1r3drive.filesystem.common.Directory;
import io.f1r3fly.f1r3drive.filesystem.common.File;
import io.f1r3fly.f1r3drive.blockchain.rholang.RholangExpressionConstructor;
import io.f1r3fly.f1r3drive.filesystem.utils.PathUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockchainFile extends AbstractDeployablePath implements File {

    private final Logger log = org.slf4j.LoggerFactory.getLogger(BlockchainFile.class);

    // it should be a number that can be divisible by 16 because of AES block size
    private static final int MAX_FILE_CHUNK_SIZE = 16 * 10 * 1024 * 1024; // 160 mb

    protected RandomAccessFile rif;
    protected java.io.File cachedFile;
    protected long lastDeploymentOffset = 0;
    protected boolean isDirty = true;
    // cached file size; avoid IO operations at getattr
    protected long size = -1;

    // Illegal Filename Characters.
    // In theory, it can't be used in filename, so it's safe to use it as a delimiter
    private static String delimiter = "/";

    protected boolean isOtherChunksDeployed = false;
    protected Map<Integer, String> otherChunks = new ConcurrentHashMap<>();

    public BlockchainFile(@NotNull BlockchainContext blockchainContext, @NotNull String name, @NotNull Directory parent) {
        this(blockchainContext, name, parent, true);
    }

    protected BlockchainFile(@NotNull BlockchainContext blockchainContext, @NotNull String name, @NotNull Directory parent, boolean sendToShard) {
        super(blockchainContext, name, parent);
        if (sendToShard) {
            enqueueCreatingFile();
        }
        try {
            cachedFile = java.io.File.createTempFile(name, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void enqueueCreatingFile() {
        refreshLastUpdated();
        String rholang = RholangExpressionConstructor.sendEmptyFileIntoNewChanel(getAbsolutePath(), getLastUpdated());
        enqueueMutation(rholang);
    }


    public int read(FSPointer buffer, long size, long offset) throws IOException {
        open(); // make sure file is open

        int bytesToRead = (int) Math.min(this.size - offset, size);
        byte[] chunk = new byte[bytesToRead];

        synchronized (this) {
            rif.seek(offset);
            rif.read(chunk);
        }

        buffer.put(0, chunk, 0, bytesToRead);
        return bytesToRead;
    }

    public synchronized void truncate(long offset) throws IOException {
        if (offset != 0) {
            throw new RuntimeException("Unsupported");
        }

        if (size >= 0) {
            size = 0; // size changed, reset it
        }

        close();

        isDirty = true;

        lastDeploymentOffset = 0;

        cachedFile.delete();
        cachedFile = Files.createTempFile(name, null).toFile();

        refreshLastUpdated();
        enqueueMutation(RholangExpressionConstructor.forgetChanel(getAbsolutePath()));
        otherChunks.forEach((chunkNumber, subChannel) -> {
            enqueueMutation(RholangExpressionConstructor.forgetChanel(subChannel));
        });

        otherChunks = new ConcurrentHashMap<>();
        isOtherChunksDeployed = false;

        enqueueCreatingFile();

        open();
    }

    public int write(FSPointer buffer, long bufSize, long writeOffset) throws IOException {
        log.trace("Writing to file {} at offset {}", cachedFile.getAbsolutePath(), writeOffset);

        open(); // make sure file is open

        if (size >= 0) {
            size = -1; // size changed, reset it
        }

        isDirty = true;
        refreshLastUpdated();

        byte[] bytesToWrite = new byte[(int) bufSize];
        buffer.get(0, bytesToWrite, 0, (int) bufSize);
        synchronized (this) {
            rif.seek(writeOffset);
            rif.write(bytesToWrite, 0, (int) bufSize);
        }

        int writeEnd = (int) (writeOffset + bufSize);
        int notDeployedChunkSize = (int) (writeEnd - lastDeploymentOffset);
        if (notDeployedChunkSize >= MAX_FILE_CHUNK_SIZE) {
            deployChunk();
        }

        return (int) bufSize;
    }

    private void deployChunk() throws IOException {
        open(); // make sure file is open

        int size = (int) Math.min(getSize() - lastDeploymentOffset, MAX_FILE_CHUNK_SIZE);

        byte[] bytes = new byte[size];
        synchronized (this) {
            rif.seek(lastDeploymentOffset);
            rif.read(bytes, 0, size);
        }

        if (PathUtils.isEncryptedExtension(name)) {
            bytes = AESCipher.getInstance().encrypt(bytes);
        }

        int chunkNumber = (int) (lastDeploymentOffset / MAX_FILE_CHUNK_SIZE);
        String rholang;
        if (chunkNumber == 0) {
            rholang = RholangExpressionConstructor.updateFileContent(getAbsolutePath(), bytes);
        } else {
            String subChannel = getAbsolutePath() + delimiter + chunkNumber;
            rholang = RholangExpressionConstructor.sendFileContentChunk(subChannel, bytes);
            otherChunks.put(chunkNumber, subChannel);
            isOtherChunksDeployed = false;
        }
        refreshLastUpdated();
        enqueueMutation(rholang);

        lastDeploymentOffset = lastDeploymentOffset + size;
    }

    public void open() {
        try {
            if (rif == null) {
                rif = createRIF();
            }
        } catch (FileNotFoundException e) {
            // TODO: if file not found, re-pull it from Node?
            try {
                cachedFile = java.io.File.createTempFile(name, null);
                rif = createRIF();
            } catch (IOException e1) {
                log.warn("Failed to create file {} while creating RIT", cachedFile.getAbsolutePath(), e1);
            }
        }
    }

    private @NotNull RandomAccessFile createRIF() throws FileNotFoundException {
        return new RandomAccessFile(cachedFile, "rw");
    }

    public void close() {
        try {
            // append a last part of the file if any
            if (lastDeploymentOffset < getSize()) {
                deployChunk();
            }

            if (!isOtherChunksDeployed) {
                if (!otherChunks.isEmpty()) {
                    refreshLastUpdated();
                    enqueueMutation(RholangExpressionConstructor.updateOtherChunksMap(getAbsolutePath(), otherChunks));
                }
                isOtherChunksDeployed = true;
            }

            if (rif != null) {
                rif.close();
                rif = null;
            }
        } catch (IOException e) {
            // Ignore
            e.printStackTrace();
        }
    }

    public long getSize() {
        if (size < 0) {
            size = cachedFile.length();
        }
        return size;
    }

    public int initFromBytes(byte[] bytes, long offset) throws IOException {
        open(); // make sure file is open

        if (PathUtils.isEncryptedExtension(name)) {
            bytes = AESCipher.getInstance().decrypt(bytes);
        }

        synchronized (this) {
            rif.seek(offset);
            rif.write(bytes);
        }

        lastDeploymentOffset = offset + bytes.length;

        return bytes.length;
    }

    public void initSubChannels(Map<Integer, String> subChannels) {
        this.otherChunks = subChannels;
        this.isOtherChunksDeployed = true;
    }

    public void onChange() {
        if (isDirty) {
            if (isDeployable()) {
                try {
                    refreshLastUpdated();
                    String rholangExpression = Files.readString(cachedFile.toPath());
                    enqueueMutation(rholangExpression); // deploy a file as rho expression
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            isDirty = false;
        }
    }

    @Override
    public void rename(String newName, Directory newParent) throws OperationNotPermitted {

        isDirty = true;
        refreshLastUpdated();

        boolean wasEncrypted = PathUtils.isEncryptedExtension(name);
        boolean willBeEncrypted = PathUtils.isEncryptedExtension(newName);

        // wasn't encrypted and now will be encrypted
        boolean needEncrypt = !wasEncrypted && willBeEncrypted;

        // was encrypted and now won't be encrypted
        boolean needDecrypt = wasEncrypted && !willBeEncrypted;

        if (needEncrypt || needDecrypt) {
            enqueueMutation(RholangExpressionConstructor.forgetChanel(getAbsolutePath())); // delete old

            this.name = newName;
            this.parent = newParent;
            // skip event, chanel wil be re-created the below

            redeployFileIntoChanel();
        } else {
            super.rename(newName, newParent); // just rename the rholang chanel
        }

    }

    private void redeployFileIntoChanel() {
        refreshLastUpdated();
        enqueueCreatingFile(); // create new

        open(); // make sure file is open
        // append
        lastDeploymentOffset = 0;
        while (lastDeploymentOffset < getSize()) {
            try {
                deployChunk();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        isDirty = false;
        close(); // close file
    }

    private boolean isDeployable() {
        return PathUtils.isDeployableFile(name);
    }


    @Override
    public void cleanLocalCache() {
        try {
            this.cachedFile.delete();
        } catch (Throwable t) {
            log.warn("Failed to clean local cache for file {}", cachedFile.getAbsolutePath(), t);
        }
    }

    @Override
    public synchronized void delete() {
        cachedFile.delete();
        super.delete();
    }
}
