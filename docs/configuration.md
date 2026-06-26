# F1r3Drive CLI Configuration Reference

F1r3Drive is launched via the `F1r3DriveCli` class which uses [picocli](https://picocli.info/) for argument parsing.

```bash
java -jar f1r3drive-<version>.jar <mount-point> [OPTIONS]
```

## Positional Arguments

| Position | Name | Required | Description |
|----------|------|----------|-------------|
| `0` | `<mount-point>` | **Yes** | The local directory path where F1r3Drive will mount the FUSE filesystem. The mount point directory must already exist. |

## Options

### Blockchain Connection

These options configure which F1r3fly node F1r3Drive connects to over gRPC.

F1r3fly nodes expose two gRPC APIs:

- **Validator API** — used for deploying Rholang contracts and proposing blocks.
- **Observer API** — used for reading data from the blockchain without affecting state.

| Short | Long | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `-H` | `--host` | No | `localhost` | Host of the F1r3fly validator gRPC API. |
| `-P` | `--port` | No | `40402` | Port of the F1r3fly validator gRPC API. |
| `-O` | `--observer-host` | No | `localhost` | Host of the F1r3fly observer gRPC API. |
| — | `--observer-port` | No | `40403` | Port of the F1r3fly observer gRPC API. |

**Example** — connecting to the default local bootstrap node:

```bash
--host localhost --port 40402 \
--observer-host localhost --observer-port 40403
```

**Example** — connecting to a remote node:

```bash
--host node.example.com --port 40402 \
--observer-host node.example.com --observer-port 40403
```

### Encryption

| Short | Long | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `-k` | `--key-file` | **Yes** | — | Path to the AES cipher key file used for encrypting data on the blockchain. If the file does not exist, a new key is automatically generated and saved at the specified path. |

The cipher key is initialized at startup as a singleton (`AESCipher.init()`) and used throughout the session. Keep this file secure — losing it means you cannot decrypt data you previously stored.

### Wallet / Identity

These options unlock a wallet directory within the mounted filesystem. **Both must be provided together**, or neither — F1r3Drive will exit with an error if only one is given. While individually optional, they are conditionally required as a pair.

| Short | Long | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `-a` | `--address` | No | — | REV address of the wallet to unlock. Must be a valid address from the shard's `wallets.txt`. |
| `-K` | `--private-key` | No | — | The private key corresponding to the REV address. Used for signing deploys. |

When both `--address` and `--private-key` are provided, F1r3Drive mounts the filesystem **and** unlocks the root directory for that wallet, enabling read/write access to on-chain data. Without these flags, the filesystem is mounted without wallet access.

### Propose Mode

| Short | Long | Required | Default | Description |
|-------|------|----------|---------|-------------|
| — | `--manual-propose` | No | `false` | Enable manual block proposing. |

- **Without `--manual-propose`** (default) — F1r3Drive deploys contracts but does not propose. The shard is expected to handle block proposals automatically (e.g., via the heartbeat proposer or the `autopropose` service in `shard-with-autopropose.yml`). Use this for production shards.
- **With `--manual-propose`** — F1r3Drive proposes blocks manually after each deploy and waits for finalization. Use this for development and testing against shards without an auto-propose mechanism.

### Debugging

| Short | Long | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `-d` | `--debug` | No | `false` | Enable FUSE debug mode for verbose logging of all filesystem operations. Useful for troubleshooting mount or I/O issues. |

## Built-in Help

F1r3Drive supports `--help` and `--version` flags:

```bash
java -jar f1r3drive-<version>.jar --help
java -jar f1r3drive-<version>.jar --version
```

## Full Example Commands

> **Note:** Replace `0.1.1` in the examples below with your actual build version from `gradle.properties`.

### Development (local shard with auto-propose)

```bash
java -jar build/libs/f1r3drive-0.1.1.jar ~/demo-f1r3drive \
  --key-file ~/cipher.key \
  --host localhost --port 40402 \
  --observer-host localhost --observer-port 40403 \
  --address 111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA \
  --private-key 357cdc4201a5650830e0bc5a03299a30038d9934ba4c7ab73ec164ad82471ff9
```

### Development (local singleton shard, manual propose)

```bash
java -jar build/libs/f1r3drive-0.1.1.jar ~/demo-f1r3drive \
  --key-file ~/cipher.key \
  --host localhost --port 40402 \
  --observer-host localhost --observer-port 40403 \
  --manual-propose \
  --address 111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA \
  --private-key 357cdc4201a5650830e0bc5a03299a30038d9934ba4c7ab73ec164ad82471ff9
```

### Minimal (no wallet unlock, manual propose against local shard)

```bash
java -jar build/libs/f1r3drive-0.1.1.jar ~/demo-f1r3drive \
  --key-file ~/cipher.key \
  --manual-propose
```

### Remote shard

```bash
java -jar f1r3drive-0.1.1.jar ~/f1r3drive-mount \
  --key-file ~/.f1r3drive/cipher.key \
  --host node.f1r3fly.io --port 40402 \
  --observer-host node.f1r3fly.io --observer-port 40403 \
  --address <your-rev-address> \
  --private-key <your-private-key>
```
