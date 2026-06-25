package com.finsent.core.event;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import com.finsent.util.GlobalSystem;

/**
 * Typed publish/subscribe bus with asynchronous delivery on a single daemon thread.
 * This is the link the collector uses to notify the analyser of freshly collected data
 * (the Java replacement for the Python {@code collection_done} {@code threading.Event}):
 * publishing never blocks the collector, and listeners run off the dispatch thread so a
 * slow analysis cannot stall collection.
 *
 * <p>Listeners are matched by the exact runtime class of the published event. A listener
 * that throws is logged and isolated &mdash; it never stops the dispatch thread or other
 * listeners.
 */
public final class EventBus implements IEventPublisher
{
    private static final String NAME = "EventBus";

    private final ConcurrentHashMap<Class<?>, List<IEventListener<?>>> listeners_ = new ConcurrentHashMap<>();
    private final BlockingQueue<Object> queue_ = new LinkedBlockingQueue<>();
    private final Thread dispatcher_;
    private volatile boolean running_ = true;

    public EventBus()
    {
        dispatcher_ = new Thread(this::dispatchLoop, "FS-EventBus");
        dispatcher_.setDaemon(true);
        dispatcher_.start();
    }

    /** Register a listener for events of the given type. */
    public <E> void subscribe(Class<E> eventType, IEventListener<E> listener)
    {
        listeners_.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Publish an event for asynchronous delivery. */
    @Override
    public void publish(Object event)
    {
        if (running_)
        {
            queue_.add(event);
        }
    }

    /** Stop the dispatch thread (does not wait for queued events). */
    public void shutdown()
    {
        running_ = false;
        dispatcher_.interrupt();
    }

    private void dispatchLoop()
    {
        while (running_)
        {
            deliverNext();
        }
    }

    private void deliverNext()
    {
        try
        {
            deliver(queue_.take());
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private void deliver(Object event)
    {
        List<IEventListener<?>> registered = listeners_.get(event.getClass());
        if (registered != null)
        {
            for (IEventListener<?> listener : registered)
            {
                invoke((IEventListener<Object>) listener, event);
            }
        }
    }

    private void invoke(IEventListener<Object> listener, Object event)
    {
        try
        {
            listener.onEvent(event);
        }
        catch (RuntimeException listenerFailed)
        {
            GlobalSystem.error().writes(NAME, "Listener failed for " + event.getClass().getName(), listenerFailed);
        }
    }
}
