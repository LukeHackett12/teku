/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beacon.sync.forward.singlepeer;

import static tech.pegasys.teku.spec.config.Constants.MAX_BLOCK_BY_RANGE_REQUEST_SIZE;
import static tech.pegasys.teku.spec.config.Constants.MAX_REQUEST_BLOBS_SIDECARS;

import com.google.common.base.Throwables;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.peers.Eth2Peer;
import tech.pegasys.teku.networking.eth2.peers.PeerStatus;
import tech.pegasys.teku.networking.eth2.rpc.beaconchain.methods.BlocksByRangeResponseInvalidResponseException;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException;
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason;
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseListener;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.versions.eip4844.BlobsSidecar;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult.FailureReason;
import tech.pegasys.teku.statetransition.blobs.BlobsSidecarManager;
import tech.pegasys.teku.statetransition.block.BlockImporter;
import tech.pegasys.teku.storage.client.RecentChainData;

public class PeerSync {
  private static final Duration NEXT_REQUEST_TIMEOUT = Duration.ofSeconds(3);

  /**
   * Peers are allowed to limit the number of blocks they actually return to use. We tolerate this
   * up to a point, but if the peer is throttling too excessively we would be better syncing from a
   * different peer. This value sets how many slots we should progress per request. Since some slots
   * may be empty we check that we're progressing through slots, even if not many blocks are being
   * returned.
   */
  static final UInt64 MIN_SLOTS_TO_PROGRESS_PER_REQUEST = UInt64.valueOf(50);

  private static final List<FailureReason> BAD_BLOCK_FAILURE_REASONS =
      List.of(
          FailureReason.FAILED_WEAK_SUBJECTIVITY_CHECKS,
          FailureReason.FAILED_STATE_TRANSITION,
          FailureReason.UNKNOWN_PARENT,
          FailureReason.FAILED_BLOBS_AVAILABILITY_CHECK);

  private static final Logger LOG = LogManager.getLogger();
  static final int MAX_THROTTLED_REQUESTS = 10;

  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final Spec spec;
  private final RecentChainData storageClient;
  private final BlockImporter blockImporter;
  private final BlobsSidecarManager blobsSidecarManager;

  private final AsyncRunner asyncRunner;
  private final Counter blockImportSuccessResult;
  private final Counter blockImportFailureResult;
  private final Counter blobsSidecarImportSuccessResult;
  private final Counter blobsSidecarImportFailureResult;

  private final AtomicInteger throttledRequestCount = new AtomicInteger(0);

  private volatile UInt64 startingSlot = UInt64.valueOf(0);

