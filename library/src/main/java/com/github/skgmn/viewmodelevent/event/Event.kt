package com.github.skgmn.viewmodelevent.event

import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.whenStarted
import com.github.skgmn.viewmodelevent.*
import com.github.skgmn.viewmodelevent.DeliveryQueue
import com.github.skgmn.viewmodelevent.LifecycleBinder
import com.github.skgmn.viewmodelevent.RetainedViewId
import java.util.*
import kotlin.collections.set

open class Event<T : Any> internal constructor(protected val delivery: Delivery<T>) {
    @GuardedBy("bindings")
    private val bindings = WeakHashMap<LifecycleOwner, LifecycleBinder>()

    private val viewIdCallback = object : RetainedViewId.Callback {
        override fun onViewIdInvalid(id: RetainedViewId) {
            synchronized(delivery.queues) {
                delivery.queues.remove(id)?.dispose()
            }
        }
    }

    @MainThread
    internal fun replaceHandler(
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        backpressure: DeliveryMode,
        handler: (T) -> Unit
    ) {
        val viewId = ViewModelProvider(viewModelStoreOwner).get(RetainedViewId::class.java)
        synchronized(delivery.queues) {
            delivery.queues[viewId]?.setReceiver(null)
        }
        synchronized(bindings) {
            bindings.remove(lifecycleOwner)?.unbind()

            val binding = LifecycleBinder(onReady = {
                val queue = synchronized(delivery.queues) {
                    delivery.queues[viewId] ?: DeliveryQueue<T>(false, backpressure).also {
                        delivery.queues[viewId] = it
                        it.runConsumerLoop()
                        viewId.addCallback(viewIdCallback)
                    }
                }
                queue.setReceiver { event ->
                    lifecycleOwner.lifecycle.whenStarted {
                        handler(event)
                    }
                }
            }, onUnbind = {
                synchronized(delivery.queues) {
                    delivery.queues[viewId]?.setReceiver(null)
                }
                synchronized(bindings) {
                    bindings -= lifecycleOwner
                }
            })
            bindings[lifecycleOwner] = binding
            binding.bindTo(lifecycleOwner)
        }
    }
}