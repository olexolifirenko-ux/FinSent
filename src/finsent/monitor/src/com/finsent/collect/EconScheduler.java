package com.finsent.collect;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.finsent.core.Times;
import com.finsent.directory.DirectorySystem;
import com.finsent.util.GlobalSystem;
import com.finsent.util.IUninitializer;

/**
 * Timer-driven resolver for scheduled economic releases (#21) -- entirely off the collection cycle. A
 * single daemon scheduler arms, once at startup and again at each 00:00 UTC, the releases listed in that
 * day's schedule ({@code data/<date>/econ_schedule_<date>.json}); when the file is absent it does nothing
 * until the next day. Arming joins each release with its static {@link EconEventDef} (from
 * {@code cfg/econ_definitions.json}) into a complete {@link EconEvent} <i>now</i> (so a consensus edited
 * before the arm is captured) and schedules a one-shot task at the release time. That release task polls
 * BLS via {@link FSCollector#tryResolveEcon} every {@code urgentPollInSec}, rescheduling itself until the
 * fresh print lands or the {@code econPollCap} elapses -- so BLS is touched only in the minutes around a
 * known release, never on every cycle. A release already past its cap at startup is skipped (a missed
 * real-time alert stays missed); one inside its window fires immediately.
 */
public final class EconScheduler implements IUninitializer
{
    private static final String NAME = "EconScheduler";
    private static final String SCHEDULE_PREFIX = "econ_schedule_";

    private final FSCollector collector_;
    private final Path dataDir_;
    private final ScheduledExecutorService scheduler_;

