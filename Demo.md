# F1r3Drive Demo

## Prerequisites

- [MacFuse](https://github.com/macfuse/macfuse/wiki/Getting-Started) (or [jnr-fuse](https://github.com/SerCeMan/jnr-fuse?tab=readme-ov-file#installation) on Linux)
- Java 17+
- A running F1R3FLY shard (see below)

## Running the F1r3fly Shard

The recommended way to run a local shard is via **shardctl** from the [system-integration](https://github.com/F1R3FLY-io/system-integration) repository.
For a complete step-by-step guide (including prerequisites, building, and configuration), see the [F1R3Drive with shardctl guide](https://github.com/F1R3FLY-io/system-integration/blob/main/docs/f1r3drive-guide.md).

```sh
# From the system-integration repo root:
shardctl up f1r3node-rust     # Start Rust shard (recommended)
shardctl wait                 # Wait for nodes to be ready
```

Default shard port mappings:

| Node | Validator gRPC | Observer gRPC |
|------|---------------|---------------|
| Validator 1 | `localhost:40412` | `localhost:40413` |
| Read-only Observer | `localhost:40452` | `localhost:40453` |

## Run F1r3Drive app

### Option 1: Via shardctl (Recommended)

```sh
# From the system-integration repo root:
shardctl up f1r3drive
```

This builds (if needed) and starts F1R3Drive with the correct connection settings automatically.

### Option 2: Manually with the JAR

1. Download the latest `f1r3drive-*.jar` from the [GitHub Releases page](https://github.com/f1r3fly-io/F1R3FLYFS/releases), or build locally:
   ```sh
   ./gradlew shadowJar -x test
   ```

2. Run with wallet credentials:

```sh
java -jar build/libs/f1r3drive-app.jar ~/demo-f1r3drive \
   --key-file ~/cipher.key \
   --host localhost --port 40412 \
   --observer-host localhost --observer-port 40452 \
   --address 111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA \
   --private-key 357cdc4201a5650830e0bc5a03299a30038d9934ba4c7ab73ec164ad82471ff9
```

> ⚠️ The credentials above are **test-only** keys. Do not use them on public shards.

## Demo

Creating a tiny file inside ~/demo-f1r3drive folder:

```sh
echo "abc" > ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/demo.txt
ls -lh ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/demo.txt
cat ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/demo.txt
```

Copy 1M file inside ~/demo-f1r3drive folder:

```sh
# generating binary file with 1M size
dd if=/dev/zero of=large_data.txt bs=1m count=1

# copy file (use rsync for progress logs)
cp large_data.txt ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/
# OR: rsync -av --progress large_data.txt ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/

# wait for some time, then verify the file is copied
ls -lh ~/demo-f1r3drive/111127RX5ZgiAdRaQy4AWy57RdvAAckdELReEBxzvWYVvdnR32PiHA/large_data.txt
```

## Cleanup

Stop all processes and remove all files:

```sh
# hit Ctrl+C in F1r3Drive app (or in the shardctl terminal)

# or kill if stuck
ps aux | grep java | grep -v grep | awk '{print $2}' | xargs kill -9

# force unmount ~/demo-f1r3drive if stuck
sudo diskutil umount force ~/demo-f1r3drive

# stop shard (from system-integration repo root)
shardctl down f1r3node-rust -v

# ~/demo-f1r3drive has to be empty folder
# delete ~/demo-f1r3drive before running the next time
rm -rf ~/demo-f1r3drive

# remove large_data.txt
rm -f large_data.txt
```
