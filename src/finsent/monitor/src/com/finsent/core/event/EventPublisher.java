package com.finsent.core.event;

/**
 * The emit-only view of the {@link EventBus}. Components that <i>produce</i> events (the collector, the
 * analyser, the FastMove poller) depend on this narrow interface rather than the full bus, so they can
 * publish but cannot subscribe -- the application owns the bus and wires all subscriptions. Keeps the
 * producers decoupled from the bus's lifecycle and from each other.
 */
public interface EventPublisher
{
    /** Publish an event for asynchronous delivery to its registered listeners. */
    void publish(Object event);
}
