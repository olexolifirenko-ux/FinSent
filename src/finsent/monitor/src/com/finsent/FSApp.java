package com.finsent;

import java.nio.file.Path;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FSAnalyser;
import com.finsent.analyse.cmd.AnalGroupCmdHandler;
import com.finsent.app.AbstractAppInitializer;
import com.finsent.collect.CollectorRunner;
import com.finsent.collect.EconScheduler;
import com.finsent.collect.FSCollector;
import com.finsent.collect.UrgentPoller;
import com.finsent.collect.cmd.CollectGroupCmdHandler;
import com.finsent.core.Config;
import com.finsent.directory.DirectorySystem;
import com.finsent.trade.FSTrader;
import com.finsent.trade.cmd.TradeGroupCmdHandler;
import com.finsent.util.GlobalSystem;

/**
 * Application entry point. Beyond the framework bootstrap (config + log facility) handled by
 * {@link AbstractAppInitializer}, this wires the four top-level components:
 * <ol>
 *   <li>the {@link FSCollector}, which owns collected-data persistence + the event bus and recovers
 *       its registries at startup;</li>
 *   <li>the {@link FSAnalyser} (subscribed to the collector), which builds and owns everything it
 *       drives &mdash; its own analysis store, the Claude passes, the notification channels and the
 *       macro-alert check &mdash; from config;</li>
 *   <li>the {@code anal} command group for runtime start/pause/status;</li>
 *   <li>the {@link CollectorRunner} and {@link UrgentPoller} schedulers.</li>
 * </ol>
 * Uninitializers run last-registered-first: the schedulers stop first (no new cycles/events), then
 * the analyser worker drains and shuts its notifier + store, and finally the collector flushes
 * persistence and stops the bus &mdash; so nothing commits or publishes afterward.
 */
public class FSApp extends AbstractAppInitializer
{
    private FSCollector collector_;
    private FSAnalyser analyser_;
    private FSTrader trader_;
    private CollectorRunner collectorRunner_;
    private UrgentPoller urgentPoller_;
    private EconScheduler econScheduler_;

    public FSApp(String[] args) throws Exception
    {
        super(args);
    }

    /**
     * Process entry point: constructing the app runs the full {@code initialize()} lifecycle. The
     * pipeline runs on daemon threads (collector/urgent/event-bus/persistence/analyser/notifier), so
     * the non-daemon main thread is parked here to keep the JVM alive until an external shutdown
     * (Ctrl+C / SIGTERM) runs the registered uninitializers via the GlobalSystem shutdown hook.
     */
    public static void main(String[] args) throws Exception
    {
        new FSApp(args);
        Thread.currentThread().join();
    }

    @Override
    protected void initializeAppSystems() throws Exception
    {
        super.initializeAppSystems();

        Config config = Config.fromGlobalSystem();
        Path dataDir = DirectorySystem.resolveToFile(config.dataDir()).toPath();

        collector_ = new FSCollector(config, dataDir);
        collector_.recover(config.recoveryLookbackInDays());
        // Initial X (Twitter) fetch state from the -DfetchX launcher flag (default off); toggled at
        // runtime via `collect x on|off`. No-op when X is not configured (no key/accounts).
        collector_.setXEnabled(Boolean.getBoolean("fetchX"));

        // Start paused unless -DrunAnalyser=true (default off when the flag is absent -> no Claude
        // calls / alerts until `anal on`). startPaused is the inverse of the run flag.
        analyser_ = new FSAnalyser(collector_, config, !Boolean.getBoolean("runAnalyser"));
        collector_.addListener(analyser_);
        collector_.addEconListener(analyser_::onEconResolved);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(AnalGroupCmdHandler.COMMAND,
                new AnalGroupCmdHandler(analyser_), AnalGroupCmdHandler.DESCRIPTION, AnalGroupCmdHandler.COMMAND_ALIASES);

        // Trader: start paused unless -DrunTrader=true (default off, like the analyser); acts on the
        // analyser's AnalysisReady signals over the same bus. Paper broker by default -- no live orders.
        trader_ = new FSTrader(collector_, config, !Boolean.getBoolean("runTrader"));
        collector_.subscribe(AnalysisReady.class, trader_);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(TradeGroupCmdHandler.COMMAND,
                new TradeGroupCmdHandler(trader_), TradeGroupCmdHandler.DESCRIPTION, TradeGroupCmdHandler.COMMAND_ALIASES);

        collectorRunner_ = new CollectorRunner(collector_);
        urgentPoller_ = new UrgentPoller(collector_);
        econScheduler_ = new EconScheduler(collector_, dataDir);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(CollectGroupCmdHandler.COMMAND,
                new CollectGroupCmdHandler(econScheduler_, collector_), CollectGroupCmdHandler.DESCRIPTION,
                CollectGroupCmdHandler.COMMAND_ALIASES);

        // Registration order matters: uninitializers run last-registered-first, so the schedulers
        // (registered last) stop first -- the econ scheduler before the others, so no econ resolution
        // commits or publishes after the analyser/collector start shutting down -- then the analyser
        // worker drains and shuts its notifier/store, and finally the collector flushes persistence.
        GlobalSystem.registerUninitializer(collector_::shutdown);
        GlobalSystem.registerUninitializer(analyser_);
        GlobalSystem.registerUninitializer(collectorRunner_);
        GlobalSystem.registerUninitializer(urgentPoller_);
        GlobalSystem.registerUninitializer(econScheduler_);
        // Registered last so it stops first: the trader stops acting and flushes its book before the
        // analyser/collector tear down, so no entry/exit fires while the rest is shutting down.
        GlobalSystem.registerUninitializer(trader_);

        collectorRunner_.start();
        urgentPoller_.start();
        if (config.econEnabled())
        {
            econScheduler_.start();
        }
        trader_.start();
    }
}
