/*
 * Copyright (c) 1997-2017 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */
package com.finsent.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * @author Dmytro.Sheyko
 */
public class InitializerCollector
{
    public static final int IMMEDIATE = Integer.MIN_VALUE;

    private List<Envelope<IInitializer>> envelopes_ = new ArrayList<>();
    private volatile boolean ready_ = false;

    public void registerInitializer(IInitializer initializer, int order)
    {
        Objects.requireNonNull(initializer, "initializer");
        if (order == IMMEDIATE)
        {
            runInitializer(initializer);
        }
        else
        {
            synchronized (this)
            {
                if (envelopes_ != null)
                {
                    envelopes_.add(newEnvelope(initializer, order));
                    initializer = null; // suppress running initializer immediately
                }
            }
            if (initializer != null)
            {
                runInitializer(initializer);
            }
        }
    }

    private static Envelope<IInitializer> newEnvelope(IInitializer initializer, int order)
    {
        return new Envelope<IInitializer>(order, initializer);
    }

    public void startInThreadInitializers()
    {
        List<Runnable> tasks = new ArrayList<>();
        synchronized (this)
        {
            if (envelopes_ != null)
            {
                for (Envelope<IInitializer> envelope : envelopes_)
                {
                    IInitializer initializer = envelope.getContent();
                    if (IInThreadInitializer.isInThread(initializer))
                    {
                        InitializerTask task = new InitializerTask(initializer);
                        tasks.add(task);
                        envelope.setContent(task);
                    }
                }
            }
        }
        new Thread("GlobalSystem initializers thread")
        {
            @Override
            public void run()
            {
                for (Runnable task : tasks)
                {
                    try
                    {
                        task.run();
                    }
                    catch (Throwable e)
                    {
                        onUncaughtException(e);
                    }
                }
            }
        }.start();
    }

    @SuppressWarnings("unchecked")
    private static <T> Envelope<T>[] toArray(Collection<Envelope<T>> collection)
    {
        return collection.toArray(new Envelope[0]);
    }

    public void makeReady()
    {
        Envelope<IInitializer>[] envelopes = null;
        synchronized (this)
        {
            if (envelopes_ != null)
            {
                envelopes = toArray(envelopes_);
                envelopes_ = null; // make it ready and release references to initializers in order to avoid memory leak
                notifyAll();
                ready_ = true;
            }
        }
        // run initializers outside synch block in order to avoid deadlocks
        if (envelopes != null)
        {
            Arrays.sort(envelopes); // stable sort
            for (Envelope<IInitializer> envelope : envelopes)
            {
                runInitializer(envelope.getContent());
            }
        }
    }

    public boolean isReady()
    {
        return ready_;
    }

    public void waitForReady() throws InterruptedException
    {
        synchronized (this)
        {
            while (envelopes_ != null)
            {
                wait();
            }
        }
    }

    private void runInitializer(IInitializer initializer)
    {
        assert !Thread.holdsLock(this);
        try
        {
            initializer.initialize();
        }
        catch (Throwable e)
        {
            onUncaughtException(e);
        }
    }

    private static void onUncaughtException(Throwable e)
    {
        Thread t = Thread.currentThread();
        t.getUncaughtExceptionHandler().uncaughtException(t, e);
    }

    static class Envelope<T> implements Comparable<Envelope<?>>
    {
        private final int order_;
        private T content_;

        Envelope(int order, T content)
        {
            order_ = order;
            content_ = content;
        }

        public T getContent()
        {
            return content_;
        }

        public void setContent(T content)
        {
            content_ = content;
        }

        public int getOrder()
        {
            return order_;
        }

        @Override
        public int compareTo(Envelope<?> o)
        {
            return Integer.compare(order_, o.order_);
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof Envelope)
            {
                Envelope<?> that = (Envelope<?>) o;
                return this.order_ == that.order_;
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return order_;
        }

        @Override
        public String toString()
        {
            return "Envelope{" +
                "content_=" + content_.toString() +
                ", order_=" + order_ +
                '}';
        }
    }
}
