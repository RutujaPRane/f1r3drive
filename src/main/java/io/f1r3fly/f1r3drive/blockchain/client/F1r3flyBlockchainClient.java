package io.f1r3fly.f1r3drive.blockchain.client;

import casper.CasperMessage;
import casper.DeployServiceCommon;
import casper.ProposeServiceCommon;
import casper.v1.DeployServiceGrpc;
import casper.v1.DeployServiceV1;
import casper.v1.ProposeServiceGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import com.rfksystems.blake2b.Blake2b;
import com.rfksystems.blake2b.security.Blake2bProvider;
import fr.acinq.secp256k1.Hex;
import fr.acinq.secp256k1.Secp256k1;
import io.f1r3fly.f1r3drive.errors.F1r3flyDeployError;
import io.f1r3fly.f1r3drive.errors.F1r3DriveError;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rhoapi.RhoTypes;
import servicemodelapi.ServiceErrorOuterClass;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Duration;
import java.util.stream.Collectors;

public class F1r3flyBlockchainClient {
    public static final String RHOLANG = "rholang";
    public static final String METTA_LANGUAGE = "metta";

    private static final Logger LOGGER = LoggerFactory.getLogger(F1r3flyBlockchainClient.class);

    private static final Duration INIT_DELAY = Duration.ofMillis(100);
    private static final Duration MAX_DELAY = Duration.ofSeconds(5);
    private static final int RETRIES = 10;
    private static final int MAX_MESSAGE_SIZE = Integer.MAX_VALUE; // ~2 GB

    private final DeployServiceGrpc.DeployServiceBlockingStub validatorDeployService;
    private final ProposeServiceGrpc.ProposeServiceBlockingStub validatorProposeService;
    private final DeployServiceGrpc.DeployServiceBlockingStub observerDeployService;
    private final boolean manualPropose;

    public F1r3flyBlockchainClient(String validatorHost,
            int validatorPort,
            String observerHost,
            int observerPort,
            boolean manualPropose) {
        super();

        Security.addProvider(new Blake2bProvider());

        ManagedChannel validatorChannel = ManagedChannelBuilder.forAddress(validatorHost, validatorPort).usePlaintext()
                .build();

        this.validatorDeployService = DeployServiceGrpc.newBlockingStub(validatorChannel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);
        this.validatorProposeService = ProposeServiceGrpc.newBlockingStub(validatorChannel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);

        ManagedChannel observerChannel = ManagedChannelBuilder.forAddress(observerHost, observerPort).usePlaintext()
                .build();

        this.observerDeployService = DeployServiceGrpc.newBlockingStub(observerChannel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);
        
        this.manualPropose = manualPropose;
    }


    private String gatherErrors(ServiceErrorOuterClass.ServiceError error) {
        ProtocolStringList messages = error.getMessagesList();
        return messages.stream().collect(Collectors.joining("\n"));
    }

    public DeployServiceCommon.BlockInfo getGenesisBlock() throws F1r3DriveError {
        try {
            LOGGER.info("Getting genesis block");
            java.util.Iterator<DeployServiceV1.BlockInfoResponse> responseIterator = observerDeployService.getBlocksByHeights(
                DeployServiceCommon.BlocksQueryByHeight.newBuilder()
                    .setStartBlockNumber(0)
                    .setEndBlockNumber(0)
                    .build()
            );

            if (!responseIterator.hasNext()) {
                LOGGER.warn("No blocks found");
                throw new F1r3DriveError("No genesis block found");
            }

            DeployServiceCommon.LightBlockInfo lightBlock = responseIterator.next().getBlockInfo();
            
            // Get the full block info using the hash from the light block
            DeployServiceCommon.BlockInfo block = observerDeployService.getBlock(
                    DeployServiceCommon.BlockQuery.newBuilder()
                            .setHash(lightBlock.getBlockHash())
                            .build())
                    .getBlockInfo();

            while (block.getBlockInfo().getBlockNumber() > 0) {
                LOGGER.info("Getting block {}", block.getBlockInfo().getParentsHashList(0));
                block = observerDeployService.getBlock(
                        DeployServiceCommon.BlockQuery.newBuilder()
                                .setHash(block.getBlockInfo().getParentsHashList(0))
                                .build())
                        .getBlockInfo();
            }

            return block;
        } catch (Exception e) {
            LOGGER.error("Error retrieving genesis block", e);
            throw new F1r3DriveError("Error retrieving genesis block", e);
        }
    }

    public DeployServiceCommon.BlockInfo getLastFinalizedBlockFromValidator() throws F1r3DriveError {
        try {
            DeployServiceV1.LastFinalizedBlockResponse response = validatorDeployService.lastFinalizedBlock(
                    DeployServiceCommon.LastFinalizedBlockQuery.newBuilder().build());
            return response.getBlockInfo();
        } catch (Exception e) {
            LOGGER.error("Error retrieving last finalized block from validator", e);
            throw new F1r3DriveError("Error retrieving last finalized block from validator", e);
        }
    }

