/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.forkchoice;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ONE;

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.core.ChainBuilder;
import tech.pegasys.teku.core.ChainBuilder.BlockOptions;
import tech.pegasys.teku.core.StateTransition;
import tech.pegasys.teku.core.results.BlockImportResult;
import tech.pegasys.teku.datastructures.blocks.Eth1Data;
import tech.pegasys.teku.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.storage.api.TrackingReorgEventChannel.ReorgEvent;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;
import tech.pegasys.teku.util.config.StateStorageMode;

class ForkChoiceTest {

  private final StateTransition stateTransition = new StateTransition();
  private final StorageSystem storageSystem =
      InMemoryStorageSystemBuilder.buildDefault(StateStorageMode.PRUNE);
  private final ChainBuilder chainBuilder = storageSystem.chainBuilder();
  private final SignedBlockAndState genesis = chainBuilder.generateGenesis();
  private final RecentChainData recentChainData = storageSystem.recentChainData();

  private final ForkChoice forkChoice =
      new ForkChoice(new SyncForkChoiceExecutor(), recentChainData, stateTransition);

  @BeforeEach
  public void setup() {
    final SafeFuture<Void> initialized = recentChainData.initializeFromGenesis(genesis.getState());
    assertThat(initialized).isCompleted();

    storageSystem.chainUpdater().setTime(UInt64.valueOf(492849242972424L));
  }

  @Test
  void shouldTriggerReorgWhenEmptyHeadSlotFilled() {
    // Run fork choice with an empty slot 1
    forkChoice.processHead(ONE);

    // Then rerun with a filled slot 1
    final SignedBlockAndState slot1Block = storageSystem.chainUpdater().advanceChain(ONE);
    forkChoice.processHead(ONE);

    final List<ReorgEvent> reorgEvents = storageSystem.reorgEventChannel().getReorgEvents();
    assertThat(reorgEvents).hasSize(1);
    assertThat(reorgEvents.get(0).getBestSlot()).isEqualTo(ONE);
    assertThat(reorgEvents.get(0).getBestBlockRoot()).isEqualTo(slot1Block.getRoot());
  }

  @Test
  void onBlock_shouldImmediatelyMakeChildOfCurrentHeadTheNewHead() {
    final SignedBlockAndState blockAndState = chainBuilder.generateBlockAtSlot(ONE);
    final SafeFuture<BlockImportResult> importResult =
        forkChoice.onBlock(blockAndState.getBlock(), Optional.of(genesis.getState()));
    assertBlockImportedSuccessfully(importResult);

    assertThat(recentChainData.getHeadBlock()).contains(blockAndState.getBlock());
    assertThat(recentChainData.getHeadSlot()).isEqualTo(blockAndState.getSlot());
  }

  @Test
  void onBlock_shouldTriggerReorgWhenSelectingChildOfChainHeadWhenForkChoiceSlotHasAdvanced() {
    // Advance the current head
    final UInt64 nodeSlot = UInt64.valueOf(5);
    forkChoice.processHead(nodeSlot);

    final SignedBlockAndState blockAndState = chainBuilder.generateBlockAtSlot(ONE);
    final SafeFuture<BlockImportResult> importResult =
        forkChoice.onBlock(blockAndState.getBlock(), Optional.of(genesis.getState()));
    assertBlockImportedSuccessfully(importResult);

    assertThat(recentChainData.getHeadBlock()).contains(blockAndState.getBlock());
    assertThat(recentChainData.getHeadSlot()).isEqualTo(blockAndState.getSlot());
    assertThat(storageSystem.reorgEventChannel().getReorgEvents())
        .contains(new ReorgEvent(blockAndState.getRoot(), blockAndState.getSlot()));
  }

  @Test
  void onBlock_shouldUpdateVotesBasedOnAttestationsInBlocks() {
    final ChainBuilder forkChain = chainBuilder.fork();
    final SignedBlockAndState forkBlock =
        forkChain.generateBlockAtSlot(
            ONE,
            BlockOptions.create()
                .setEth1Data(new Eth1Data(Bytes32.ZERO, UInt64.valueOf(6), Bytes32.ZERO)));
    final List<SignedBlockAndState> betterChain = chainBuilder.generateBlocksUpToSlot(3);

    importBlock(forkChain, forkBlock);
    // Should automatically follow the fork
    assertThat(recentChainData.getBestBlockRoot()).contains(forkBlock.getRoot());

    betterChain.forEach(blockAndState -> importBlock(chainBuilder, blockAndState));
    final BlockOptions options = BlockOptions.create();
    chainBuilder
        .streamValidAttestationsForBlockAtSlot(UInt64.valueOf(4))
        .limit(3)
        .forEach(options::addAttestation);
    final SignedBlockAndState blockWithAttestations =
        chainBuilder.generateBlockAtSlot(UInt64.valueOf(4), options);
    importBlock(chainBuilder, blockWithAttestations);
    // Haven't run fork choice so won't have re-orged yet
    assertThat(recentChainData.getBestBlockRoot()).contains(forkBlock.getRoot());

    // Should have processed the attestations and switched to this fork
    forkChoice.processHead(blockWithAttestations.getSlot());
    assertThat(recentChainData.getBestBlockRoot()).contains(blockWithAttestations.getRoot());
  }

  private void assertBlockImportedSuccessfully(final SafeFuture<BlockImportResult> importResult) {
    assertThat(importResult).isCompleted();
    final BlockImportResult result = importResult.join();
    assertThat(result.isSuccessful()).describedAs(result.toString()).isTrue();
  }

  private void importBlock(final ChainBuilder chainBuilder, final SignedBlockAndState block) {
    final SafeFuture<BlockImportResult> result =
        forkChoice.onBlock(
            block.getBlock(), Optional.of(chainBuilder.getStateAtSlot(block.getSlot().minus(ONE))));
    assertBlockImportedSuccessfully(result);
  }
}
