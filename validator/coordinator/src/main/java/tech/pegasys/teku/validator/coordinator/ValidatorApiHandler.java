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

package tech.pegasys.teku.validator.coordinator;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static tech.pegasys.teku.datastructures.util.AttestationUtil.get_attesting_indices;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_epoch_at_slot;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_start_slot_at_epoch;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.get_beacon_proposer_index;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.get_committee_count_per_slot;
import static tech.pegasys.teku.datastructures.util.CommitteeUtil.getAggregatorModulo;
import static tech.pegasys.teku.infrastructure.logging.ValidatorLogger.VALIDATOR_LOGGER;
import static tech.pegasys.teku.util.config.Constants.GENESIS_SLOT;
import static tech.pegasys.teku.util.config.Constants.MAX_VALIDATORS_PER_COMMITTEE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.EventBus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.core.CommitteeAssignmentUtil;
import tech.pegasys.teku.core.StateTransition;
import tech.pegasys.teku.core.exceptions.EpochProcessingException;
import tech.pegasys.teku.core.exceptions.SlotProcessingException;
import tech.pegasys.teku.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.datastructures.blocks.BeaconBlockAndState;
import tech.pegasys.teku.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.datastructures.operations.Attestation;
import tech.pegasys.teku.datastructures.operations.AttestationData;
import tech.pegasys.teku.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.datastructures.state.BeaconState;
import tech.pegasys.teku.datastructures.state.ForkInfo;
import tech.pegasys.teku.datastructures.util.AttestationUtil;
import tech.pegasys.teku.datastructures.util.CommitteeUtil;
import tech.pegasys.teku.datastructures.util.ValidatorsUtil;
import tech.pegasys.teku.datastructures.validator.SubnetSubscription;
import tech.pegasys.teku.infrastructure.async.ExceptionThrowingFunction;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.subnets.AttestationTopicSubscriber;
import tech.pegasys.teku.ssz.SSZTypes.Bitlist;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPool;
import tech.pegasys.teku.statetransition.attestation.AttestationManager;
import tech.pegasys.teku.statetransition.events.block.ProposedBlockEvent;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.sync.SyncState;
import tech.pegasys.teku.sync.SyncStateTracker;
import tech.pegasys.teku.util.config.Constants;
import tech.pegasys.teku.validator.api.NodeSyncingException;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.api.ValidatorDuties;

public class ValidatorApiHandler implements ValidatorApiChannel {
  private static final Logger LOG = LogManager.getLogger();
  private final CombinedChainDataClient combinedChainDataClient;
  private final SyncStateTracker syncStateTracker;
  private final StateTransition stateTransition;
  private final BlockFactory blockFactory;
  private final AggregatingAttestationPool attestationPool;
  private final AttestationManager attestationManager;
  private final AttestationTopicSubscriber attestationTopicSubscriber;
  private final EventBus eventBus;
  private final DutyMetrics dutyMetrics;

  public ValidatorApiHandler(
      final CombinedChainDataClient combinedChainDataClient,
      final SyncStateTracker syncStateTracker,
      final StateTransition stateTransition,
      final BlockFactory blockFactory,
      final AggregatingAttestationPool attestationPool,
      final AttestationManager attestationManager,
      final AttestationTopicSubscriber attestationTopicSubscriber,
      final EventBus eventBus,
      final DutyMetrics dutyMetrics) {
    this.combinedChainDataClient = combinedChainDataClient;
    this.syncStateTracker = syncStateTracker;
    this.stateTransition = stateTransition;
    this.blockFactory = blockFactory;
    this.attestationPool = attestationPool;
    this.attestationManager = attestationManager;
    this.attestationTopicSubscriber = attestationTopicSubscriber;
    this.eventBus = eventBus;
    this.dutyMetrics = dutyMetrics;
  }

  @Override
  public SafeFuture<Optional<ForkInfo>> getForkInfo() {
    return SafeFuture.completedFuture(
        combinedChainDataClient.getBestState().map(BeaconState::getForkInfo));
  }

