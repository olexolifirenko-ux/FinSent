package com.finsent;

import java.nio.file.Path;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FSAnalyser;
import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.cmd.AnalGroupCmdHandler;
import com.finsent.app.AbstractAppInitializer;
import com.finsent.collect.CollectionResult;
import com.finsent.collect.CollectorRunner;
import com.finsent.collect.EconResolved;
import com.finsent.collect.EconScheduler;
import com.finsent.collect.FSCollector;
import com.finsent.collect.FastMovePoller;
import com.finsent.collect.UrgentPoller;
import com.finsent.collect.cmd.CollectGroupCmdHandler;
import com.finsent.core.Config;
import com.finsent.core.event.EventBus;
import com.finsent.directory.DirectorySystem;
import com.finsent.trade.FSTrader;
import com.finsent.trade.broker.whitebit.WhiteBitClient;
import com.finsent.trade.cmd.TradeGroupCmdHandler;
import com.finsent.util.GlobalSystem;

/**
 * Application entry point. Beyond the framework bootstrap (config + log facility) handled by
 * {@link AbstractAppInitializer}, this owns the application {@link EventBus} and wires the top-level
 * components onto it:
 * <ol>
 *   <li>the {@link EventBus}: the app owns it and injects an {@link com.finsent.core.event.EventPublisher}
 *       into the producers (collector / analyser / poller); the app wires all subscriptions;</li>
 *   <li>the {@link FSCollector}, which owns collected-data persistence and publishes each cycle's result;</li>
 *   <li>the {@link FSAnalyser} (subscribed to the collector's results), which builds and owns everything it
 *       drives &mdash; its own analysis store, the Claude passes, the notification channels &mdash; from config;</li>
 *   <li>the {@link FSTrader} (subscribed to the analyser's and the poller's signals);</li>
 *   <li>the {@code anal}/{@code trade} command groups and the {@link CollectorRunner}/{@link UrgentPoller}/
 *       {@link FastMovePoller} schedulers.</li>
 * </ol>
 * Uninitializers run last-registered-first: the schedulers stop first (no new cycles/events), then the
 * analyser worker drains and shuts its notifier + store, then the collector flushes persistence, and
 * finally the event bus stops last (registered first) &mdash; so no producer publishes after it stops.
 */
public class FSApp extends AbstractAppInitializer
{
    private EventBus eventBus_;
    private FSCollector collector_;
    private FSAnalyser analyser_;
    private FSTrader trader_;
    private CollectorRunner collectorRunner_;
    private UrgentPoller urgentPoller_;
    private EconScheduler econScheduler_;
    private FastMovePoller fastMovePoller_;

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

        // The application owns the event bus; producers get an EventPublisher, the app wires subscriptions.
        eventBus_ = new EventBus();
        collector_ = new FSCollector(config, dataDir, eventBus_);
        collector_.recover(config.recoveryLookbackInDays());
        // Initial X (Twitter) fetch state from the -DfetchX launcher flag (default off); toggled at
        // runtime via `collect x on|off`. No-op when X is not configured (no key/accounts).
        collector_.setXEnabled(Boolean.getBoolean("fetchX"));

        // Start paused unless -DrunAnalyser=true (default off when the flag is absent -> no Claude
        // calls / alerts until `anal on`). startPaused is the inverse of the run flag.
        analyser_ = new FSAnalyser(collector_, config, eventBus_, !Boolean.getBoolean("runAnalyser"));
        eventBus_.subscribe(CollectionResult.class, analyser_);
        eventBus_.subscribe(EconResolved.class, analyser_::onEconResolved);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(AnalGroupCmdHandler.COMMAND,
                new AnalGroupCmdHandler(analyser_), AnalGroupCmdHandler.DESCRIPTION, AnalGroupCmdHandler.COMMAND_ALIASES);

        // One WhiteBIT client shared by the `trade wbcheck`/`trade wborder` commands and the auto-trader's
        // live broker. The auto-trader uses it only when broker=whitebit in config (else the paper broker).
        WhiteBitClient whitebit = new WhiteBitClient(config.whitebitApiKey(), config.whitebitApiSecret(),
                config.whitebitBaseUrl(), config.whitebitMarket());
        // Trader: start paused unless -DrunTrader=true (default off, like the analyser); acts on the
        // analyser's AnalysisReady signals over the same bus.
        trader_ = new FSTrader(collector_, config, whitebit, !Boolean.getBoolean("runTrader"));
        eventBus_.subscribe(AnalysisReady.class, trader_::onAnalysisReadyEvent);
        // The mechanical FastMove (momentum) lane: a separate event the trader consumes via a method reference.
        eventBus_.subscribe(FastMoveReady.class, trader_::onFastEvent);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(TradeGroupCmdHandler.COMMAND,
                new TradeGroupCmdHandler(trader_, whitebit), TradeGroupCmdHandler.DESCRIPTION,
                TradeGroupCmdHandler.COMMAND_ALIASES);

        collectorRunner_ = new CollectorRunner(collector_);
        urgentPoller_ = new UrgentPoller(collector_);
        econScheduler_ = new EconScheduler(collector_, dataDir);
        fastMovePoller_ = new FastMovePoller(collector_, eventBus_);
        GlobalSystem.getCmdInterpreter().registerCmdHandler(CollectGroupCmdHandler.COMMAND,
                new CollectGroupCmdHandler(econScheduler_, collector_), CollectGroupCmdHandler.DESCRIPTION,
                CollectGroupCmdHandler.COMMAND_ALIASES);

        // Registration order matters: uninitializers run last-registered-first, so the schedulers
        // (registered last) stop first -- the econ scheduler before the others, so no econ resolution
        // commits or publishes after the analyser/collector start shutting down -- then the analyser
        // worker drains and shuts its notifier/store, then the collector flushes persistence, and the
        // event bus (registered first) stops last so no producer publishes onto a stopped bus.
        GlobalSystem.registerUninitializer(eventBus_::shutdown);
        GlobalSystem.registerUninitializer(collector_::shutdown);
        GlobalSystem.registerUninitializer(analyser_);
        GlobalSystem.registerUninitializer(collectorRunner_);
        GlobalSystem.registerUninitializer(urgentPoller_);
        GlobalSystem.registerUninitializer(econScheduler_);
        // Registered last so it stops first: the trader stops acting and flushes its book before the
        // analyser/collector tear down, so no entry/exit fires while the rest is shutting down.
        GlobalSystem.registerUninitializer(trader_);
        // Registered after the trader so it stops even sooner: the FastMove poller stops producing fires
        // before the trader stops consuming them.
        GlobalSystem.registerUninitializer(fastMovePoller_);

        collectorRunner_.start();
        urgentPoller_.start();
        if (config.econEnabled())
        {
            econScheduler_.start();
        }
        trader_.start();
        // Off by default; starts only when <FSFastMove enabled="true">. Alert-only unless trade="true".
        if (config.fastMoveEnabled())
        {
            fastMovePoller_.start();
        }
    }
}