  public PeerSync(
      final AsyncRunner asyncRunner,
      final RecentChainData storageClient,
      final BlockImporter blockImporter,
      final BlobsSidecarManager blobsSidecarManager,
      final MetricsSystem metricsSystem) {
    this.spec = storageClient.getSpec();
    this.asyncRunner = asyncRunner;
    this.storageClient = storageClient;
    this.blockImporter = blockImporter;
    this.blobsSidecarManager = blobsSidecarManager;
    final LabelledMetric<Counter> blockImportCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.BEACON,
            "block_import_total",
            "The number of block imports performed",
            "result");
    final LabelledMetric<Counter> blobsSidecarImportCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.BEACON,
            "blobs_sidecar_import_total",
            "The number of blobs sidecar imports performed",
            "result");
    this.blockImportSuccessResult = blockImportCounter.labels("imported");
    this.blockImportFailureResult = blockImportCounter.labels("rejected");
    this.blobsSidecarImportSuccessResult = blobsSidecarImportCounter.labels("imported");
    this.blobsSidecarImportFailureResult = blobsSidecarImportCounter.labels("rejected");
  }

  public SafeFuture<PeerSyncResult> sync(final Eth2Peer peer) {
    LOG.debug("Start syncing to peer {}", peer);
    // Begin requesting blocks at our first non-finalized slot
    final UInt64 finalizedEpoch = storageClient.getFinalizedEpoch();
    final UInt64 latestFinalizedSlot = spec.computeStartSlotAtEpoch(finalizedEpoch);
    final UInt64 firstNonFinalSlot = latestFinalizedSlot.plus(UInt64.ONE);

    this.startingSlot = firstNonFinalSlot;

    return executeSync(peer, firstNonFinalSlot, SafeFuture.COMPLETE, true)
        .whenComplete(
            (res, err) -> {
              if (err != null) {
                LOG.debug("Failed to sync with peer {}: {}", peer, err);
              } else {
                LOG.debug("Finished syncing (with status {}) to peer {}", res.name(), peer);
              }
            });
  }

  public void stop() {
    stopped.set(true);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private SafeFuture<PeerSyncResult> executeSync(
      final Eth2Peer peer,
      final UInt64 startSlot,
      final SafeFuture<Void> readyForRequest,
      final boolean findCommonAncestor) {
    if (stopped.get()) {
      return SafeFuture.completedFuture(PeerSyncResult.CANCELLED);
    }

    final PeerStatus status = peer.getStatus();

    if (startSlot.isGreaterThan(status.getHeadSlot())) {
      // We've synced the advertised head, nothing left to request
      return completeSyncWithPeer(peer, status);
    }

    final RequestContext requestContext = createRequestContext(startSlot, status);

    final UInt64 count = requestContext.getCount();
    final boolean blobsSidecarsRequired = requestContext.areBlobsSidecarsRequired();

    return readyForRequest
        .thenCompose(
            __ -> {
              if (!findCommonAncestor) {
                return SafeFuture.completedFuture(startSlot);
              }
              CommonAncestor ancestor = new CommonAncestor(storageClient);
              return ancestor.getCommonAncestor(peer, startSlot, status.getHeadSlot());
            })
        .thenCompose(
            (ancestorStartSlot) -> {
              if (findCommonAncestor) {
                LOG.trace("Start sync from slot {}, instead of {}", ancestorStartSlot, startSlot);
              }

              LOG.debug(
                  "Request {} blocks starting at {} from peer {}",
                  count,
                  ancestorStartSlot,
                  peer.getId());

              final SafeFuture<Void> readyForNextRequest =
                  asyncRunner.getDelayedFuture(NEXT_REQUEST_TIMEOUT);

              final BlockAndBlobsSidecarMatcher blockAndBlobsSidecarMatcher =
                  new BlockAndBlobsSidecarMatcher(
                      blobsSidecarManager,
                      this::storeBlobsSidecarAndImportBlock,
                      this::importBlock);

              final RpcResponseListener<SignedBeaconBlock> blockListener;
              final SafeFuture<Void> blobsSidecarsRequest;

              if (blobsSidecarsRequired) {
                blockListener = blockAndBlobsSidecarMatcher::recordBlock;
                LOG.debug(
                    "Request {} blobs sidecars starting at {} from peer {}",
                    count,
                    ancestorStartSlot,
                    peer.getId());
                blobsSidecarsRequest =
                    peer.requestBlobsSidecarsByRange(
                        ancestorStartSlot, count, blockAndBlobsSidecarMatcher::recordBlobsSidecar);
              } else {
                blockListener = this::importBlock;
                blobsSidecarsRequest = SafeFuture.COMPLETE;
              }

              final PeerSyncBlockRequest request =
                  new PeerSyncBlockRequest(
                      readyForNextRequest, ancestorStartSlot.plus(count), blockListener);

              return SafeFuture.allOfFailFast(
                      peer.requestBlocksByRange(ancestorStartSlot, count, request),
                      blobsSidecarsRequest)
                  .thenApply(
                      __ -> {
                        blockAndBlobsSidecarMatcher.clearCache();
                        return request;
                      });
            })
        .thenCompose(
            (blockRequest) -> {
              final UInt64 nextSlot = blockRequest.getActualEndSlot().plus(UInt64.ONE);
              LOG.trace(
                  "Completed request for {} slots from peer {}. Next request starts from {}",
                  requestContext.count,
                  peer.getId(),
                  nextSlot);
              if (count.isGreaterThan(MIN_SLOTS_TO_PROGRESS_PER_REQUEST)
                  && startSlot.plus(MIN_SLOTS_TO_PROGRESS_PER_REQUEST).isGreaterThan(nextSlot)) {
                final int throttledRequests = throttledRequestCount.incrementAndGet();
                LOG.debug(
                    "Received {} consecutive excessively throttled response from {}",
                    throttledRequests,
                    peer.getId());
                if (throttledRequests > MAX_THROTTLED_REQUESTS) {
                  LOG.debug(
                      "Rejecting peer {} as sync target because it excessively throttled returned blocks",
                      peer.getId());
                  return SafeFuture.completedFuture(PeerSyncResult.EXCESSIVE_THROTTLING);
                }
              } else {
                throttledRequestCount.set(0);
              }
              return executeSync(peer, nextSlot, blockRequest.getReadyForNextRequest(), false);
            })
        .exceptionally(err -> handleFailedRequestToPeer(peer, status, err));
  }

  private PeerSyncResult handleFailedRequestToPeer(
      final Eth2Peer peer, final PeerStatus peerStatus, final Throwable err) {
    final Throwable rootException = Throwables.getRootCause(err);
    if (rootException instanceof FailedBlockImportException) {
      final FailedBlockImportException importException = (FailedBlockImportException) rootException;
      final FailureReason reason = importException.getResult().getFailureReason();
      final SignedBeaconBlock block = importException.getBlock();

      if (reason.equals(FailureReason.UNKNOWN_PARENT)
          && !hasPeerFinalizedBlock(block, peerStatus)) {
        // We received a block that doesn't connect to our chain.
        // This can happen if our peer is sending us blocks from the non-final portion of their
        // chain. They may be sending us blocks from a stale fork that we have already pruned out of
        // our Store.
        LOG.debug(
            "Failed to import non-final block from peer (err: {}) {}: {}", reason, block, peer);
        return PeerSyncResult.IMPORT_FAILED;
      } else if (BAD_BLOCK_FAILURE_REASONS.contains(reason)) {
        LOG.warn("Failed to import block from peer (err: {}) {}: {}", reason, block, peer);
        LOG.debug(
            "Disconnecting from peer ({}) who sent invalid block ({}): {}",
            peer,
            reason.name(),
            block);
        disconnectFromPeer(peer);
        return PeerSyncResult.BAD_BLOCK;
      } else {
        LOG.warn("Failed to import block from peer (err: {}) {}: {}", reason, block, peer);
        return PeerSyncResult.IMPORT_FAILED;
      }
    }

    if (rootException instanceof CancellationException) {
      return PeerSyncResult.CANCELLED;
    }

    if (rootException instanceof BlocksByRangeResponseInvalidResponseException
        || rootException instanceof RpcException) {
      disconnectFromPeer(peer);
      return PeerSyncResult.INVALID_RESPONSE;
    }

    if (err instanceof RuntimeException) {
      throw (RuntimeException) err;
    } else {
      throw new RuntimeException("Unhandled error while syncing", err);
    }
  }

  private boolean hasPeerFinalizedBlock(final SignedBeaconBlock block, final PeerStatus status) {
    return block
        .getSlot()
        .isLessThanOrEqualTo(status.getFinalizedCheckpoint().getEpochStartSlot(spec));
  }

  private SafeFuture<PeerSyncResult> completeSyncWithPeer(
      final Eth2Peer peer, final PeerStatus status) {
    if (storageClient.getFinalizedEpoch().isGreaterThanOrEqualTo(status.getFinalizedEpoch())) {
      return SafeFuture.completedFuture(PeerSyncResult.SUCCESSFUL_SYNC);
    } else {
      LOG.debug(
          "Disconnecting from peer ({}) due to inaccurate advertised finalized block at {}",
          peer,
          status.getFinalizedEpoch());
      disconnectFromPeer(peer);
      return SafeFuture.completedFuture(PeerSyncResult.FAULTY_ADVERTISEMENT);
    }
  }

  private RequestContext createRequestContext(final UInt64 startSlot, final PeerStatus status) {

    final UInt64 diff = status.getHeadSlot().minusMinZero(startSlot).plus(UInt64.ONE);
    final UInt64 requestCount = diff.min(MAX_BLOCK_BY_RANGE_REQUEST_SIZE);

    if (blobsSidecarsAreRequired(startSlot, requestCount)) {
      return new RequestContext(requestCount.min(MAX_REQUEST_BLOBS_SIDECARS), true);
    }

    return new RequestContext(requestCount, false);
  }

  private boolean blobsSidecarsAreRequired(final UInt64 startSlot, final UInt64 requestCount) {
    final UInt64 requestEndSlot =
        startSlot.plus(requestCount.min(MAX_REQUEST_BLOBS_SIDECARS)).minusMinZero(1);
    return blobsSidecarManager.isStorageOfBlobsSidecarRequired(requestEndSlot);
  }

  private static class RequestContext {
    private final UInt64 count;
    private final boolean blobsSidecarsRequired;

    private RequestContext(final UInt64 count, final boolean blobsSidecarsRequired) {
      this.count = count;
      this.blobsSidecarsRequired = blobsSidecarsRequired;
    }

    public UInt64 getCount() {
      return count;
    }

    public boolean areBlobsSidecarsRequired() {
      return blobsSidecarsRequired;
    }
  }

  private SafeFuture<Void> storeBlobsSidecarAndImportBlock(
      final SignedBeaconBlock block, final BlobsSidecar blobsSidecar) {
    return storeBlobsSidecar(blobsSidecar).thenCompose(__ -> importBlock(block));
  }

  private SafeFuture<Void> importBlock(final SignedBeaconBlock block) {
    if (stopped.get()) {
      throw new CancellationException("Peer sync was cancelled");
    }
    return blockImporter
        .importBlock(block)
        .thenAccept(
            (result) -> {
              LOG.trace("Block import result for block at {}: {}", block.getSlot(), result);
              if (!result.isSuccessful()) {
                blockImportFailureResult.inc();
                throw new FailedBlockImportException(block, result);
              } else {
                blockImportSuccessResult.inc();
              }
            });
  }

  private SafeFuture<Void> storeBlobsSidecar(final BlobsSidecar blobsSidecar) {
    if (stopped.get()) {
      throw new CancellationException("Peer sync was cancelled");
    }
    return SafeFuture.fromRunnable(
            () -> blobsSidecarManager.storeUnconfirmedBlobsSidecar(blobsSidecar))
        .whenSuccess(
            () -> {
              LOG.trace(
                  "Blobs sidecar stored for slot {} with block root: {}",
                  blobsSidecar.getBeaconBlockSlot(),
                  blobsSidecar.getBeaconBlockRoot());
              blobsSidecarImportSuccessResult.inc();
            })
        .whenException(
            throwable -> {
              LOG.trace(
                  String.format(
                      "Error while storing blobs sidecar for slot %s with block root: %s",
                      blobsSidecar.getBeaconBlockSlot(), blobsSidecar.getBeaconBlockRoot()),
                  throwable);
              blobsSidecarImportFailureResult.inc();
            });
  }

  private void disconnectFromPeer(final Eth2Peer peer) {
    peer.disconnectCleanly(DisconnectReason.REMOTE_FAULT).ifExceptionGetsHereRaiseABug();
  }

  public UInt64 getStartingSlot() {
    return startingSlot;
  }
}
