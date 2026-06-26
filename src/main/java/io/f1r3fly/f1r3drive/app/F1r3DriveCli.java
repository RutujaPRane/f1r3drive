package io.f1r3fly.f1r3drive.app;

import io.f1r3fly.f1r3drive.app.linux.fuse.F1r3DriveFuse;
import io.f1r3fly.f1r3drive.encryption.AESCipher;
import io.f1r3fly.f1r3drive.blockchain.client.F1r3flyBlockchainClient;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "f1r3drive", mixinStandardHelpOptions = true,
    version = "f1r3drive 0.1.1", // NOTE: keep in sync with gradle.properties
    description = "A FUSE filesystem that stores data on the F1r3fly blockchain.")
class F1r3DriveCli implements Callable<Integer> {

    private static String[] getMountOptions() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return new String[] {
                "-o", "fsname=f1r3drive",
                "-o", "volname=F1r3Drive",
                "-o", "local",
                "-o", "noappledouble",
                "-o", "noatime",
                "-s"
            };
        } else {
            return new String[] {
                "-o", "fsname=f1r3drive",
                "-o", "noatime",
                "-s"
            };
        }
    }

    // --- Blockchain connection ---

    @Option(names = {"-H", "--host"},
        description = "Host of the F1r3fly validator gRPC API (default: ${DEFAULT-VALUE}).")
    private String validatorHost = "localhost";

    @Option(names = {"-P", "--port"},
        description = "Port of the F1r3fly validator gRPC API (default: ${DEFAULT-VALUE}).")
    private int validatorPort = 40402;

    @Option(names = {"-O", "--observer-host"},
        description = "Host of the F1r3fly observer gRPC API (default: ${DEFAULT-VALUE}).")
    private String observerHost = "localhost";

    @Option(names = {"--observer-port"},
        description = "Port of the F1r3fly observer gRPC API (default: ${DEFAULT-VALUE}).")
    private int observerPort = 40403;

    // --- Security ---

    @Option(names = {"-k", "--key-file"}, required = true,
        description = "Path to the AES cipher key file. A new key is generated if the file does not exist.")
    private String cipherKeyPath;

    // --- Mount point ---

    @Parameters(index = "0",
        description = "Directory path where the FUSE filesystem will be mounted.")
    private Path mountPoint;

    // --- Wallet / identity ---

    static class WalletOptions {
        @Option(names = {"-a", "--address"}, required = true,
            description = "REV address of the wallet to unlock.")
        String revAddress;

        @Option(names = {"-K", "--private-key"}, required = true,
            description = "Private key of the wallet (must be used together with --address).")
        String privateKey;
    }

    @ArgGroup(exclusive = false, multiplicity = "0..1")
    private WalletOptions wallet;

    // --- Propose mode ---

    @Option(names = {"--manual-propose"},
        description = "Enable manual block proposing after each deploy and wait for finalization. " +
            "Without this flag (default), F1r3Drive skips proposing and expects the shard to handle it " +
            "(e.g. heartbeat or autopropose service).")
    private boolean manualPropose = false;

    // --- Debug ---

    @Option(names = {"-d", "--debug"},
        description = "Enable FUSE debug mode for verbose logging of filesystem operations.")
    private boolean fuseDebug = false;

    private F1r3DriveFuse f1r3DriveFuse;


    @Override
    public Integer call() throws Exception {
        AESCipher.init(cipherKeyPath); // init singleton instance

        F1r3flyBlockchainClient f1R3FlyBlockchainClient = new F1r3flyBlockchainClient(
            validatorHost,
            validatorPort,
            observerHost,
            observerPort,
            manualPropose
        );

        f1r3DriveFuse = new F1r3DriveFuse(
            f1R3FlyBlockchainClient
        );

        // Add shutdown hook to ensure proper unmounting on Ctrl+C or kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (f1r3DriveFuse != null) {
                try {
                    f1r3DriveFuse.umount();
                } catch (Exception e) {
                    System.err.println("Error during shutdown unmount: " + e.getMessage());
                }
            }
        }, "F1r3Drive-Shutdown"));

        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), new sun.misc.SignalHandler() {
                private int count = 0;
                @Override
                public void handle(sun.misc.Signal sig) {
                    count++;
                    if (count >= 2) {
                        System.err.println("\nDouble Ctrl+C detected. Forcing hard stop!");
                        Runtime.getRuntime().halt(130);
                    } else {
                        System.err.println("\nInterrupt received. Unmounting... (Press Ctrl+C again to force quit)");
                        new Thread(() -> System.exit(130)).start();
                    }
                }
            });
        } catch (Throwable t) {
            System.err.println("Signal handling to detect double Ctrl+C not supported: " + t.getMessage());
        }

        // Mount filesystem - unmounting is handled by shutdown hook
        if (wallet != null) {
            f1r3DriveFuse.mountAndUnlockRootDirectory(mountPoint, true, fuseDebug, wallet.revAddress, wallet.privateKey, getMountOptions());
        } else {
            f1r3DriveFuse.mount(mountPoint, true, fuseDebug, getMountOptions());
        }
        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new F1r3DriveCli()).execute(args);
        System.exit(exitCode);
    }
}
