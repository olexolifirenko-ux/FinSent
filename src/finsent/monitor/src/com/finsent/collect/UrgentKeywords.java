package com.finsent.collect;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword detection for the urgent pipeline (ports Python {@code collect.RISK_SIGNALS}/
 * {@code detect_risks}, {@code urgent.URGENT_BULLISH_KEYWORDS}/{@code _is_urgent_worthy}, and
 * {@code shared._kw_match}). An article is urgent-worthy when its title+description matches any
 * risk signal or bullish keyword. Short tokens (&le; 4 chars, e.g. {@code "war"}) match as whole
 * words; longer tokens match as substrings.
 */
public final class UrgentKeywords
{
    private static final int WHOLE_WORD_MAX_LEN = 4;

    private static final Map<String, List<String>> RISK_SIGNALS = Map.of(
            "REGULATORY CRACKDOWN", List.of(
                    "crypto ban", "sec charges", "enforcement action", "cease and desist",
                    "crypto regulation", "congress vote", "crypto bill fail",
                    "consent order", "fined", "indicted"),
            "EXCHANGE / CUSTODIAN RISK", List.of(
                    "exchange collapse", "exchange hack", "withdrawal freeze",
                    "insolvency", "bankruptcy", "chapter 11", "default", "contagion", "bank run"),
            "NETWORK / PROTOCOL RISK", List.of(
                    "hack", "exploit", "rug pull", "51% attack", "bridge exploit",
                    "smart contract bug", "protocol vulnerability", "drained"),
            "MINER SELL PRESSURE", List.of(
                    "miner selling", "miner sell", "miner capitulation", "hashrate drop",
                    "mining difficulty down", "miner revenue", "miner outflow",
                    "sold bitcoin", "dumped bitcoin"),
            "MACRO HEADWIND", List.of(
                    "rate hike", "fed tightening", "dollar surges", "dollar strengthens",
                    "risk-off", "equity sell-off", "recession fears", "credit crunch", "liquidity crunch"),
            "WHALE / INSTITUTIONAL OUTFLOW", List.of(
                    "etf outflow", "btc outflow", "whale sell", "large transfer",
                    "exchange inflow spike", "grayscale selling", "ark sells", "institutional selling"),
            "MARKET STRESS", List.of(
                    "crash", "plunge", "liquidation", "long squeeze", "short squeeze",
                    "flash crash", "market rout", "panic sell", "capitulation", "extreme fear"),
            "GEOPOLITICAL", List.of(
                    "war", "conflict", "sanctions", "tariff", "trade war",
                    "geopolitical tension", "embargo", "military",
                    "invasion", "airstrike", "nuclear", "blockade", "ceasefire", "peace deal"));

    private static final List<String> BULLISH = List.of(
            "etf inflow", "etf approval", "spot etf",
            "rate cut", "fed easing", "dovish",
            "legal tender", "strategic reserve", "bitcoin reserve",
            "institutional buy", "treasury buy");

    private UrgentKeywords()
    {
    }

    /** True when the title+description matches any risk signal or bullish keyword. */
    public static boolean isUrgentWorthy(String title, String description)
    {
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        return detectRisks(text) || matchesBullish(text);
    }

    /** True when any risk-signal keyword matches the (already lower-cased) text. */
    static boolean detectRisks(String textLower)
    {
        boolean matched = false;
        for (List<String> keywords : RISK_SIGNALS.values())
        {
            if (anyMatch(keywords, textLower))
            {
                matched = true;
                break;
            }
        }
        return matched;
    }

    private static boolean matchesBullish(String textLower)
    {
        boolean matched = false;
        for (String keyword : BULLISH)
        {
            if (textLower.contains(keyword))
            {
                matched = true;
                break;
            }
        }
        return matched;
    }

    private static boolean anyMatch(List<String> keywords, String textLower)
    {
        boolean matched = false;
        for (String keyword : keywords)
        {
            if (kwMatch(keyword, textLower))
            {
                matched = true;
                break;
            }
        }
        return matched;
    }

    /** Whole-word match for short tokens (&le; 4 chars), substring for longer ones. */
    static boolean kwMatch(String keyword, String textLower)
    {
        boolean matched;
        if (keyword.length() <= WHOLE_WORD_MAX_LEN)
        {
            matched = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(textLower).find();
        }
        else
        {
            matched = textLower.contains(keyword);
        }
        return matched;
    }
}
