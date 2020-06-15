/*
 * Copyright 2019 ConsenSys AG.
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

package tech.pegasys.teku.datastructures.phase1.state;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.UnsignedLong;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import jdk.jfr.Label;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.ssz.SSZ;
import tech.pegasys.teku.datastructures.blocks.BeaconBlockHeader;
import tech.pegasys.teku.datastructures.blocks.Eth1Data;
import tech.pegasys.teku.datastructures.phase1.config.ConstantsPhase1;
import tech.pegasys.teku.datastructures.phase1.shard.ShardState;
import tech.pegasys.teku.datastructures.state.BeaconStateCache;
import tech.pegasys.teku.datastructures.state.Checkpoint;
import tech.pegasys.teku.datastructures.state.Fork;
import tech.pegasys.teku.datastructures.state.TransitionCaches;
import tech.pegasys.teku.datastructures.util.SimpleOffsetSerializer;
import tech.pegasys.teku.ssz.SSZTypes.Bitvector;
import tech.pegasys.teku.ssz.SSZTypes.SSZList;
import tech.pegasys.teku.ssz.SSZTypes.SSZVector;
import tech.pegasys.teku.ssz.backing.ContainerViewRead;
import tech.pegasys.teku.ssz.backing.ViewRead;
import tech.pegasys.teku.ssz.backing.cache.IntCache;
import tech.pegasys.teku.ssz.backing.tree.TreeNode;
import tech.pegasys.teku.ssz.backing.type.CompositeViewType;
import tech.pegasys.teku.ssz.backing.type.ContainerViewType;
import tech.pegasys.teku.ssz.backing.view.ContainerViewReadImpl;
import tech.pegasys.teku.ssz.sos.SimpleOffsetSerializable;
import tech.pegasys.teku.util.config.Constants;

public class BeaconStatePhase1Impl extends ContainerViewReadImpl
    implements BeaconStatePhase1, BeaconStateCache {

  // The number of SimpleSerialize basic types in this SSZ Container/POJO.
  public static final int SSZ_FIELD_COUNT = 17;

  @Label("sos-ignore")
  private final TransitionCaches transitionCaches;

  // Versioning
  @SuppressWarnings("unused")
  private final UnsignedLong genesis_time = null;

  @SuppressWarnings("unused")
  private final Bytes32 genesis_validators_root = null;

  @SuppressWarnings("unused")
  private final UnsignedLong slot = null;

  @SuppressWarnings("unused")
  private final Fork fork = null; // For versioning hard forks

  // History
  @SuppressWarnings("unused")
  private final BeaconBlockHeader latest_block_header = null;

  @SuppressWarnings("unused")
  private final SSZVector<Bytes32> block_roots =
      SSZVector.createMutable(
          Bytes32.class,
          Constants.SLOTS_PER_HISTORICAL_ROOT); // Vector of length SLOTS_PER_HISTORICAL_ROOT

  @SuppressWarnings("unused")
  private final SSZVector<Bytes32> state_roots =
      SSZVector.createMutable(
          Bytes32.class,
          Constants.SLOTS_PER_HISTORICAL_ROOT); // Vector of length SLOTS_PER_HISTORICAL_ROOT

  @SuppressWarnings("unused")
  private final SSZList<Bytes32> historical_roots =
      SSZList.createMutable(
          Bytes32.class, Constants.HISTORICAL_ROOTS_LIMIT); // Bounded by HISTORICAL_ROOTS_LIMIT

  // Ethereum 1.0 chain data
  @SuppressWarnings("unused")
  private final Eth1Data eth1_data = null;

  @SuppressWarnings("unused")
  private final SSZList<Eth1Data> eth1_data_votes =
      SSZList.createMutable(
          Eth1Data.class,
          Constants.EPOCHS_PER_ETH1_VOTING_PERIOD
              * Constants.SLOTS_PER_EPOCH); // List Bounded by EPOCHS_PER_ETH1_VOTING_PERIOD *
  // SLOTS_PER_PERIOD

  @SuppressWarnings("unused")
  private final UnsignedLong eth1_deposit_index = null;

  // Validator registry
  @SuppressWarnings("unused")
  private final SSZList<ValidatorPhase1> validators =
      SSZList.createMutable(
          ValidatorPhase1.class,
          Constants.VALIDATOR_REGISTRY_LIMIT); // List Bounded by VALIDATOR_REGISTRY_LIMIT

  @SuppressWarnings("unused")
  private final SSZList<UnsignedLong> balances =
      SSZList.createMutable(
          UnsignedLong.class,
          Constants.VALIDATOR_REGISTRY_LIMIT); // List Bounded by VALIDATOR_REGISTRY_LIMIT

  @SuppressWarnings("unused")
  private final SSZVector<Bytes32> randao_mixes =
      SSZVector.createMutable(
          Bytes32.class,
          Constants.EPOCHS_PER_HISTORICAL_VECTOR); // Vector of length EPOCHS_PER_HISTORICAL_VECTOR

  // Slashings
  @SuppressWarnings("unused")
  private final SSZVector<UnsignedLong> slashings =
      SSZVector.createMutable(
          UnsignedLong.class,
          Constants.EPOCHS_PER_SLASHINGS_VECTOR); // Vector of length EPOCHS_PER_SLASHINGS_VECTOR

  // Attestations
  @SuppressWarnings("unused")
  private final SSZList<PendingAttestationPhase1> previous_epoch_attestations =
      SSZList.createMutable(
          PendingAttestationPhase1.class,
          Constants.MAX_ATTESTATIONS
              * Constants.SLOTS_PER_EPOCH); // List bounded by MAX_ATTESTATIONS * SLOTS_PER_EPOCH

  @SuppressWarnings("unused")
  private final SSZList<PendingAttestationPhase1> current_epoch_attestations =
      SSZList.createMutable(
          PendingAttestationPhase1.class,
          Constants.MAX_ATTESTATIONS
              * Constants.SLOTS_PER_EPOCH); // List bounded by MAX_ATTESTATIONS * SLOTS_PER_EPOCH

  // Finality
  @SuppressWarnings("unused")
  private final Bitvector justification_bits =
      new Bitvector(
          Constants.JUSTIFICATION_BITS_LENGTH); // Bitvector bounded by JUSTIFICATION_BITS_LENGTH

  @SuppressWarnings("unused")
  private final Checkpoint previous_justified_checkpoint = null;

  @SuppressWarnings("unused")
  private final Checkpoint current_justified_checkpoint = null;

  @SuppressWarnings("unused")
  private final Checkpoint finalized_checkpoint = null;

  // Phase 1
  @SuppressWarnings("unused")
  private final UnsignedLong current_epoch_start_shard = null;

  @SuppressWarnings("unused")
  private final SSZList<ShardState> shard_states =
      SSZList.createMutable(
          ShardState.class, ConstantsPhase1.MAX_SHARDS); //  List bounded by MAX_SHARDS

  @SuppressWarnings("unused")
  private final SSZList<Byte> online_countdown =
      SSZList.createMutable(
          Byte.class,
          Constants.VALIDATOR_REGISTRY_LIMIT); //  List bounded by VALIDATOR_REGISTRY_LIMIT

  @SuppressWarnings("unused")
  private final CompactCommittee current_light_committee = null;

  @SuppressWarnings("unused")
  private final CompactCommittee next_light_committee = null;

  @SuppressWarnings("unused")
  private final SSZVector<ExposedValidatorIndices> exposed_derived_secrets =
      SSZVector.createMutable(
          ExposedValidatorIndices.class,
          (int)
              ConstantsPhase1.EARLY_DERIVED_SECRET_PENALTY_MAX_FUTURE_EPOCHS); // Vector bounded by
  // EARLY_DERIVED_SECRET_PENALTY_MAX_FUTURE_EPOCHS

  @Label("sos-ignore")
  private SSZList<ValidatorPhase1> validatorsCache;

  @Label("sos-ignore")
  private SSZList<UnsignedLong> balancesCache;

  @Label("sos-ignore")
  private SSZVector<Bytes32> blockRootsCache;

  @Label("sos-ignore")
  private SSZVector<Bytes32> stateRootsCache;

  @Label("sos-ignore")
  private SSZList<Bytes32> historicalRootsCache;

  @Label("sos-ignore")
  private SSZList<Eth1Data> eth1DataVotesCache;

  @Label("sos-ignore")
  private SSZVector<Bytes32> randaoMixesCache;

  @Label("sos-ignore")
  private SSZList<PendingAttestationPhase1> previousEpochAttestationsCache;

  @Label("sos-ignore")
  private SSZList<PendingAttestationPhase1> currentEpochAttestationsCache;

  @Label("sos-ignore")
  private SSZList<ShardState> shardStatesCache;

  @Label("sos-ignore")
  private SSZList<Byte> onlineCountdownCache;

  @Label("sos-ignore")
  private SSZVector<ExposedValidatorIndices> exposedDerivedSecretsCache;

  public BeaconStatePhase1Impl() {
    super(BeaconStatePhase1.getSSZType());
    transitionCaches = TransitionCaches.createNewEmpty();
  }

  BeaconStatePhase1Impl(
      CompositeViewType type,
      TreeNode backingNode,
      IntCache<ViewRead> cache,
      TransitionCaches transitionCaches) {
    super(type, backingNode, cache);
    this.transitionCaches = transitionCaches;
  }

  BeaconStatePhase1Impl(ContainerViewType<? extends ContainerViewRead> type, TreeNode backingNode) {
    super(type, backingNode);
    transitionCaches = TransitionCaches.createNewEmpty();
  }

  public BeaconStatePhase1Impl(
      // Versioning
      UnsignedLong genesis_time,
      Bytes32 genesis_validators_root,
      UnsignedLong slot,
      Fork fork,

      // History
      BeaconBlockHeader latest_block_header,
      SSZVector<Bytes32> block_roots,
      SSZVector<Bytes32> state_roots,
      SSZList<Bytes32> historical_roots,

      // Eth1
      Eth1Data eth1_data,
      SSZList<Eth1Data> eth1_data_votes,
      UnsignedLong eth1_deposit_index,

      // Registry
      SSZList<? extends ValidatorPhase1> validators,
      SSZList<UnsignedLong> balances,

      // Randomness
      SSZVector<Bytes32> randao_mixes,

      // Slashings
      SSZVector<UnsignedLong> slashings,

      // Attestations
      SSZList<PendingAttestationPhase1> previous_epoch_attestations,
      SSZList<PendingAttestationPhase1> current_epoch_attestations,

      // Finality
      Bitvector justification_bits,
      Checkpoint previous_justified_checkpoint,
      Checkpoint current_justified_checkpoint,
      Checkpoint finalized_checkpoint,

      // Phase 1
      UnsignedLong current_epoch_start_shard,
      SSZList<ShardState> shard_states,
      SSZList<Byte> online_countdown,
      CompactCommittee current_light_committee,
      CompactCommittee next_light_committee,
      SSZVector<ExposedValidatorIndices> exposed_derived_secrets) {

    super(
        BeaconStatePhase1.getSSZType(),
        BeaconStatePhase1.create(
                genesis_time,
                genesis_validators_root,
                slot,
                fork,
                latest_block_header,
                block_roots,
                state_roots,
                historical_roots,
                eth1_data,
                eth1_data_votes,
                eth1_deposit_index,
                validators,
                balances,
                randao_mixes,
                slashings,
                previous_epoch_attestations,
                current_epoch_attestations,
                justification_bits,
                previous_justified_checkpoint,
                current_justified_checkpoint,
                finalized_checkpoint,
                current_epoch_start_shard,
                shard_states,
                online_countdown,
                current_light_committee,
                next_light_committee,
                exposed_derived_secrets)
            .getBackingNode());

    transitionCaches = TransitionCaches.createNewEmpty();
  }

  @Override
  public <E1 extends Exception, E2 extends Exception, E3 extends Exception>
      BeaconStatePhase1 updated(Mutator<E1, E2, E3> mutator) throws E1, E2, E3 {
    MutableBeaconStatePhase1 writableCopy = createWritableCopyPriv();
    mutator.mutate(writableCopy);
    return writableCopy.commitChanges();
  }

  @Override
  public int getSSZFieldCount() {
    return SSZ_FIELD_COUNT
        + getFork().getSSZFieldCount()
        + getLatest_block_header().getSSZFieldCount()
        + getEth1_data().getSSZFieldCount()
        + getPrevious_justified_checkpoint().getSSZFieldCount()
        + getCurrent_justified_checkpoint().getSSZFieldCount()
        + getFinalized_checkpoint().getSSZFieldCount()
        + getCurrent_light_committee().getSSZFieldCount()
        + getNext_light_committee().getSSZFieldCount();
  }

  @Override
  public List<Bytes> get_fixed_parts() {
    List<Bytes> ret = new ArrayList<>();
    ret.addAll(
        List.of(
            SSZ.encodeUInt64(getGenesis_time().longValue()),
            getGenesis_validators_root(),
            SSZ.encodeUInt64(getSlot().longValue()),
            SimpleOffsetSerializer.serialize(getFork()),
            SimpleOffsetSerializer.serialize(getLatest_block_header()),
            SSZ.encode(writer -> writer.writeFixedBytesVector(getBlock_roots().asList())),
            SSZ.encode(writer -> writer.writeFixedBytesVector(getState_roots().asList())),
            Bytes.EMPTY,
            SimpleOffsetSerializer.serialize(getEth1_data()),
            Bytes.EMPTY,
            SSZ.encodeUInt64(getEth1_deposit_index().longValue()),
            Bytes.EMPTY,
            Bytes.EMPTY,
            SSZ.encode(writer -> writer.writeFixedBytesVector(getRandao_mixes().asList())),
            SSZ.encode(
                writer ->
                    writer.writeFixedBytesVector(
                        getSlashings().stream()
                            .map(slashing -> SSZ.encodeUInt64(slashing.longValue()))
                            .collect(Collectors.toList()))),
            Bytes.EMPTY,
            Bytes.EMPTY,
            getJustification_bits().serialize(),
            SimpleOffsetSerializer.serialize(getPrevious_justified_checkpoint()),
            SimpleOffsetSerializer.serialize(getCurrent_justified_checkpoint()),
            SimpleOffsetSerializer.serialize(getFinalized_checkpoint()),
            SSZ.encodeUInt64(getCurrent_epoch_start_shard().longValue()),
            Bytes.EMPTY,
            Bytes.EMPTY,
            Bytes.EMPTY,
            Bytes.EMPTY));
    ret.addAll(
        getExposed_derived_secrets().stream()
            .map(SimpleOffsetSerializable::get_fixed_parts)
            .flatMap(Collection::stream)
            .collect(Collectors.toList()));
    return ret;
  }

  @Override
  public List<Bytes> get_variable_parts() {
    List<Bytes> variablePartsList =
        new ArrayList<>(
            List.of(
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY));
    variablePartsList.add(
        SSZ.encode(writer -> writer.writeFixedBytesVector(getHistorical_roots().asList())));
    variablePartsList.add(Bytes.EMPTY);
    variablePartsList.add(SimpleOffsetSerializer.serializeFixedCompositeList(getEth1_data_votes()));
    variablePartsList.add(Bytes.EMPTY);
    variablePartsList.add(SimpleOffsetSerializer.serializeFixedCompositeList(getValidators()));
    // TODO The below lines are a hack while Tuweni SSZ/SOS is being upgraded.
    variablePartsList.add(
        Bytes.fromHexString(
            getBalances().stream()
                .map(value -> SSZ.encodeUInt64(value.longValue()).toHexString().substring(2))
                .collect(Collectors.joining())));
    variablePartsList.addAll(List.of(Bytes.EMPTY, Bytes.EMPTY));
    variablePartsList.add(
        SimpleOffsetSerializer.serializeVariableCompositeList(getPrevious_epoch_attestations()));
    variablePartsList.add(
        SimpleOffsetSerializer.serializeVariableCompositeList(getCurrent_epoch_attestations()));
    variablePartsList.addAll(List.of(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY));
    variablePartsList.addAll(
        Collections.nCopies(getPrevious_justified_checkpoint().getSSZFieldCount(), Bytes.EMPTY));
    variablePartsList.addAll(
        Collections.nCopies(getCurrent_justified_checkpoint().getSSZFieldCount(), Bytes.EMPTY));
    variablePartsList.addAll(
        Collections.nCopies(getFinalized_checkpoint().getSSZFieldCount(), Bytes.EMPTY));
    variablePartsList.add(Bytes.EMPTY);
    variablePartsList.add(SimpleOffsetSerializer.serializeFixedCompositeList(getShard_states()));
    variablePartsList.add(
        Bytes.fromHexString(
            getOnline_countdown().stream()
                .map(value -> SSZ.encodeUInt8(value & 0xFF).toHexString().substring(2))
                .collect(Collectors.joining())));
    variablePartsList.add(SimpleOffsetSerializer.serialize(getCurrent_light_committee()));
    variablePartsList.add(SimpleOffsetSerializer.serialize(getNext_light_committee()));
    variablePartsList.addAll(
        getExposed_derived_secrets().stream()
            .map(SimpleOffsetSerializer::serialize)
            .collect(Collectors.toList()));
    return variablePartsList;
  }

  @Override
  public int hashCode() {
    return hashCode(this);
  }

  static int hashCode(BeaconStatePhase1 state) {
    return state.hashTreeRoot().slice(0, 4).toInt();
  }

  @Override
  public boolean equals(Object obj) {
    return equals(this, obj);
  }

  static boolean equals(BeaconStatePhase1 state, Object obj) {
    if (Objects.isNull(obj)) {
      return false;
    }

    if (state == obj) {
      return true;
    }

    if (!(obj instanceof BeaconStatePhase1)) {
      return false;
    }

    BeaconStatePhase1 other = (BeaconStatePhase1) obj;
    return state.hashTreeRoot().equals(other.hashTreeRoot());
  }

  @Override
  public Bytes32 hash_tree_root() {
    return hashTreeRoot();
  }

  @Override
  public TransitionCaches getTransitionCaches() {
    return transitionCaches;
  }

  static String toString(BeaconStatePhase1 state) {
    return MoreObjects.toStringHelper(state)
        .add("genesis_time", state.getGenesis_time())
        .add("genesis_validators_root", state.getGenesis_validators_root())
        .add("slot", state.getSlot())
        .add("fork", state.getFork())
        .add("latest_block_header", state.getLatest_block_header())
        .add("block_roots", state.getBlock_roots())
        .add("state_roots", state.getState_roots())
        .add("historical_roots", state.getHistorical_roots())
        .add("eth1_data", state.getEth1_data())
        .add("eth1_data_votes", state.getEth1_data_votes())
        .add("eth1_deposit_index", state.getEth1_deposit_index())
        .add("validators", state.getValidators())
        .add("balances", state.getBalances())
        .add("randao_mixes", state.getRandao_mixes())
        .add("slashings", state.getSlashings())
        .add("previous_epoch_attestations", state.getPrevious_epoch_attestations())
        .add("current_epoch_attestations", state.getCurrent_epoch_attestations())
        .add("justification_bits", state.getJustification_bits())
        .add("previous_justified_checkpoint", state.getPrevious_justified_checkpoint())
        .add("current_justified_checkpoint", state.getCurrent_justified_checkpoint())
        .add("finalized_checkpoint", state.getFinalized_checkpoint())
        .add("current_epoch_start_shard", state.getCurrent_epoch_start_shard())
        .add("shard_states", state.getShard_states())
        .add("online_countdown", state.getOnline_countdown())
        .add("current_light_committee", state.getCurrent_light_committee())
        .add("next_light_committee", state.getNext_light_committee())
        .add("exposed_derived_secrets", state.getExposed_derived_secrets())
        .toString();
  }

  @Override
  public String toString() {
    return toString(this);
  }

  private MutableBeaconStatePhase1 createWritableCopyPriv() {
    return new MutableBeaconStatePhase1Impl(this);
  }

  @Override
  public SSZList<ValidatorPhase1> getValidators() {
    return validatorsCache != null
        ? validatorsCache
        : (validatorsCache = BeaconStatePhase1.super.getValidators());
  }

  @Override
  public SSZList<UnsignedLong> getBalances() {
    return balancesCache != null
        ? balancesCache
        : (balancesCache = BeaconStatePhase1.super.getBalances());
  }

  @Override
  public SSZVector<Bytes32> getBlock_roots() {
    return blockRootsCache != null
        ? blockRootsCache
        : (blockRootsCache = BeaconStatePhase1.super.getBlock_roots());
  }

  @Override
  public SSZVector<Bytes32> getState_roots() {
    return stateRootsCache != null
        ? stateRootsCache
        : (stateRootsCache = BeaconStatePhase1.super.getState_roots());
  }

  @Override
  public SSZList<Bytes32> getHistorical_roots() {
    return historicalRootsCache != null
        ? historicalRootsCache
        : (historicalRootsCache = BeaconStatePhase1.super.getHistorical_roots());
  }

  @Override
  public SSZList<Eth1Data> getEth1_data_votes() {
    return eth1DataVotesCache != null
        ? eth1DataVotesCache
        : (eth1DataVotesCache = BeaconStatePhase1.super.getEth1_data_votes());
  }

  @Override
  public SSZVector<Bytes32> getRandao_mixes() {
    return randaoMixesCache != null
        ? randaoMixesCache
        : (randaoMixesCache = BeaconStatePhase1.super.getRandao_mixes());
  }

  @Override
  public SSZList<PendingAttestationPhase1> getPrevious_epoch_attestations() {
    return previousEpochAttestationsCache != null
        ? previousEpochAttestationsCache
        : (previousEpochAttestationsCache =
            BeaconStatePhase1.super.getPrevious_epoch_attestations());
  }

  @Override
  public SSZList<PendingAttestationPhase1> getCurrent_epoch_attestations() {
    return currentEpochAttestationsCache != null
        ? currentEpochAttestationsCache
        : (currentEpochAttestationsCache = BeaconStatePhase1.super.getCurrent_epoch_attestations());
  }

  @Override
  public SSZList<ShardState> getShard_states() {
    return shardStatesCache != null
        ? shardStatesCache
        : (shardStatesCache = BeaconStatePhase1.super.getShard_states());
  }

  @Override
  public SSZList<Byte> getOnline_countdown() {
    return onlineCountdownCache != null
        ? onlineCountdownCache
        : (onlineCountdownCache = BeaconStatePhase1.super.getOnline_countdown());
  }

  @Override
  public SSZVector<ExposedValidatorIndices> getExposed_derived_secrets() {
    return exposedDerivedSecretsCache != null
        ? exposedDerivedSecretsCache
        : (exposedDerivedSecretsCache = BeaconStatePhase1.super.getExposed_derived_secrets());
  }
}
