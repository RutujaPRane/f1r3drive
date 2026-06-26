# F1r3Drive — Feature Reference

This document tracks every feature supported by `f1r3drive`, grouped by implementation status. Each item links to the e2e test that validates it or notes why it is planned but not yet enabled.

---

## ✅ Implemented Features (Passing E2E Tests)

### File Operations (CRUD)

| Feature | Description | Test |
|---------|-------------|------|
| **Create file** | Create a new file inside an unlocked wallet directory | `shouldCreateRenameGetDeleteFiles` |
| **Write file** | Write binary (1 MB+) and string data to files | `shouldCreateRenameGetDeleteFiles` |
| **Read file** | Read file content back and verify against written data | `shouldCreateRenameGetDeleteFiles` |
| **Rename file** | Rename a file and verify both local and shard state update | `shouldCreateRenameGetDeleteFiles` |
| **Overwrite / truncate** | Truncate and overwrite existing file content | `shouldCreateRenameGetDeleteFiles` |
| **Delete file** | Remove a file and verify it disappears locally and on-chain | `shouldCreateRenameGetDeleteFiles` |
| **Nested files** | Create files inside subdirectories | `shouldCreateRenameGetDeleteFiles` |

### Directory Operations (CRUD)

| Feature | Description | Test |
|---------|-------------|------|
| **Create directory** | Create a new directory inside an unlocked wallet | `shouldCreateRenameListDeleteDirectoriesManualPropose` |
| **Rename directory** | Rename a directory and verify shard consistency | `shouldCreateRenameListDeleteDirectoriesManualPropose` |
| **Nested directories** | Create deeply nested directory trees (`mkdirs`) | `shouldCreateRenameListDeleteDirectoriesManualPropose` |
| **Delete directory** | Remove empty directories | `shouldCreateRenameListDeleteDirectoriesManualPropose` |
| **List directory** | List children of any directory (local + shard) | `shouldCreateRenameListDeleteDirectoriesManualPropose` |

### Directory Operations — Auto-Propose Mode (Heartbeat)

| Feature | Description | Test |
|---------|-------------|------|
| **Create / rename / delete with auto-propose** | Same CRUD operations as above, but without manual block proposals — relies on shard heartbeat to finalize | `shouldCreateRenameListDeleteDirectoriesAutoPropose` |
| **Polling shard state** | Waits up to 120 s for auto-proposer to process and verify operations on-chain | `shouldCreateRenameListDeleteDirectoriesAutoPropose` |

### Wallet & Access Control

| Feature | Description | Test |
|---------|-------------|------|
| **Unlock wallet directory** | Provide a valid REV address + private key to unlock a `LOCKED-REMOTE-REV-*` folder | `shouldHandleOperationsWithNotExistingFileAndDirectory` |
| **Reject invalid keys** | Invalid or mismatched private keys throw `WalletUnlockException` | `shouldHandleOperationsWithNotExistingFileAndDirectory` |
| **Locked folder isolation** | Locked wallet folders appear empty; contents are inaccessible | `shouldHandleOperationsWithNotExistingFileAndDirectory` |
| **Non-existent path errors** | Operations on non-existent files/directories fail gracefully | `shouldHandleOperationsWithNotExistingFileAndDirectory` |

### Token Management

| Feature | Description | Test |
|---------|-------------|------|
| **`.token` directory** | Appears inside every unlocked wallet directory; contains `*.token` files | `shouldSupportTokenFileOperations` |
| **Token file properties** | Each `.token` file follows the `<denomination>-REV.<id>.token` naming convention | `shouldSupportTokenFileOperations` |
| **Change token denomination** | Splitting a token produces 10 smaller-denomination token files | `shouldSupportTokenFileOperations` |
| **Transfer tokens** | Moving a `.token` file to another wallet folder triggers an on-chain REV transfer | `shouldSupportTokenFileOperations` |
| **Balance verification** | After transfer, sender balance decreases and receiver balance increases | `shouldSupportTokenFileOperations` |
| **Read-only `.token` dir** | Direct file creation and writes inside `.tokens` are blocked | `shouldSupportTokenFileOperations` |

### Timestamps & Persistence

| Feature | Description | Test |
|---------|-------------|------|
| **Last-modified tracking** | File and directory creation, modification, and rename all update `lastModified` | `shouldTrackLastModifiedDatesForFilesAndDirectories` |
| **Timestamp persistence** | Timestamps survive unmount → remount cycle | `shouldTrackLastModifiedDatesForFilesAndDirectories` |
| **Data persistence** | File content survives unmount → remount cycle | `shouldCreateRenameGetDeleteFiles` |

### Platform Integration (macOS)

| Feature | Description |
|---------|-------------|
| **Finder sidebar visibility** | Mounts with `-o volname=F1r3Drive -o local` so the drive appears under "Locations" in Finder |
| **No `.DS_Store` clutter** | `-o noappledouble` prevents macOS metadata files from being written to the blockchain |
| **Double Ctrl+C hard stop** | First `Ctrl+C` triggers graceful unmount; second forces `Runtime.halt(130)` for instant JVM kill |

---

## 🔌 Extension Features (Requires [F1R3Drive Finder Extension](https://github.com/F1R3FLY-io/f1r3drive-extension))

These features require installing the optional macOS Finder Extension. They communicate with `f1r3drive` over a local gRPC server on `localhost:54000`.

| Feature | Description |
|---------|-------------|
| **Context menu → Change token** | Right-click a `.token` file in Finder and select "Change" to trigger denomination splitting via gRPC `SubmitAction` |
| **Auto-unlock via Finder navigation** | Navigating into a `LOCKED-REMOTE-REV-*` folder launches `RevFolderUnlockerApp` which prompts for a private key |
| **MacFUSE mount detection** | Extension polls every 5 s for new MacFUSE volumes and auto-binds Finder Sync to them |
| **Deep linking (`f1r3drive://`)** | Opening `f1r3drive://unlock?revAddress=<ADDRESS>` launches the unlocker UI pre-filled for that address |

---

## 🚧 Planned Features (Disabled E2E Tests — TODO)

These tests exist in the codebase but are currently marked `@Disabled`. They represent features that are partially implemented or awaiting upstream support.

| Feature | Test Name | Reason / Notes |
|---------|-----------|----------------|
| **Deploy `.rho` file** | `shouldStoreRhoFileAndDeployIt` | Store a Rholang source file and deploy it to the blockchain |
| **Deploy `.metta` file** | `shouldStoreMettaFileAndDeployIt` | Store a MeTTa source file and deploy it (requires correct Metta code) |
| **Rename `.txt` → `.rho` triggers deploy** | `shouldDeployRhoFileAfterRename` | Renaming a text file to `.rho` should auto-deploy it |
| **Rename `.txt` → `.metta` triggers deploy** | `shouldDeployMettaFileAfterRename` | Renaming a text file to `.metta` should auto-deploy it |
| **Transparent encryption (`.encrypted`)** | `shouldEncryptOnSaveAndDecryptOnReadForEncryptedExtension` | Files saved with `.encrypted` extension are AES-encrypted on write and decrypted on read |
| **Encryption via rename** | `shouldEncryptOnChangingExtension` | Renaming a file to/from `.encrypted` triggers encryption/decryption |
| **Large file support (512 MB)** | `shouldWriteAndReadLargeFile` | Write and read a 512 MB binary file, verify content survives remount |
