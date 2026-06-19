package com.finsent.trade.broker;

/**
 * An expected operational failure of a live broker: an order the venue rejected (insufficient
 * margin, bad size, missing permission), a network/auth error, or an open-position read that failed.
 * Checked so the {@link IBroker} contract forces callers to handle it &mdash; on an entry the trader
 * stays flat, on an exit it keeps the position and retries. The paper broker never throws it.
 */
public class BrokerException extends Exception
{
    public BrokerException(String message)
    {
        super(message);
    }

    public BrokerException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