  @Override
  public SafeFuture<Optional<List<ValidatorDuties>>> getDuties(
      final UInt64 epoch, final Collection<BLSPublicKey> publicKeys) {
    if (isSyncActive()) {
      return NodeSyncingException.failedFuture();
    }
    if (publicKeys.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(emptyList()));
    }
    final UInt64 slot = CommitteeUtil.getEarliestQueryableSlotForTargetEpoch(epoch);
    LOG.trace("Retrieving duties from epoch {} using state at slot {}", epoch, slot);
    return combinedChainDataClient
        .getLatestStateAtSlot(slot)
        .thenApply(
            optionalState ->
                optionalState
                    .map(state -> processSlots(state, slot))
                    .map(state -> getValidatorDutiesFromState(state, epoch, publicKeys)));
  }

  private BeaconState processSlots(final BeaconState startingState, final UInt64 targetSlot) {
    if (startingState.getSlot().compareTo(targetSlot) >= 0) {
      return startingState;
    }
    try {
      return stateTransition.process_slots(startingState, targetSlot);
    } catch (SlotProcessingException | EpochProcessingException e) {
      throw new IllegalStateException("Unable to process slots", e);
    }
  }

  @Override
  public SafeFuture<Optional<BeaconBlock>> createUnsignedBlock(
      final UInt64 slot, final BLSSignature randaoReveal, final Optional<Bytes32> graffiti) {
    if (isSyncActive()) {
      return NodeSyncingException.failedFuture();
    }
    return createFromBlockAndState(
        slot.minus(UInt64.ONE),
        blockAndState ->
            blockFactory.createUnsignedBlock(
                blockAndState.getState(), blockAndState.getBlock(), slot, randaoReveal, graffiti));
  }

  private <T> SafeFuture<Optional<T>> createFromBlockAndState(
      final UInt64 maximumSlot, final ExceptionThrowingFunction<BeaconBlockAndState, T> creator) {

    return combinedChainDataClient
        .getBlockAndStateInEffectAtSlot(maximumSlot)
        .thenApplyChecked(
            maybeBlockAndState -> {
              if (maybeBlockAndState.isEmpty()) {
                return Optional.empty();
              }
              return Optional.of(creator.apply(maybeBlockAndState.get()));
            });
  }

  @Override
  public SafeFuture<Optional<Attestation>> createUnsignedAttestation(
      final UInt64 slot, final int committeeIndex) {
    if (isSyncActive()) {
      return NodeSyncingException.failedFuture();
    }

    final UInt64 minQuerySlot = CommitteeUtil.getEarliestQueryableSlotForTargetSlot(slot);

    return combinedChainDataClient
        .getSignedBlockAndStateInEffectAtSlot(slot)
        .thenCompose(
            maybeBlockAndState -> {
              if (maybeBlockAndState.isEmpty()) {
                return SafeFuture.completedFuture(Optional.empty());
              }
              final SignedBlockAndState blockAndState = maybeBlockAndState.get();
              final BeaconBlock block = blockAndState.getBlock().getMessage();
              if (blockAndState.getSlot().compareTo(minQuerySlot) < 0) {
                // The current effective block is too far in the past - so roll the state
                // forward to the minimum epoch
                final UInt64 epoch = compute_epoch_at_slot(minQuerySlot);
                return combinedChainDataClient
                    .getCheckpointState(epoch, blockAndState)
                    .thenApply(
                        checkpointState ->
                            Optional.of(
                                createAttestation(
                                    block, checkpointState.getState(), slot, committeeIndex)));
              } else {
                final Attestation attestation =
                    createAttestation(block, blockAndState.getState(), slot, committeeIndex);
                return SafeFuture.completedFuture(Optional.of(attestation));
              }
            });
  }

  private Attestation createAttestation(
      final BeaconBlock block,
      final BeaconState state,
      final UInt64 slot,
      final int committeeIndex) {
    final int committeeCount =
        get_committee_count_per_slot(state, compute_epoch_at_slot(slot)).intValue();

    if (committeeIndex < 0 || committeeIndex >= committeeCount) {
      throw new IllegalArgumentException(
          "Invalid committee index "
              + committeeIndex
              + " - expected between 0 and "
              + (committeeCount - 1));
    }
    final UInt64 committeeIndexUnsigned = UInt64.valueOf(committeeIndex);
    final AttestationData attestationData =
        AttestationUtil.getGenericAttestationData(slot, state, block, committeeIndexUnsigned);
    final List<Integer> committee =
        CommitteeUtil.get_beacon_committee(state, slot, committeeIndexUnsigned);

    final Bitlist aggregationBits = new Bitlist(committee.size(), MAX_VALIDATORS_PER_COMMITTEE);
    return new Attestation(aggregationBits, attestationData, BLSSignature.empty());
  }

  @Override
  public SafeFuture<Optional<Attestation>> createAggregate(final Bytes32 attestationHashTreeRoot) {
    if (isSyncActive()) {
      return NodeSyncingException.failedFuture();
    }
    return SafeFuture.completedFuture(
        attestationPool
            .createAggregateFor(attestationHashTreeRoot)
            .map(ValidateableAttestation::getAttestation));
  }

  @Override
  public void subscribeToBeaconCommitteeForAggregation(
      final int committeeIndex, final UInt64 aggregationSlot) {
    attestationTopicSubscriber.subscribeToCommitteeForAggregation(committeeIndex, aggregationSlot);
  }

  @Override
  public void subscribeToPersistentSubnets(Set<SubnetSubscription> subnetSubscriptions) {
    attestationTopicSubscriber.subscribeToPersistentSubnets(subnetSubscriptions);
  }

  @Override
  public void sendSignedAttestation(
      final Attestation attestation, final Optional<Integer> expectedValidatorIndex) {
    attestationManager
        .onAttestation(ValidateableAttestation.fromAttestation(attestation))
        .finish(
            result -> {
              result.ifInvalid(
                  reason ->
                      VALIDATOR_LOGGER.producedInvalidAttestation(
                          attestation.getData().getSlot(),
                          getValidatorIndex(attestation),
                          expectedValidatorIndex,
                          reason));
              dutyMetrics.onAttestationPublished(attestation.getData().getSlot());
            },
            err ->
                LOG.error(
                    "Failed to send signed attestation for validator {}, slot {}, block {}",
                    getValidatorIndex(attestation),
                    attestation.getData().getSlot(),
                    attestation.getData().getBeacon_block_root()));
  }

  private int getValidatorIndex(final Attestation attestation) {
    return get_attesting_indices(
            combinedChainDataClient.getBestState().orElseThrow(),
            attestation.getData(),
            attestation.getAggregation_bits())
        .get(0);
  }

  @Override
  public void sendSignedAttestation(final Attestation attestation) {
    sendSignedAttestation(attestation, Optional.empty());
  }

  @Override
  public void sendAggregateAndProof(final SignedAggregateAndProof aggregateAndProof) {
    attestationManager
        .onAttestation(ValidateableAttestation.fromSignedAggregate(aggregateAndProof))
        .finish(
            result ->
                result.ifInvalid(
                    reason ->
                        VALIDATOR_LOGGER.producedInvalidAggregate(
                            aggregateAndProof.getMessage().getAggregate().getData().getSlot(),
                            reason)),
            err ->
                LOG.error(
                    "Failed to send aggregate for slot {}",
                    aggregateAndProof.getMessage().getAggregate().getData().getSlot()));
  }

  @Override
  public void sendSignedBlock(final SignedBeaconBlock block) {
    eventBus.post(new ProposedBlockEvent(block));
  }

  @VisibleForTesting
  boolean isSyncActive() {
    final SyncState syncState = syncStateTracker.getCurrentSyncState();
    return syncState.isStartingUp() || (syncState.isSyncing() && headBlockIsTooFarBehind());
  }

  private boolean headBlockIsTooFarBehind() {
    final UInt64 currentEpoch = combinedChainDataClient.getCurrentEpoch();
    final UInt64 headEpoch = combinedChainDataClient.getHeadEpoch();
    return headEpoch.plus(1).isLessThan(currentEpoch);
  }

  private List<ValidatorDuties> getValidatorDutiesFromState(
      final BeaconState state, final UInt64 epoch, final Collection<BLSPublicKey> publicKeys) {
    final Map<Integer, List<UInt64>> proposalSlotsByValidatorIndex =
        getBeaconProposalSlotsByValidatorIndex(state, epoch);
    return publicKeys.stream()
        .map(key -> getDutiesForValidator(key, state, epoch, proposalSlotsByValidatorIndex))
        .collect(toList());
  }

  private ValidatorDuties getDutiesForValidator(
      final BLSPublicKey key,
      final BeaconState state,
      final UInt64 epoch,
      final Map<Integer, List<UInt64>> proposalSlotsByValidatorIndex) {
    return ValidatorsUtil.getValidatorIndex(state, key)
        .map(
            index -> createValidatorDuties(proposalSlotsByValidatorIndex, key, state, epoch, index))
        .orElseGet(() -> ValidatorDuties.noDuties(key));
  }

  private ValidatorDuties createValidatorDuties(
      final Map<Integer, List<UInt64>> proposalSlotsByValidatorIndex,
      final BLSPublicKey key,
      final BeaconState state,
      final UInt64 epoch,
      final Integer validatorIndex) {
    final List<UInt64> proposerSlots =
        proposalSlotsByValidatorIndex.getOrDefault(validatorIndex, emptyList());
    return CommitteeAssignmentUtil.get_committee_assignment(state, epoch, validatorIndex)
        .map(
            committeeAssignment ->
                ValidatorDuties.withDuties(
                    key,
                    validatorIndex,
                    Math.toIntExact(committeeAssignment.getCommitteeIndex().longValue()),
                    committeeAssignment.getCommittee().indexOf(validatorIndex),
                    getAggregatorModulo(committeeAssignment.getCommittee().size()),
                    proposerSlots,
                    committeeAssignment.getSlot()))
        .orElseGet(() -> ValidatorDuties.noDuties(key));
  }

  private Map<Integer, List<UInt64>> getBeaconProposalSlotsByValidatorIndex(
      final BeaconState state, final UInt64 epoch) {
    final UInt64 epochStartSlot = compute_start_slot_at_epoch(epoch);
    // Don't calculate a proposer for the genesis slot
    final UInt64 startSlot = epochStartSlot.max(UInt64.valueOf(GENESIS_SLOT + 1));
    final UInt64 endSlot = epochStartSlot.plus(Constants.SLOTS_PER_EPOCH);
    final Map<Integer, List<UInt64>> proposalSlotsByValidatorIndex = new HashMap<>();
    for (UInt64 slot = startSlot; slot.compareTo(endSlot) < 0; slot = slot.plus(UInt64.ONE)) {
      final Integer proposer = get_beacon_proposer_index(state, slot);
      proposalSlotsByValidatorIndex.computeIfAbsent(proposer, key -> new ArrayList<>()).add(slot);
    }
    return proposalSlotsByValidatorIndex;
  }
}
