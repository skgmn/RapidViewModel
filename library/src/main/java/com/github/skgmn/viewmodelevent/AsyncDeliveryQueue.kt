package com.github.skgmn.viewmodelevent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicInteger

internal class AsyncDeliveryQueue<T>(
    private val deliveryMode: DeliveryMode
) : DeliveryQueue<T, suspend (AsyncDeliveryQueue.ReceiverState, T) -> Unit>(
    deliveryMode.extraBufferCapacity
) {
    override fun runConsumerLoop() {
        scope.launch {
            var prevReceiverState: ReceiverState? = null
            itemFlow.collect { item ->
                if (deliveryMode == DeliveryMode.LATEST) {
                    prevReceiverState?.tryCancel()
                }

                lateinit var receiverState: ReceiverState
                val job = launch(start = CoroutineStart.LAZY) {
                    passToReceiver(receiverState, item)
                }
                receiverState = ReceiverState(job)
                job.start()

                prevReceiverState = receiverState
            }
        }
    }

    private suspend fun passToReceiver(receiverState: ReceiverState, item: T) {
        try {
            coroutineScope {
                receiverFlow.collectLatest { receiver ->
                    if (receiver == null) {
                        return@collectLatest
                    }
                    try {
                        receiver(receiverState, item)
                        cancel(DeliveryCompletionException())
                    } catch (e: CancellationException) {
                        // receiver is cancelled
                    }
                }
            }
        } catch (e: CancellationException) {
            // canceled after successful event passing
        }
    }

    class ReceiverState(private val job: Job) {
        private val cancelState = AtomicInteger(RECEIVER_CANCELLABLE)

        fun tryCancel(): Boolean {
            return if (cancelState.compareAndSet(RECEIVER_CANCELLABLE, RECEIVER_CANCELED)) {
                job.cancel(LatestCancellationException())
                true
            } else {
                false
            }
        }

        fun trySetCancellable(cancellable: Boolean): Boolean {
            do {
                val curState = cancelState.get()
                if (curState == RECEIVER_CANCELED) {
                    return false
                }
                val nextState = if (cancellable) RECEIVER_CANCELLABLE else RECEIVER_NOT_CANCELLABLE
            } while (!cancelState.compareAndSet(curState, nextState))
            return true
        }
    }

    class DeliveryCompletionException : CancellationException()

    class LatestCancellationException : CancellationException()

    companion object {
        private const val RECEIVER_CANCELLABLE = 0
        private const val RECEIVER_NOT_CANCELLABLE = 1
        private const val RECEIVER_CANCELED = 2
    }
}