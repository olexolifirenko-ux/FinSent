#!/usr/bin/env perl
#
# wbstop_probe.pl -- operator launcher for the one-shot LIVE WhiteBIT venueStop probe
# (com.finsent.trade.broker.whitebit.WhiteBitStopProbe). It opens a DEDICATED console
# window running the probe, so you don't have to type the long java -cp line by hand.
#
# This is a dev/operator tool, NOT part of the running app: the probe class lives in the
# TEST classpath (JavaClasses.sun/.../test/monitor), so this bypasses the FSSatellite/
# common.cfg launcher (which only sees the deployed runtime jars).
#
# Safety: running it bare only PREVIEWS (places nothing). You must pass --go to actually
# launch the probe, which then places REAL but tiny orders (~$6 notional, a few cents of
# fees) and a far-away protective stop that cannot trigger. See WhiteBitStopProbe's header.
#
# Usage:
#   perl wbstop_probe.pl                       # preview only -- prints what it would run
#   perl wbstop_probe.pl --go                  # open a console and run the probe (live!)
#   perl wbstop_probe.pl --go <baseUrl> <market>
#   perl wbstop_probe.pl --go --cleanup        # cancel any resting stop orders, place nothing
#
# Keys: read from release/.env (WHITEBIT_API_KEY / WHITEBIT_API_SECRET); already-set
# environment variables win. They are passed via the environment, never written to disk.
#
use strict;
use warnings;
use FindBin qw($RealBin);
use Cwd qw(abs_path);

my $CLASS    = 'com.finsent.trade.broker.whitebit.WhiteBitStopProbe';
my $CONFIRM  = '--yes-place-live-orders';   # the probe's own live-order acknowledgement
my $TOOLCHAIN = 'C:/tools/java/jdk-17.0.15_6_temurin_x64';   # last-resort JDK17 fallback

# ---- parse args: --go gate, optional [baseUrl] [market], forward other --flags --------
# (e.g. --cleanup, which makes the probe cancel resting stop orders and place nothing)
my $go = 0;
my @pos;
my @extra;
for my $arg (@ARGV) {
    if    ($arg eq '--go')   { $go = 1; }
    elsif ($arg =~ /^--/)    { push @extra, $arg; }   # forwarded to the probe verbatim
    else                     { push @pos, $arg; }
}
my $base_url = @pos > 0 ? $pos[0] : 'https://whitebit.com';
my $market   = @pos > 1 ? $pos[1] : 'BTC_USDT';

# ---- resolve repo root, classpath, java ---------------------------------------------
my $root = abs_path("$RealBin/../..") or die "cannot resolve repo root from $RealBin\n";
my $jc   = "$root/JavaClasses.sun/JavaClasses";
my $cp   = join(';', "$jc/infra", "$jc/monitor", "$jc/test/monitor", "$root/lib/*");
my $java = resolve_java();

load_env("$RealBin/../.env");   # release/.env -> %ENV (does not overwrite existing vars)
my $have_keys = (length($ENV{WHITEBIT_API_KEY} // '') > 0) && (length($ENV{WHITEBIT_API_SECRET} // '') > 0);

# ---- preview ------------------------------------------------------------------------
print "WhiteBIT venueStop probe launcher\n";
print "  java       : $java\n";
print "  market     : $market   baseUrl: $base_url\n";
print "  classpath  : $cp\n";
print "  API keys   : " . ($have_keys ? "present (from env/.env)" : "MISSING -- set WHITEBIT_API_KEY/SECRET in release/.env") . "\n";
print "  classes    : " . (-d "$jc/test/monitor" ? "found" : "NOT BUILT -- run the gradle compile first") . "\n";

if (!$go) {
    print "\nPREVIEW ONLY -- nothing was placed. Re-run with --go to open a console and run the LIVE probe.\n";
    exit 0;
}
if (!$have_keys) {
    print "\nRefusing to launch: API keys are not set.\n";
    exit 1;
}

# ---- launch the probe in a dedicated console ----------------------------------------
my @cmd = ($java, '-Djava.net.preferIPv4Stack=true', '-cp', $cp, $CLASS, $base_url, $market, $CONFIRM, @extra);
launch_console(\@cmd);

# -------------------------------------------------------------------------------------

# Pick a JDK17 java. The probe classes are JDK17 bytecode and this project's JAVA_HOME / PATH
# java is often an older JDK (see AGENTS.md), so prefer the known JDK17 toolchain, then
# JAVA_HOME, then bare "java" on PATH.
sub resolve_java {
    for my $home ($TOOLCHAIN, $ENV{JAVA_HOME}) {
        next unless defined $home && $home ne '';
        for my $exe ("$home/bin/java.exe", "$home/bin/java") {
            return $exe if -e $exe;
        }
    }
    return 'java';
}

# Read KEY=VALUE lines from $path into %ENV (skip blanks/#; strip surrounding quotes;
# never overwrite an already-set variable). Silent no-op if the file is absent.
sub load_env {
    my ($path) = @_;
    open(my $fh, '<', $path) or return;
    while (my $line = <$fh>) {
        $line =~ s/\r?\n$//;
        next if $line =~ /^\s*(#|$)/;
        next unless $line =~ /^\s*([A-Za-z_][\w]*)\s*=\s*(.*)$/;
        my ($k, $v) = ($1, $2);
        $v =~ s/^\s+//; $v =~ s/\s+$//;
        $v =~ s/^"(.*)"$/$1/ or $v =~ s/^'(.*)'$/$1/;
        $ENV{$k} = $v unless defined $ENV{$k} && $ENV{$k} ne '';
    }
    close($fh);
}

# Open the command in a dedicated console window. On Windows: write a tiny launcher .bat
# (so quoting is bulletproof) and `start` it in a new window that stays open (pause); the
# new console inherits this process's environment, so the API keys ride along without ever
# being written to the .bat. Elsewhere: just run it inline.
sub launch_console {
    my ($cmd) = @_;
    if ($^O =~ /MSWin32|msys|cygwin/i) {
        my $bat = ($ENV{TEMP} || $ENV{TMP} || '.') . "/wbstop_probe_run.bat";
        write_bat($bat, $cmd);
        print "\nOpening a dedicated console for the probe...\n";
        system(1, 'cmd', '/c', 'start', '', $bat);   # P_NOWAIT: new window, inherits %ENV
    }
    else {
        print "\nRunning the probe (no separate console on this OS)...\n";
        system(@$cmd);
    }
}

sub write_bat {
    my ($bat, $cmd) = @_;
    my $line = join(' ', map { /[\s;]/ ? qq{"$_"} : $_ } @$cmd);
    open(my $fh, '>', $bat) or die "cannot write $bat: $!\n";
    print $fh "\@echo off\r\n";
    print $fh "title WhiteBIT venueStop probe\r\n";
    print $fh "$line\r\n";
    print $fh "echo.\r\n";
    print $fh "echo ============================================================\r\n";
    print $fh "echo Probe finished. Review the PROBE RESULT above, then verify the\r\n";
    print $fh "echo account is FLAT in the WhiteBIT UI.\r\n";
    print $fh "echo ============================================================\r\n";
    print $fh "pause\r\n";
    close($fh);
}
