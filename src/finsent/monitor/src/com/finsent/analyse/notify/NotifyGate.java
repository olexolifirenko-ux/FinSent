package com.finsent.analyse.notify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Times;

/**
 * The notification threshold gate. Pure: the caller injects {@code now} so the decision is
 * deterministic and testable. As an <b>event monitor</b> it fires on <b>materiality</b>, not on a
 * directional bet -- a material event with an unclear (neutral) lean still alerts. Notifiable when
 * both hold:
 * <ol>
 *   <li>its {@code impact_tier} (materiality) ranks at or above {@code minImpactTier}; and</li>
 *   <li>at least one resonant article was published within {@code newsAgeMinutes} of {@code now}
 *       (bypassed by {@code skipAgeCheck}, e.g. a manual re-analysis).</li>
 * </ol>
 */
public final class NotifyGate
{
    private NotifyGate()
    {
    }

    public static boolean shouldNotify(ObjectNode predRecord, List<ObjectNode> resonant,
                                       String minImpactTier, int newsAgeMinutes,
                                       Instant now, boolean skipAgeCheck)
    {
        boolean notify;
        if (predRecord == null)
        {
            notify = false;
        }
        else
        {
            int minTierVal = ImpactTier.order(minImpactTier, 2);
            int tierVal = ImpactTier.order(predRecord.path("impact_tier").asText("noise"), 0);
            boolean meetsThreshold = tierVal >= minTierVal;
            notify = meetsThreshold && (skipAgeCheck || hasFreshArticle(resonant, now, newsAgeMinutes));
        }
        return notify;
    }

    private static boolean hasFreshArticle(List<ObjectNode> resonant, Instant now, int newsAgeMinutes)
    {
        Instant cutoff = now.minus(newsAgeMinutes, ChronoUnit.MINUTES);
        boolean fresh = false;
        for (ObjectNode article : resonant)
        {
            if (isFresh(article, cutoff))
            {
                fresh = true;
                break;
            }
        }
        return fresh;
    }

    private static boolean isFresh(ObjectNode article, Instant cutoff)
    {
        boolean fresh = false;
        String published = article.path("publishedAt").asText("");
        if (!published.isEmpty())
        {
            try
            {
                fresh = !Times.parseIso(published).isBefore(cutoff);
            }
            catch (java.time.format.DateTimeParseException unparseable)
            {
                // Tolerated: a malformed timestamp simply doesn't count as fresh, as in Python.
                fresh = false;
            }
        }
        return fresh;
    }
}
