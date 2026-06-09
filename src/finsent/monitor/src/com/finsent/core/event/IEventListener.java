package com.finsent.core.event;

/**
 * Listener for events of a given type published on the {@link EventBus}.
 *
 * @param <E> the event type this listener consumes.
 */
@FunctionalInterface
public interface IEventListener<E>
{
    void onEvent(E event);
}
