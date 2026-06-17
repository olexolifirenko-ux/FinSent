#!/usr/bin/env perl
#
# Launch a FinSent Satellite process.
#
# Usage:
#   perl FSSatellite.pl [<name>]      # <name> optional; when omitted the process
#                                     # runs unnamed and picks up the default
#                                     # (unnamed) <FSSatellite> section of
#                                     # cfg/processes.xml.
#
# The -type / -name / -bootstrapDataFile arguments below are passed through to
# the Java class and parsed by AbstractAppInitializer (GlobalDefs.CFG_TYPE /
# CFG_NAME / CFG_BOOTSTRAP_DATA_FILE). Modeled on the TMS
# ELTAllocationSatellite.pl launcher, which likewise takes the name from ARGV[0].
#
use strict;
use warnings;
use FindBin qw($RealBin);
use lib $RealBin;
use FinSent;

# Standard process arguments handed to the Java class (parsed by CmdArgParser /
# AbstractAppInitializer). The name comes straight from ARGV[0] (as in master_AO);
# when absent it is passed as an empty -name and GlobalSystem.getProcessData falls
# back to the default (unnamed) <FSSatellite> section of processes.xml. Resource
# paths resolve against the release home (-Dfinsent.home, set by FinSent.pm).
my $arguments = [
    "-type"              => "FSSatellite",
    "-name"              => $ARGV[0],
    "-bootstrapDataFile" => "cfg/processes.xml",
];

init({
    # The name (ARGV[0]) drives the per-name single-instance lock + -Dfinsent.process
    # when supplied; when ARGV[0] is absent FinSent.pm falls back to a class-based
    # lock (the same effect as master_AO's ref-style 'name').
    name  => $ARGV[0],
    flags => EXCLUSIVE_MODE(),
    _SCRIPT_OPTIONS() => {
        _OPT_DEBUG_PORT_OFFSET() => 0,        # 9000
        _OPT_DEBUG_SUSPEND()     => "false",
    },
});

start(
    _JAVA_OPTIONS() => {
        "-Xms64m"         => undef,
        "-Xmx512m"        => undef,
        # Force IPv4-only sockets. Several feeds/APIs (e.g. AlJazeera) are CDN-fronted with both A
        # and AAAA records; IPv6 is unroutable from this host, and the JDK HttpClient does not fall
        # back from a dead IPv6 address to IPv4 -- so the connect hangs until the (tight, urgent)
        # request timeout fires. IPv4-only avoids the wasted IPv6 connect attempt entirely.
        "-Djava.net.preferIPv4Stack=true" => undef,
        # JVM system property read by FSApp via Boolean.getBoolean("runAnalyser"): whether the analyser
        # starts running. "false" (or absent) starts it paused so the user runs `anal on` when ready
        # (no Claude calls / alerts until then). Must live in _JAVA_OPTIONS (not _SCRIPT_OPTIONS) --
        # only that block becomes -D... on the java line.
        "-DrunAnalyser" => "false",
        # Initial state of the fast X (Twitter) source, read by FSApp via Boolean.getBoolean("fetchX").
        # Default is off when absent; set "true" to start polling X at launch. Toggle live with
        # `collect x on|off` (no restart). Requires getxapiKey + <XAccounts> to have any effect.
        "-DfetchX" => "false",
    },
    _CLASS()     => "com.finsent.FSApp",
    _ARGUMENTS() => $arguments,
);
