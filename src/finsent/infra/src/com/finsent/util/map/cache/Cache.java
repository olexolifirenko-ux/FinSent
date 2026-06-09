/*
 * Copyright (c) InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.map.cache;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A lazily-populated cache backed by a caller-supplied map: {@link #get(Object)}
 * returns the stored value for a key, computing and storing it via the data
 * provider on first access.
 *
 * <p>This is a minimal variant of the full InfoReach {@code Cache} (which used
 * an {@code IMapFactory} and a richer collection API); it carries only the
 * factory + data-provider constructor and {@code get(K)} used by the tests.
 */
public class Cache<K, V>
{
    private final Map<K, V> backingMap_;
    private final Function<? super K, ? extends V> dataProvider_;

    public Cache(Supplier<? extends Map<K, V>> mapFactory, Function<? super K, ? extends V> dataProvider)
    {
        backingMap_ = mapFactory.get();
        dataProvider_ = dataProvider;
    }

    public V get(K key)
    {
        return backingMap_.computeIfAbsent(key, dataProvider_);
    }
}
