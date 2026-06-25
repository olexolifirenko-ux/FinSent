package com.finsent.collect;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.core.Config;
import com.finsent.core.event.EventBus;
import com.finsent.util.xml.XMLData;

/**
 * Verifies the FastMove poller's runtime on/off gate (behind the {@code fastmove on|off|status} command):
 * it starts in the configured paused/running state and {@code pause()}/{@code resume()} flip
 * {@code status()}. The polling thread is never started here -- only the pause flag is exercised (no live
 * price sampling, no network).
 */
public class FastMovePoller_utest
{
    private Path dir_;
    private EventBus bus_;
    private FSCollector collector_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-fastmove-utest");
        bus_ = new EventBus();
        collector_ = new FSCollector(config(), dir_, bus_);
    }

    @After
    public void tearDown() throws IOException
    {
        collector_.shutdown();
        bus_.shutdown();
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FastMovePoller_utest::deleteQuietly);
        }
    }

    @Test
    public void startsPausedAndTogglesWithResumePause()
    {
        FastMovePoller poller = new FastMovePoller(collector_, bus_, true); // startPaused
        assertEquals("paused", poller.status());
        poller.resume();
        assertEquals("running", poller.status());
        poller.pause();
        assertEquals("paused", poller.status());
    }

    @Test
    public void startsRunningWhenNotPaused()
    {
        assertEquals("running", new FastMovePoller(collector_, bus_, false).status());
    }

    private static Config config()
    {
        return new Config(XMLData.valueOf(
                "<FSSatellite><FSCollector analysisNewsWindow=\"10m\" ohlcImpactWindow=\"30m\"/></FSSatellite>"));
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // best-effort temp cleanup
        }
    }
}
