package io.f1r3fly.f1r3drive.filesystem.local;

import io.f1r3fly.f1r3drive.errors.OperationNotPermitted;
import io.f1r3fly.f1r3drive.blockchain.BlockchainContext;
import io.f1r3fly.f1r3drive.filesystem.bridge.FSPointer;
import io.f1r3fly.f1r3drive.filesystem.common.Directory;
import io.f1r3fly.f1r3drive.filesystem.common.File;
import io.f1r3fly.f1r3drive.filesystem.deployable.UnlockedWalletDirectory;

public class TokenFile extends AbstractLocalPath implements File {

    final long value;

    public TokenFile(BlockchainContext blockchainContext, String name, TokenDirectory parent, long value) {
        super(blockchainContext, name, parent);
        this.value = value;
        this.lastUpdated = 0L;
    }

    @Override
    public int read(FSPointer buffer, long size, long offset)  { return 0; }
    @Override
    public int write(FSPointer buffer, long bufSize, long writeOffset) { return 0;}
    @Override
    public void truncate(long offset) {}
    @Override
    public void open() {}
    @Override
    public void close() {}

    @Override
    public void rename(String newName, Directory newParent) throws OperationNotPermitted {
        if (!this.name.equals(newName)) {
            throw OperationNotPermitted.instance;
        }

        // allow to move TokenFile inside Wallet Directory only
        if (!(newParent instanceof LockedWalletDirectory) && !(newParent instanceof UnlockedWalletDirectory)) {
            throw OperationNotPermitted.instance;
        }

        this.parent = newParent;
    }

    @Override
    public TokenDirectory getParent() {
        return (TokenDirectory) super.getParent();
    }

    public long getValue() {
        return value;
    }
    
}
