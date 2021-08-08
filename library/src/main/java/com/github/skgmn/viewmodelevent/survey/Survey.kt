package com.github.skgmn.viewmodelevent.survey

import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.whenStarted
import com.github.skgmn.viewmodelevent.DeliveryMode
import com.github.skgmn.viewmodelevent.DeliveryQueue
import com.github.skgmn.viewmodelevent.LifecycleBinder
import com.github.skgmn.viewmodelevent.RetainedViewId
import java.util.*
import kotlin.collections.set

open class Survey<Q : Any, A : Any> internal constructor(protected val poll: Poll<Q, A>) {
    @GuardedBy("binders")
    private val binders = WeakHashMap<LifecycleOwner, LifecycleBinder>()

    private val viewIdCallback = object : RetainedViewId.Callback {
        override fun onViewIdInvalid(id: RetainedViewId) {
            synchronized(poll.queues) {
                poll.queues.remove(id)?.dispose()
            }
        }
    }

    @MainThread
    internal fun replaceReplier(
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        deliveryMode: DeliveryMode,
        replier: suspend (Q) -> A
    ) {
        val viewId = ViewModelProvider(viewModelStoreOwner).get(RetainedViewId::class.java)
        synchronized(poll.queues) {
            poll.queues[viewId]?.setReceiver(null)
        }
        synchronized(binders) {
            binders.remove(lifecycleOwner)?.unbind()

            val binder = LifecycleBinder(onReady = {
                val queue = synchronized(poll.queues) {
                    poll.queues[viewId] ?: DeliveryQueue<Questionnaire<Q, A>>(deliveryMode).also {
                        poll.queues[viewId] = it
                        it.runConsumerLoop()
                        viewId.addCallback(viewIdCallback)
                    }
                }
                queue.setReceiver { questionnaire ->
                    lifecycleOwner.lifecycle.whenStarted {
                        questionnaire.answer(replier(questionnaire.question))
                    }
                }
            }, onUnbind = {
                synchronized(poll.queues) {
                    poll.queues[viewId]?.setReceiver(null)
                }
                synchronized(binders) {
                    binders -= lifecycleOwner
                }
            })
            binders[lifecycleOwner] = binder
            binder.bindTo(lifecycleOwner)
        }
    }
}