package com.finsent.trade.broker;

/**
 * The execution seam between the trader's decisions and a venue. v1 ships only {@link PaperBroker}
 * (simulated fills against the collected BTC price); a live adapter (e.g. a WhiteBIT USD-M
 * perpetual-futures broker over its signed Trading API) implements the same interface and is
 * selected by config, so the trader's logic is unchanged. Opening or closing a long/short maps to a
 * market {@code BUY}/{@code SELL} of a BTC quantity; the broker reports the resulting {@link Fill}.
 */
public interface IBroker
{
    /**
     * Execute a market order of {@code qty} BTC at the prevailing {@code price}; returns the fill (a
     * live broker reports the venue's actual fill price, which may differ from {@code price}).
     *
     * @throws BrokerException if the venue rejects the order or it cannot be placed (paper never throws).
     */
    Fill marketOrder(OrderSide side, double qty, double price) throws BrokerException;

    /**
     * Execute the entry market order and, when {@code protectiveStopPrice > 0}, attach a venue-resting
     * protective stop (a position-linked OTO bracket) at that trigger price in the same order &mdash; so the
     * position is never open at the venue without at least its initial stop, even if this process dies. The
     * default ignores the stop and places a plain order (the paper broker has no resting orders; a live broker
     * overrides this to attach the bracket).
     *
     * @throws BrokerException if the venue rejects the order or it cannot be placed.
     */
    default Fill marketOrder(OrderSide side, double qty, double price, double protectiveStopPrice)
            throws BrokerException
    {
        return marketOrder(side, qty, price);
    }

    /**
     * Cancel any venue-resting protective stop the trader left on this market &mdash; called before a
     * deliberate close so a closed position can never leave a naked stop that later flips it. The default is a
     * no-op (the paper broker has no resting orders); a live broker cancels its conditional stop orders.
     *
     * @throws BrokerException if the cancel cannot be placed.
     */
    default void cancelProtectiveStops() throws BrokerException
    {
        // No venue-resting orders to cancel (paper / default); a live broker overrides this.
    }

    /**
     * Move the venue-resting protective stop to a new trigger price as the app's trailing stop ratchets, so
     * the venue executes near the <i>trailed</i> level (not just the initial) and a crash gives back to the
     * trailed level. {@code closeSide} is the order side that closes the position (opposite the entry),
     * {@code qty} the held size. The default is a no-op (paper has no resting orders); a live broker
     * replaces its resting stop. Called only past a deadband, so the churn is bounded.
     *
     * @throws BrokerException if the stop cannot be replaced.
     */
    default void amendProtectiveStop(OrderSide closeSide, double qty, double triggerPrice) throws BrokerException
    {
        // No venue-resting orders to trail (paper / default); a live broker overrides this.
    }

    /** A short name for the venue (for logging / {@code trade status}). */
    String name();

    /**
     * The venue's authoritative open position for startup reconciliation. The paper broker has no
     * venue truth and returns {@link VenueState#untracked()} (the local book stays authoritative); a
     * live broker reads the venue and returns {@code flat()} or an {@code open(...)} state.
     *
     * @throws BrokerException if the venue cannot be read.
     */
    default VenueState venueState() throws BrokerException
    {
        return VenueState.untracked();
    }
}
