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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.peers.PeerStatus;
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseListener;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;

public class CommonAncestorTest extends AbstractSyncTest {

  @Test
  void shouldNotSearchCommonAncestorWithoutSufficientLocalData()
      throws ExecutionException, InterruptedException {
    final UInt64 firstNonFinalSlot = dataStructureUtil.randomUInt64();
    final UInt64 currentLocalHead =
        firstNonFinalSlot.plus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH.minus(1));
    final CommonAncestor commonAncestor = new CommonAncestor(storageClient);
    final PeerStatus status =
        withPeerHeadSlot(
            currentLocalHead,
            spec.computeEpochAtSlot(currentLocalHead),
            dataStructureUtil.randomBytes32());
    when(storageClient.getHeadSlot()).thenReturn(currentLocalHead);

    assertThat(
            commonAncestor.getCommonAncestor(peer, firstNonFinalSlot, status.getHeadSlot()).get())
        .isEqualTo(firstNonFinalSlot);
  }

  @Test
  void shouldNotSearchCommonAncestorWithoutSufficientRemoteData()
      throws ExecutionException, InterruptedException {
    final UInt64 firstNonFinalSlot = dataStructureUtil.randomUInt64();
    final UInt64 currentLocalHead =
        firstNonFinalSlot.plus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH);
    final UInt64 currentRemoteHead =
        firstNonFinalSlot.plus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH.minus(1));
    final CommonAncestor commonAncestor = new CommonAncestor(storageClient);
    final PeerStatus status =
        withPeerHeadSlot(
            currentRemoteHead,
            spec.computeEpochAtSlot(currentRemoteHead),
            dataStructureUtil.randomBytes32());
    when(storageClient.getHeadSlot()).thenReturn(currentLocalHead);

    assertThat(
            commonAncestor.getCommonAncestor(peer, firstNonFinalSlot, status.getHeadSlot()).get())
        .isEqualTo(firstNonFinalSlot);
  }

  @Test
  void shouldSearchCommonAncestorWithSufficientRemoteData()
      throws ExecutionException, InterruptedException {
    final UInt64 firstNonFinalSlot = dataStructureUtil.randomUInt64();
    final UInt64 currentLocalHead =
        firstNonFinalSlot.plus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH.times(10));
    final UInt64 currentRemoteHead =
        firstNonFinalSlot.plus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH.times(9));
    final CommonAncestor commonAncestor = new CommonAncestor(storageClient);
    final UInt64 syncStartSlot = currentRemoteHead.minus(CommonAncestor.OPTIMISTIC_HISTORY_LENGTH);
    final SafeFuture<Void> requestFuture1 = new SafeFuture<>();
    when(peer.requestBlocksByRange(eq(syncStartSlot), eq(CommonAncestor.BLOCK_COUNT), any()))
        .thenReturn(requestFuture1);
    final PeerStatus status =
        withPeerHeadSlot(
            currentRemoteHead,
            spec.computeEpochAtSlot(currentRemoteHead),
            dataStructureUtil.randomBytes32());
    when(storageClient.getHeadSlot()).thenReturn(currentLocalHead);
    when(storageClient.containsBlock(any())).thenReturn(true);

    SafeFuture<UInt64> futureSlot =
        commonAncestor.getCommonAncestor(peer, firstNonFinalSlot, status.getHeadSlot());

    verify(peer)
        .requestBlocksByRange(
            eq(syncStartSlot),
            eq(CommonAncestor.BLOCK_COUNT),
            blockResponseListenerArgumentCaptor.capture());

    assertThat(futureSlot.isDone()).isFalse();
    final RpcResponseListener<SignedBeaconBlock> responseListener =
        blockResponseListenerArgumentCaptor.getValue();
    respondWithBlocksAtSlots(responseListener, syncStartSlot, CommonAncestor.BLOCK_COUNT);
    requestFuture1.complete(null);
    // last received slot is the best slot
    assertThat(futureSlot.get()).isEqualTo(syncStartSlot.plus(CommonAncestor.BLOCK_COUNT).minus(1));
  }
}
