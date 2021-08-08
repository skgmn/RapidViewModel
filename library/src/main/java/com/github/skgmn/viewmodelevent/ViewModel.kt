package com.github.skgmn.viewmodelevent

import androidx.lifecycle.ViewModel
import java.util.*

open class ViewModel : ViewModel() {
    private val deliveries by lazy(LazyThreadSafetyMode.NONE) {
        IdentityHashMap<Event<*>, Delivery<*>>()
    }

    protected fun <T : Any> event(): Event<T> {
        val delivery = Delivery<T>()
        val event = Event(delivery)
        deliveries[event] = delivery
        return event
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> Event<T>.post(event: T) {
        (deliveries[this] as? Delivery<T>)?.post(event)
            ?: throw RuntimeException("Illegal usage")
    }
}