    public DeployServiceCommon.BlockInfo getLastFinalizedBlockFromObserver() throws F1r3DriveError {
        try {
            DeployServiceV1.LastFinalizedBlockResponse response = observerDeployService.lastFinalizedBlock(
                    DeployServiceCommon.LastFinalizedBlockQuery.newBuilder().build());
            return response.getBlockInfo();
        } catch (Exception e) {
            LOGGER.error("Error retrieving last finalized block from observer", e);
            throw new F1r3DriveError("Error retrieving last finalized block from observer", e);
        }
    }

    public void waitForNodesSynchronization() throws F1r3DriveError {
        waitForNodesSynchronization(5000, 60000); // 5s interval, 60s timeout
    }

    public void waitForNodesSynchronization(long intervalMs, long timeoutMs) throws F1r3DriveError {
        LOGGER.info("Waiting for validator and observer to have the same last block...");

        long startTime = System.currentTimeMillis();
        long endTime = startTime + timeoutMs;

        while (System.currentTimeMillis() < endTime) {
            try {
                DeployServiceCommon.BlockInfo validatorBlock = getLastFinalizedBlockFromValidator();
                DeployServiceCommon.BlockInfo observerBlock = getLastFinalizedBlockFromObserver();

                long validatorBlockNumber = validatorBlock.getBlockInfo().getBlockNumber();
                long observerBlockNumber = observerBlock.getBlockInfo().getBlockNumber();

                LOGGER.debug("Validator last block: {}, Observer last block: {}",
                        validatorBlockNumber, observerBlockNumber);

                if (validatorBlockNumber == observerBlockNumber) {
                    LOGGER.info("Nodes synchronized at block number: {}", validatorBlockNumber);
                    return;
                }

                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new F1r3DriveError("Interrupted while waiting for node synchronization", e);
            }
        }

        throw new F1r3DriveError("Timeout waiting for nodes to synchronize after " + timeoutMs + "ms");
    }

    public RhoTypes.Expr exploratoryDeploy(String rhoCode) throws F1r3DriveError {
        try {
            LOGGER.debug("Exploratory deploy code {}", rhoCode);

            // Create query
            DeployServiceCommon.ExploratoryDeployQuery exploratoryDeploy =
                DeployServiceCommon.ExploratoryDeployQuery.newBuilder()
                .setTerm(rhoCode)
                .build();

            // Deploy
            DeployServiceV1.ExploratoryDeployResponse deployResponse = observerDeployService.exploratoryDeploy(exploratoryDeploy);
                
            LOGGER.trace("Exploratory deploy code {}. Response {}", rhoCode, deployResponse);
            
            if (deployResponse.hasError()) {
                LOGGER.debug("Exploratory deploy code {}. Error response {}", rhoCode, deployResponse.getError());
                throw new F1r3DriveError("Error retrieving exploratory deploy: " + gatherErrors(deployResponse.getError()));
            }

            LOGGER.debug("Exploratory deploy code {}. Result {}", rhoCode, deployResponse.getResult());

            if (deployResponse.getResult().getPostBlockDataCount() == 0) {
                LOGGER.debug("Exploratory deploy code {}. No data found (empty PostBlockData list)", rhoCode);
                throw new F1r3DriveError("No data found at channel");
            }

            LOGGER.debug("Exploratory deploy code {}. PostBlockData count {}", rhoCode, deployResponse.getResult().getPostBlockDataCount());

            if (deployResponse.getResult().getPostBlockData(0).getExprsCount() == 0) {
                LOGGER.debug("Exploratory deploy code {}. No expressions found in PostBlockData", rhoCode);
                throw new F1r3DriveError("No expressions found in data");
            }

            return deployResponse.getResult().getPostBlockData(0).getExprs(0);
            
            
        } catch (Exception e) {
            LOGGER.warn("failed to deploy exploratory code", e);
            throw new F1r3DriveError("Error deploying exploratory code", e);
        }
    }

