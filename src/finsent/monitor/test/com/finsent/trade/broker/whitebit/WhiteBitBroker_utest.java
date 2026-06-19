package com.finsent.trade.broker.whitebit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.junit.Test;

import com.finsent.core.Json;
import com.finsent.trade.Side;
import com.finsent.trade.broker.BrokerException;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.VenueState;

/**
 * Pins the live broker's pure parsing/formatting: the BTC amount precision (rounded down to the
 * market step), the open-positions -&gt; {@link VenueState} mapping used for reconciliation (flat,
 * long, short), and reading the venue's actual fill out of an order response (including failing when
 * the order was not filled).
 */
public class WhiteBitBroker_utest
{
    @Test
    public void formatsAmountToTheMarketStepRoundingDown()
    {
        assertEquals("0.0039", WhiteBitBroker.formatAmount(0.0039765));
        assertEquals("0.0001", WhiteBitBroker.formatAmount(0.00019));
        assertEquals("1.2345", WhiteBitBroker.formatAmount(1.23456));
    }

    @Test
    public void parsesAFlatVenueFromAnEmptyArray() throws JsonProcessingException
    {
        assertEquals(VenueState.Kind.FLAT, WhiteBitBroker.parsePositions(Json.parse("[]")).kind());
    }

    @Test
    public void parsesALongVenuePositionAndDerivesItsLeverage() throws JsonProcessingException
    {
        VenueState state = WhiteBitBroker.parsePositions(
                Json.parse("[{\"amount\":\"0.0001\",\"basePrice\":\"63043.72\",\"margin\":\"1.27\"}]"));
        assertEquals(VenueState.Kind.OPEN, state.kind());
        assertEquals(Side.LONG, state.side());
        assertEquals(0.0001, state.qty(), 1e-9);
        assertEquals(63043.72, state.entryPrice(), 1e-9);
        assertEquals(4.96, state.leverage(), 0.01); // 0.0001 * 63043.72 / 1.27 ~= 5x
    }

    @Test
    public void parsesAShortVenuePositionFromANegativeAmount() throws JsonProcessingException
    {
        VenueState state = WhiteBitBroker.parsePositions(
                Json.parse("[{\"amount\":\"-0.0030\",\"basePrice\":\"60000\"}]"));
        assertEquals(Side.SHORT, state.side());
        assertEquals(0.0030, state.qty(), 1e-9);
    }

    @Test
    public void readsTheActualFillFromAFilledOrder() throws BrokerException, JsonProcessingException
    {
        Fill fill = WhiteBitBroker.fillOf(
                Json.parse("{\"status\":\"FILLED\",\"dealStock\":\"0.0001\",\"dealMoney\":\"6.30\"}"),
                OrderSide.BUY, "0.0001");
        assertEquals(63000.0, fill.price(), 1e-9); // 6.30 / 0.0001
        assertEquals(0.0001, fill.qty(), 1e-9);
    }

    @Test
    public void throwsWhenTheOrderWasNotFilled()
    {
        assertThrows(BrokerException.class, () -> WhiteBitBroker.fillOf(
                Json.parse("{\"status\":\"REJECTED\",\"dealStock\":\"0\",\"dealMoney\":\"0\"}"),
                OrderSide.SELL, "0.0001"));
    }
}
