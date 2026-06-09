/*
 * Copyright (c) 2006 InfoReach, Inc. All Rights Reserved.
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

import java.io.Serializable;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * @author Alexander Dolgin
 */
public class Pair<F,S> implements Serializable
{
    private static final long serialVersionUID = -4141289225410621713L;

    private F first_;
    private S second_;
    
    public Pair()
    {
    }
    
    public Pair(F first, S second)
    {
        first_ = first;
        second_ = second;
    }

    public static <F, S> Pair<F,S> pair(F first, S second)
    {
        return new Pair<>(first, second);
    }

    public F getFirst()
    {
        return first_;
    }

    public void setFirst(F first)
    {
        first_ = first;
    }
    
    public S getSecond()
    {
        return second_;
    }

    public void setSecond(S second)
    {
        second_ = second;
    }

    public void accept(BiConsumer<F, S> consumer)
    {
        consumer.accept(first_, second_);
    }

    public Pair transformFirst(UnaryOperator<F> firstFn)
    {
        return new Pair(firstFn.apply(getFirst()), getSecond());
    }

    public Pair transformSecond(UnaryOperator<S> secondFn)
    {
        return new Pair(getFirst(), secondFn.apply(getSecond()));
    }

    public void ifFirstPresent(Consumer<? super F> consumer)
    {
        if (first_ != null)
            consumer.accept(first_);
    }

    public void ifSecondPresent(Consumer<? super S> consumer)
    {
        if (second_ != null)
            consumer.accept(second_);
    }

    public boolean equals(Object obj)
    {
        return obj instanceof Pair
            && Objects.equals(first_, ((Pair)obj).first_)
            && Objects.equals(second_, ((Pair)obj).second_);
    }

    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((first_ == null)
            ? 0
            : first_.hashCode());
        result = prime * result + ((second_ == null)
            ? 0
            : second_.hashCode());
        return result;
    }

    public String toString()
    {
        return "Pair ["+String.valueOf(first_)+", "+String.valueOf(second_)+"]";
    }
}
