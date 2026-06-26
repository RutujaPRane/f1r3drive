# F1r3Drive

F1r3Drive is a FUSE-based filesystem that stores data on the F1r3fly blockchain. It is built in Java using [JNR-FUSE](https://github.com/SerCeMan/jnr-fuse) and connects to a running F1r3fly node via gRPC.

## Prerequisites

The only strict prerequisite is having the FUSE libraries installed for your operating system:

- **macOS** — install [macFUSE](https://github.com/macfuse/macfuse/wiki/Getting-Started).
- **Linux / Windows** — see the [jnr-fuse installation guide](INSTALLATION.md).

### 🍎 macOS Finder Extension (Optional, but Recommended)

If you're using a Mac, the core F1r3Drive app gives you everything you need to mount files directly from the blockchain at a basic level:
- ✅ Unlock and mount a single root folder to your computer
- ✅ Read, write, and manage files and folders normally
- ✅ Access the hidden `.token` folder to manage permissions manually

**Want a more native, seamless experience?**
To get advanced, Dropbox-style features right inside Finder, install the [**F1R3Drive Finder Extension**](https://github.com/F1R3FLY-io/f1r3drive-extension)! 

Installing the extension unlocks:
- 🎨 **Status Badges**: See exactly which files are synced or pending right on their icons.
- 🖱️ **Context Menus**: Right-click any file or folder to easily manage `.token` configuration files, share links, or control permissions without opening the terminal.

## Getting Started

There are two parts to using F1r3Drive: running a F1r3fly shard and running the F1r3Drive application.

### 1. Running a F1r3fly Shard

F1r3Drive connects to a F1r3fly node's gRPC API. You can either run a local shard or connect to a remote one.

#### Local Shard with shardctl (Recommended)

The easiest way to run a local shard is via **shardctl** from the [system-integration](https://github.com/F1R3FLY-io/system-integration) repository.
For a complete step-by-step guide, see the [F1R3Drive with shardctl guide](https://github.com/F1R3FLY-io/system-integration/blob/main/docs/f1r3drive-guide.md).

```bash
# From the system-integration repo root:
shardctl up f1r3node-rust     # Start Rust shard (recommended)
shardctl wait                 # Wait for nodes to be ready

# Start F1R3Drive (builds automatically if needed)
shardctl up f1r3drive
```

**Default port mappings (multi-validator shard):**

| Node | Validator gRPC | Observer gRPC |
|------|---------------|---------------|
| Validator 1 | `localhost:40412` | `localhost:40413` |
| Read-only Observer | `localhost:40452` | `localhost:40453` |

#### Connect to a Remote Shard

If you have access to a remote shard, use the `--host`, `--port`, `--observer-host`, and `--observer-port` flags to point F1r3Drive at it.

### 2. Running F1r3Drive

#### Option A: Use shardctl (Recommended)

If you're using the [system-integration](https://github.com/F1R3FLY-io/system-integration) repository, simply run:

```bash
shardctl up f1r3drive
```

This automatically builds the JAR (if needed) and starts F1R3Drive with the correct connection settings.

#### Option B: Use the Pre-built JAR

Download the latest `f1r3drive-*.jar` from the [GitHub Releases page](https://github.com/f1r3fly-io/F1R3FLYFS/releases). Requires **Java 17**.

#### Option C: Build from Source

**Requirements:** [Nix](https://nixos.org/download/), [direnv](https://direnv.net/#basic-installation), [Protobuf Compiler](https://grpc.io/docs/protoc-installation/)

```bash
# 1. Clone and enter the repository
git clone https://github.com/f1r3fly-io/F1R3FLYFS.git && cd F1R3FLYFS

# 2. Set up the development environment via Nix
direnv allow

# 3. Build the fat JAR
./gradlew shadowJar -x test
```

The resulting JAR will be at `build/libs/f1r3drive-app.jar`.

## Usage

```bash
java -jar f1r3drive-<version>.jar <mount-point> \
  --key-file <path-to-key> \
  [--manual-propose] \
  [--host <host>] [--port <port>] \
  [--observer-host <host>] [--observer-port <port>] \
  [--address <rev-address> --private-key <key>] \
  [--debug]
```

**Quick start example** (connecting to the local shard started with shardctl):

> ⚠️ The credentials below are **test-only** keys. Do not use them on public shards.

```bash
java -jar build/libs/f1r3drive-app.jar ~/demo-f1r3drive \
  --key-file ~/cipher.key \
  --host localhost --port 40412 \
  --observer-host localhost --observer-port 40452 \
  --address 111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA \
  --private-key 357cdc4201a5650830e0bc5a03299a30038d9934ba4c7ab73ec164ad82471ff9
```

For the full CLI reference, see **[docs/configuration.md](docs/configuration.md)**.
For a step-by-step demo walkthrough, see **[Demo.md](Demo.md)**.

## Running Tests

```bash
# Unit tests
./gradlew test

# End-to-end tests (requires a running shard)
./gradlew e2eTest --rerun-tasks
```

## Git Hooks

This repo ships git hooks under [`.githooks/`](.githooks). They are **opt-in** — run the installer once per clone:

```bash
./scripts/setup-hooks.sh           # recommended: sets core.hooksPath = .githooks
./scripts/setup-hooks.sh --copy    # alternative: copy into .git/hooks/
./scripts/setup-hooks.sh --status  # show current configuration
./scripts/setup-hooks.sh --remove  # uninstall
```

What they run:

| Hook | Checks | Bypass |
|------|--------|--------|
| **pre-commit** | `./gradlew spotlessCheck` (google-java-format). Spotless uses `ratchetFrom 'HEAD'`, so only files in the current commit are checked. Fix with `./gradlew spotlessApply`. | `SKIP_SPOTLESS=1` / `git commit --no-verify` |
| **pre-push** | `./gradlew test` (unit tests) + `./gradlew shadowJar` (build). The e2e suite is *not* run — it needs a live shard. | `SKIP_TESTS=1`, `SKIP_BUILD=1` / `git push --no-verify` |

Add `VERBOSE=1` to either to see full output.

## License

[MIT](LICENSE)
