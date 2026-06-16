#!/usr/bin/env perl
#
# Score past predictions against realized BTC moves and print the accuracy report (BL#6).
# The Java ScorePastPredictions does both in one shot: it scores the stored predictions, writes
# outcomes.jsonl / article_outcomes.jsonl, and prints the report. The live `anal feedback` command
# is the in-process equivalent.
#
# Usage:
#   perl feedback_report.pl
#   perl feedback_report.pl --days 7
#
use strict;
use warnings;
use FindBin qw($RealBin);
use lib $RealBin;
use FinSent;

init({
    name  => 'FinSentFeedbackReport',
    _SCRIPT_OPTIONS() => {
        _OPT_DEBUG_PORT_OFFSET() => -1,
        _OPT_DEBUG_SUSPEND()     => "false",
    },
});

start(
    _JAVA_OPTIONS() => {
        # IPv4-only (dead IPv6 on this host) for the Binance price fetches; and no interactive
        # command interpreter -- this is a one-shot batch tool, not the long-running satellite.
        "-Djava.net.preferIPv4Stack=true" => undef,
        "-DdisableCmdInterpreter"         => "true",
    },
    _CLASS()     => "com.finsent.feedback.ScorePastPredictions",
    # Bootstrap against the same FSSatellite config (FSCollector has dataDir + binanceBaseUrl); an
    # empty -name selects the default unnamed <FSSatellite> section. Trailing args (e.g. --days 7)
    # pass through to ScorePastPredictions.
    _ARGUMENTS() => [
        "-type"              => "FSSatellite",
        "-bootstrapDataFile" => "cfg/processes.xml",
        @ARGV,
    ],
);