    public void deploy(String rhoCode, boolean useBiggerRhloPrice, String language, byte[] signingKey, long timestamp)
            throws F1r3flyDeployError {
        try {

            int maxRholangInLogs = 2000;
            LOGGER.debug("Rholang code {}",
                    rhoCode.length() > maxRholangInLogs ? rhoCode.substring(0, maxRholangInLogs) : rhoCode);

            long phloLimit = useBiggerRhloPrice ? 5_000_000_000L : 50_000L;

            LOGGER.trace("Language parameter is skipped for now: {}. Using default language: {}", language, RHOLANG);

            // Get last finalized block from validator to set validAfterBlockNumber
            DeployServiceCommon.BlockInfo lastBlock = getLastFinalizedBlockFromValidator();
            long validAfterBlockNumber = lastBlock.getBlockInfo().getBlockNumber();
            LOGGER.debug("Setting validAfterBlockNumber to: {}", validAfterBlockNumber);

            // Make deployment
            CasperMessage.DeployDataProto deployment = CasperMessage.DeployDataProto.newBuilder()
                    .setTerm(rhoCode)
                    .setTimestamp(timestamp)
                    .setPhloPrice(1)
                    .setPhloLimit(phloLimit)
                    .setValidAfterBlockNumber(validAfterBlockNumber)
                    .setShardId("root")
                    // .setLanguage(language)
                    .build();

            // Sign deployment
            CasperMessage.DeployDataProto signed = signDeploy(deployment, signingKey);

            // Deploy
            DeployServiceV1.DeployResponse deployResponse = validatorDeployService.doDeploy(signed);
            if (deployResponse.hasError()) {
                throw new F1r3flyDeployError(rhoCode, gatherErrors(deployResponse.getError()));
            }

            // If manual propose is disabled, just return after deploying
            if (!manualPropose) {
                LOGGER.debug("Manual propose is disabled, skipping propose and finalization waiting");
                return;
            }

            String deployResult = deployResponse.getResult();
            String deployId = deployResult.substring(deployResult.indexOf("DeployId is: ") + 13,
                    deployResult.length());

            // Propose
            casper.v1.ProposeServiceV1.ProposeResponse proposeResponse = validatorProposeService.propose(
                    ProposeServiceCommon.ProposeQuery.newBuilder().setIsAsync(false).build());
            if (proposeResponse.hasError()) {
                throw new F1r3flyDeployError(rhoCode, gatherErrors(proposeResponse.getError()));
            }

            // Find deploy
            ByteString b64 = ByteString.copyFrom(Hex.decode(deployId));
            DeployServiceV1.FindDeployResponse findResponse = validatorDeployService.findDeploy(
                    DeployServiceCommon.FindDeployQuery.newBuilder().setDeployId(b64).build());
            if (findResponse.hasError()) {
                throw new F1r3flyDeployError(rhoCode, gatherErrors(findResponse.getError()));
            }

            String blockHash = findResponse.getBlockInfo().getBlockHash();
            LOGGER.debug("Block Hash {}", blockHash);

            // Wait for finalization with retry logic
            for (int attempt = 0; attempt < RETRIES; attempt++) {
                try {
                    DeployServiceV1.IsFinalizedResponse isFinalizedResponse = validatorDeployService.isFinalized(
                            DeployServiceCommon.IsFinalizedQuery.newBuilder().setHash(blockHash).build());
                    
                    LOGGER.debug("isFinalizedResponse {}", isFinalizedResponse);
                    
                    if (isFinalizedResponse.hasError()) {
                        throw new F1r3flyDeployError(rhoCode, gatherErrors(isFinalizedResponse.getError()));
                    }
                    
                    if (isFinalizedResponse.getIsFinalized()) {
                        LOGGER.debug("Deploy finalized successfully");
                        return;
                    }
                    
                    // Wait before retry
                    Thread.sleep(Math.min(INIT_DELAY.toMillis() * (1L << attempt), MAX_DELAY.toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new F1r3flyDeployError(rhoCode, "Interrupted while waiting for finalization", e);
                }
            }

            throw new F1r3flyDeployError(rhoCode, "Deploy not finalized after " + RETRIES + " attempts");
        } catch (Exception e) {
            if (e instanceof F1r3flyDeployError) {
                throw (F1r3flyDeployError) e;
            } else {
                LOGGER.warn("failed to deploy Rho {}", rhoCode, e);
                throw new F1r3flyDeployError(rhoCode, "Failed to deploy", e);
            }
        }
    }

    private CasperMessage.DeployDataProto signDeploy(CasperMessage.DeployDataProto deploy, byte[] signingKey) {
        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance(Blake2b.BLAKE2_B_256);
        } catch (NoSuchAlgorithmException e) {
            throw new F1r3DriveError("Can't load MessageDigest instance (BLAKE2_B_256)", e);
        }

        final Secp256k1 secp256k1 = Secp256k1.get();

        CasperMessage.DeployDataProto.Builder builder = CasperMessage.DeployDataProto.newBuilder();

        builder
                .setTerm(deploy.getTerm())
                .setTimestamp(deploy.getTimestamp())
                .setPhloPrice(deploy.getPhloPrice())
                .setPhloLimit(deploy.getPhloLimit())
                .setValidAfterBlockNumber(deploy.getValidAfterBlockNumber())
                .setShardId(deploy.getShardId());

        CasperMessage.DeployDataProto signed = builder.build();

        byte[] serial = signed.toByteArray();
        digest.update(serial);
        byte[] hashed = digest.digest();
        byte[] signature = secp256k1.compact2der(secp256k1.sign(hashed, signingKey));
        byte[] pubKey = secp256k1.pubkeyCreate(signingKey);

        CasperMessage.DeployDataProto.Builder outbound = signed.toBuilder();
        outbound
                .setSigAlgorithm("secp256k1")
                .setSig(ByteString.copyFrom(signature))
                .setDeployer(ByteString.copyFrom(pubKey));

        return outbound.build();
    }
}
