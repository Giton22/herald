package dev.hermeskotlin.android.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Invalidates the watch's browsing context; foreground status checks recover missed deliveries. */
class WatchProfileNotifier(
    context: Context,
    private val profiles: ProfileStore,
    private val gateways: GatewayRepository,
    private val scope: CoroutineScope,
) {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)

    fun start() {
        scope.launch {
            var previous: Pair<String?, String?>? = null
            merge(profiles.changes.map { Unit }, gateways.list.map { it.currentUrl }.distinctUntilChanged().map { Unit })
                .collect {
                    try {
                        val gateway = gateways.current()
                        val current = gateway?.url to gateway?.let { profiles.get(it.gatewayUrl) }
                        if (current == previous) return@collect
                        previous = current
                        for (node in nodes.connectedNodes.await()) {
                            try {
                                messages.sendMessage(node.id, WearPaths.PROFILE_CHANGED, ByteArray(0)).await()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The next foreground check reads the authoritative context.
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // No background retry: status is checked whenever the watch opens.
                    }
                }
        }
    }
}
