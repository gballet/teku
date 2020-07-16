package tech.pegasys.teku.phase1.simulator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import tech.pegasys.teku.phase1.integration.datastructures.FullAttestation
import tech.pegasys.teku.phase1.onotole.phase1.GENESIS_SLOT
import tech.pegasys.teku.phase1.onotole.phase1.Slot

class DelayedAttestationsProcessor(eventBus: SendChannel<Eth2Event>) : Eth2Actor(eventBus) {

  private var recentAttestations = listOf<FullAttestation>()
  private var recentSlot = GENESIS_SLOT

  override suspend fun dispatchImpl(event: Eth2Event, scope: CoroutineScope) {
    when (event) {
      is NewAttestations -> onNewAttestations(event.attestations)
      is NewSlot -> onNewSlot(event.slot)
    }
  }

  private suspend fun onNewSlot(slot: Slot) {
    this.recentSlot = slot
    publish(PrevSlotAttestationsPublished(recentAttestations))
  }

  private suspend fun onNewAttestations(attestations: List<FullAttestation>) {
    this.recentAttestations = attestations

    // parking attestations is a slot terminal event
    publish(SlotTerminal(recentSlot))
  }
}