    public EconScheduler(FSCollector collector, Path dataDir)
    {
        collector_ = collector;
        dataDir_ = dataDir;
        scheduler_ = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "FS-Econ-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Arm today's releases now, then re-arm once per day at 00:00 UTC. */
    public void start()
    {
        armDay(Instant.now());
        long toMidnight = secondsUntilNextUtcMidnight(Instant.now());
        scheduler_.scheduleAtFixedRate(() -> armDay(Instant.now()), toMidnight, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    @Override
    public void uninitialize()
    {
        scheduler_.shutdownNow();
    }

    /**
     * Manually fetch a scheduled release's BLS actual on demand (the {@code collect econ} command): load the
     * day's schedule + the static catalog, join the named event, recover the day so an already-stored actual
     * is not re-fetched, and fetch-and-store it (no publish &mdash; analysis is left to {@code anal econ} so a
     * back-dated catch-up does not fire a stale alert). {@code day} defaults to today when blank. Returns a
     * status line. Runs on the caller's thread.
     */
    public String resolveNow(String day, String eventName)
    {
        String resolveDay = (day == null || day.isEmpty()) ? Times.dayOf(Times.formatUtcIso(Instant.now())) : day;
        Map<String, EconEventDef> defs = EconDefinitionsConfig.load(
                DirectorySystem.resolveToFile(collector_.config().econDefinitionsFile()));
        EconRelease release = findByName(EconScheduleConfig.load(scheduleFile(resolveDay)), eventName);
        String result;
        if (release == null)
        {
            result = "No release named '" + eventName + "' in the schedule for " + resolveDay + ".";
        }
        else if (!defs.containsKey(eventName))
        {
            result = "No definition for '" + eventName + "' in the econ_definitions catalog.";
        }
        else
        {
            result = fetch(resolveDay, eventName, merge(defs.get(eventName), release));
        }
        return result;
    }

    /** Fetch-and-store the actual (no publish), reporting whether it was already stored, newly fetched, or unavailable. */
    private String fetch(String day, String eventName, EconEvent event)
    {
        collector_.recoverDay(day); // so an already-stored actual is seen and not re-fetched
        boolean already = collector_.econ().resolved(day, eventName);
        boolean resolved = collector_.tryResolveEcon(event, false);
        String result;
        if (already)
        {
            result = eventName + " already fetched for " + day + " -- run `anal econ " + day + " " + eventName + "` to analyse.";
        }
        else if (resolved)
        {
            result = "Fetched " + eventName + " for " + day + " -- run `anal econ " + day + " " + eventName + "` to analyse.";
        }
        else
        {
            result = "Could not fetch " + eventName + " for " + day + " -- no fresh BLS print yet (or fetch failed).";
        }
        return result;
    }

    private static EconRelease findByName(List<EconRelease> releases, String eventName)
    {
        EconRelease match = null;
        for (EconRelease release : releases)
        {
            if (release.name().equals(eventName))
            {
                match = release;
            }
        }
        return match;
    }

    /** Load the day's schedule + the static catalog, join, and arm a release task per due/future release. */
    private void armDay(Instant now)
    {
        try
        {
            String day = Times.dayOf(Times.formatUtcIso(now));
            Map<String, EconEventDef> defs = EconDefinitionsConfig.load(
                    DirectorySystem.resolveToFile(collector_.config().econDefinitionsFile()));
            List<EconRelease> releases = EconScheduleConfig.load(scheduleFile(day));
            int armed = 0;
            for (EconRelease release : releases)
            {
                if (armRelease(release, defs, now))
                {
                    armed++;
                }
            }
            if (!releases.isEmpty())
            {
                GlobalSystem.info().writes(NAME, "Econ schedule " + day + ": armed " + armed + "/" + releases.size() + " release(s).");
            }
        }
        catch (RuntimeException armFailed)
        {
            // Never let a bad schedule kill the daily re-arm; the next day tries again.
            GlobalSystem.warning().writes(NAME, "Failed to arm econ schedule", armFailed);
        }
    }

    /** Join the release with its definition and schedule its task, unless it is past its cap (skipped). */
    private boolean armRelease(EconRelease release, Map<String, EconEventDef> defs, Instant now)
    {
        boolean armed = false;
        EconEventDef def = defs.get(release.name());
        if (def == null)
        {
            GlobalSystem.warning().writes(NAME, "No definition for scheduled event '" + release.name() + "' -- skipped.");
        }
        else
        {
            long delay = armDelaySeconds(release.release(), now, collector_.config().econPollCapMinutes());
            if (delay >= 0)
            {
                EconEvent event = merge(def, release);
                scheduler_.schedule(() -> pollRelease(event), delay, TimeUnit.SECONDS);
                armed = true;
            }
        }
        return armed;
    }

    /**
     * Seconds to wait before firing a release: the gap when it is still future, {@code 0} when {@code now}
     * is already in the resolution window {@code [release, release+cap]} (startup catch), or {@code -1}
     * when it is past the cap and should be skipped.
     */
    static long armDelaySeconds(Instant release, Instant now, int capMinutes)
    {
        long delay;
        if (now.isBefore(release))
        {
            delay = Duration.between(now, release).getSeconds();
        }
        else if (now.isBefore(release.plus(capMinutes, ChronoUnit.MINUTES)))
        {
            delay = 0;
        }
        else
        {
            delay = -1;
        }
        return delay;
    }

    /** Poll BLS for the fresh print; reschedule every {@code urgentPollInSec} until resolved or the cap elapses. */
    private void pollRelease(EconEvent event)
    {
        boolean resolved = collector_.tryResolveEcon(event, true);
        if (!resolved)
        {
            if (Instant.now().isBefore(event.release().plus(collector_.config().econPollCapMinutes(), ChronoUnit.MINUTES)))
            {
                scheduler_.schedule(() -> pollRelease(event), collector_.config().urgentPollInSec(), TimeUnit.SECONDS);
            }
            else
            {
                GlobalSystem.warning().writes(NAME, "Gave up on " + event.name() + " -- no fresh print within the poll cap.");
            }
        }
    }

    private File scheduleFile(String day)
    {
        return new File(new File(dataDir_.toFile(), day), SCHEDULE_PREFIX + day + ".json");
    }

    private static EconEvent merge(EconEventDef def, EconRelease release)
    {
        return new EconEvent(def.name(), release.release(), release.consensus(), def.unit(), def.hotDirection(),
                def.inlineBand(), def.highBand(), def.series(), def.kind());
    }

    /** Seconds from {@code now} to the next 00:00 UTC (floored at 1s), for the daily re-arm. */
    static long secondsUntilNextUtcMidnight(Instant now)
    {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime nextMidnight = utc.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Math.max(1, Duration.between(utc, nextMidnight).getSeconds());
    }
}
