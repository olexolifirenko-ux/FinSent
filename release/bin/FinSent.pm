package FinSent;
#
# Launcher framework for the FinSent Perl entry points, modelled on the
#
# A launcher reads:
#
#   use FinSent;
#   init({
#       name  => 'FinSentMonitor',
#       flags => EXCLUSIVE_MODE(),
#       _SCRIPT_OPTIONS() => {
#           _OPT_DEBUG_PORT_OFFSET() => -1,     # -1 disables remote debug
#           _OPT_DEBUG_SUSPEND()     => "false",
#       },
#   });
#   start(
#       _JAVA_OPTIONS() => { "-Xms64m" => undef, "-Xmx512m" => undef },
#       _CLASS()        => "com.finsent.Monitor",
#       _ARGUMENTS()    => [ @ARGV ],
#   );
#
# Configuration comes from release/common/cfg/common.cfg (jlaunch-style: $(var)
# substitution + "cond ? a : b"). Relative paths in the cfg are resolved against
# release/bin (where the launchers run). The [javaOptions] cfg section supplies
# the JVM options (including -classpath "$(classpath)").
#
# Override precedence for optional features (remote debug, output redirect):
#   ENV  >  init(_SCRIPT_OPTIONS)  >  cfg.
# Honoured environment variables: JAVA_HOME, _OPT_DEBUG_PORT_OFFSET,
# _OPT_DEBUG_SUSPEND, _OPT_REDIRECT_OUTPUT, _JAVA_OPTIONS, FINSENT_VERBOSE.
#
# redirect_output (cfg) / _OPT_REDIRECT_OUTPUT: when true, the launched JVM's
# stdout+stderr go to <LOGS_DIR>/<process>.out instead of the console (for
# daemons started without a console). Ports the jlaunch redirect_output option.
#
# flags => EXCLUSIVE_MODE() enforces single-instance: start() takes an OS file
# lock (release/run/<name>.lock) for the run; a second launch of the same
# 'name' is refused with exit code 11. The lock is released automatically on
# exit/crash (no stale-PID problem).
#
use strict;
use warnings;
use FindBin;
use Exporter 'import';
use Fcntl qw(:DEFAULT);
use File::Path qw(make_path);

use constant {
    _SCRIPT_OPTIONS        => '_SCRIPT_OPTIONS',
    _OPT_DEBUG_PORT_OFFSET => '_OPT_DEBUG_PORT_OFFSET',
    _OPT_DEBUG_SUSPEND     => '_OPT_DEBUG_SUSPEND',
    _OPT_REDIRECT_OUTPUT   => '_OPT_REDIRECT_OUTPUT',
    _JAVA_OPTIONS          => '_JAVA_OPTIONS',
    _CLASS                 => '_CLASS',
    _ARGUMENTS             => '_ARGUMENTS',
};

sub EXCLUSIVE_MODE { 'EXCLUSIVE_MODE' }

our @EXPORT = qw(init start EXCLUSIVE_MODE
    _SCRIPT_OPTIONS _OPT_DEBUG_PORT_OFFSET _OPT_DEBUG_SUSPEND _OPT_REDIRECT_OUTPUT
    _JAVA_OPTIONS _CLASS _ARGUMENTS);

my %CTX;       # launcher context: base, cfg, name, flags, opts
my $LOCK_FILE; # path of the single-instance lock file, removed on exit

# Exit code used when refusing to start a second exclusive instance.
use constant EXIT_ALREADY_RUNNING => 11;

# ---- path helpers --------------------------------------------------------

sub to_win {
    my ($p) = @_;
    $p =~ s{^/([A-Za-z])/}{ uc($1) . ":/" }e;
    return $p;
}

# OS-detected platform, equivalent to the jlaunch $(os64) built-in.
sub detect_platform {
    return ($^O =~ /MSWin32|msys|cygwin/i) ? 'win64' : 'linux64';
}

# Return "$home/bin/java" if a java executable exists there, else undef.
sub java_in {
    my ($home) = @_;
    return undef unless defined $home && $home ne '';
    my $j = "$home/bin/java";
    return $j if (-e $j || -e "$j.exe");
    return undef;
}

# Resolve $rel against $base, collapsing "." / "..", preserving a trailing
# "/*" wildcard and a leading drive letter.
sub norm_path {
    my ($rel, $base) = @_;
    my $p = ($rel =~ m{^([A-Za-z]:|/)}) ? $rel : "$base/$rel";
    my @out;
    for my $seg (split m{/}, $p) {
        next if $seg eq '' || $seg eq '.';
        if ($seg eq '..') {
            pop @out if @out && $out[-1] ne '..' && $out[-1] !~ /:$/;
            next;
        }
        push @out, $seg;
    }
    return join('/', @out);
}

# ---- cfg parsing (jlaunch-style) -----------------------------------------

# Returns { raw => { key => rawvalue }, jopts => [ "option line", ... ] } or undef.
sub cfg_parse {
    my ($path) = @_;
    my %raw;
    my @jopts;
    my $section = '';
    open(my $fh, '<', $path) or return undef;
    while (my $line = <$fh>) {
        $line =~ s/\r?\n$//;
        $line =~ s/^\s+//;
        $line =~ s/\s+$//;
        next if $line eq '' || $line =~ /^#/;
        if ($line =~ /^\[(\w+)\]/) { $section = $1; next; }
        if ($section eq 'javaOptions') {
            my $opt = strip_inline_comment($line);
            $opt =~ s/\s+$//;
            push @jopts, $opt if $opt ne '';
        } elsif ($line =~ /^(\w+)\s*=\s*(.+)$/) {
            my ($k, $v) = ($1, $2);
            $v = strip_inline_comment($v);
            $v =~ s/\s+$//;
            $raw{$k} = $v;
        }
    }
    close($fh);
    return { raw => \%raw, jopts => \@jopts };
}

# Strip a trailing "# comment" that is outside double quotes.
sub strip_inline_comment {
    my ($s) = @_;
    my $inq = 0;
    for (my $i = 0; $i < length($s); $i++) {
        my $c = substr($s, $i, 1);
        if ($c eq '"') {
            $inq = !$inq;
        } elsif ($c eq '#' && !$inq) {
            return substr($s, 0, $i);
        }
    }
    return $s;
}

sub cfg_resolve {
    my ($raw, $key, $seen) = @_;
    $seen ||= {};
    return '' if $seen->{$key};
    return '' unless defined $raw->{$key};
    local $seen->{$key} = 1;
    return cfg_eval($raw, $raw->{$key}, $seen);
}

sub cfg_eval {
    my ($raw, $expr, $seen) = @_;
    $expr =~ s/^\s+//;
    $expr =~ s/\s+$//;
    if ($expr =~ /^\$\((\w+)\)\s*\?\s*"([^"]*)"\s*:\s*"([^"]*)"\s*$/) {
        my ($cond, $a, $b) = ($1, $2, $3);
        my $cv = lc(cfg_resolve($raw, $cond, $seen));
        my $chosen = ($cv eq 'true' || $cv eq '1') ? $a : $b;
        return cfg_subst($raw, $chosen, $seen);
    }
    $expr =~ s/^"(.*)"$/$1/s;
    return cfg_subst($raw, $expr, $seen);
}

sub cfg_subst {
    my ($raw, $s, $seen) = @_;
    $s =~ s/\$\((\w+)\)/ cfg_resolve($raw, $1, $seen) /ge;
    return $s;
}

sub cfg_get {
    my ($raw, $key) = @_;
    return defined($raw->{$key}) ? cfg_resolve($raw, $key) : undef;
}

sub is_true {
    my ($v) = @_;
    return defined($v) && (lc($v) eq 'true' || $v eq '1');
}

sub first_defined {
    for (@_) { return $_ if defined $_; }
    return undef;
}

# ---- public API ----------------------------------------------------------

sub init {
    my ($args) = @_;
    my $base = to_win($FindBin::RealBin);          # release/bin
    $CTX{base} = $base;
    $CTX{name} = $args->{name};
    $CTX{flags} = $args->{flags};
    $CTX{opts} = $args->{ _SCRIPT_OPTIONS() } || {};
    $CTX{cfg} = cfg_parse("$base/../common/cfg/common.cfg");
    if ($CTX{cfg}) {
        # Seed jlaunch-style platform built-ins so $(os64) resolves in the cfg.
        my $raw = $CTX{cfg}{raw};
        $raw->{os64} = detect_platform() unless defined $raw->{os64};
        $raw->{os}   = $raw->{os64}      unless defined $raw->{os};
    }
    return 1;
}

sub start {
    my %a = @_;
    my $base = $CTX{base} || to_win($FindBin::RealBin);
    my $cfg = $CTX{cfg};
    my $raw = $cfg ? $cfg->{raw} : {};
    my $sep = ($base =~ /^[A-Za-z]:/) ? ';' : ':';
    my $opts = $CTX{opts} || {};

    # JVM executable. Resolution order: cfg jre_path (bundled per-platform JRE,
    # absolutised against release/bin) -> JAVA_HOME (env) -> "java" on PATH.
    # No machine-specific path is baked in.
    my $java;
    my $jp = cfg_get($raw, 'jre_path');
    $java = java_in(norm_path($jp, $base)) if defined $jp && $jp ne '';
    $java ||= java_in($ENV{JAVA_HOME});
    $java ||= 'java';

    my @jvm;

    # [javaOptions] from common.cfg (includes -classpath "$(classpath)")
    my $have_cp = 0;
    for my $line (@{ $cfg ? $cfg->{jopts} : [] }) {
        my @opt = parse_java_option($raw, $line, $base, $sep);
        $have_cp = 1 if @opt && ($opt[0] eq '-cp' || $opt[0] eq '-classpath');
        push @jvm, @opt;
    }
    unless ($have_cp) {
        push @jvm, '-cp', norm_path('../common/lib/*', $base);   # fallback
    }

    # Remote debug: ENV > init(_SCRIPT_OPTIONS) > cfg. offset -1 disables.
    my $offset = first_defined($ENV{_OPT_DEBUG_PORT_OFFSET}, $opts->{ _OPT_DEBUG_PORT_OFFSET() },
        cfg_get($raw, 'debug_port_offset'), -1);
    my $suspend = first_defined($ENV{_OPT_DEBUG_SUSPEND}, $opts->{ _OPT_DEBUG_SUSPEND() },
        cfg_get($raw, 'debug_suspend'), 'false');
    my $remote_master = is_true(cfg_get($raw, 'remote_debug'));
    if ($remote_master && $offset != -1) {
        my $portbase = first_defined(cfg_get($raw, 'debug_port_base'), 9000);
        my $port = $portbase + $offset;
        my $susp = is_true($suspend) ? 'y' : 'n';
        push @jvm, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=$susp,address=*:$port";
    }

    # Computed -D properties
    my $release = norm_path('..', $base);
    push @jvm, "-Dfinsent.home=$release";
    # LOGS_DIR (referenced as %LOGS_DIR% in cfg/system.xml outputDestinations):
    # absolutise the cfg logs_path against release/bin so it is independent of the
    # JVM working directory, defaulting to <release>/logs.
    my $logs = norm_path(first_defined(cfg_get($raw, 'logs_path'), '../logs'), $base);
    push @jvm, "-DLOGS_DIR=$logs";
    push @jvm, "-Dfinsent.debug=" . (is_true(cfg_get($raw, 'debug')) ? 'true' : 'false');
    my $platform = cfg_get($raw, 'primary_platform');
    push @jvm, "-Dfinsent.platform=" . (defined $platform ? $platform : '');
    push @jvm, "-Dfinsent.process=$CTX{name}" if defined $CTX{name} && !ref $CTX{name};

    # Script-level _JAVA_OPTIONS (hash: key=>undef -> "key"; key=>val -> "key=val")
    my $jo = $a{ _JAVA_OPTIONS() } || {};
    for my $k (sort keys %$jo) {
        my $v = $jo->{$k};
        push @jvm, (defined $v && $v ne '') ? "$k=$v" : $k;
    }

    # Environment _JAVA_OPTIONS (whitespace-separated)
    if (defined $ENV{_JAVA_OPTIONS} && $ENV{_JAVA_OPTIONS} ne '') {
        push @jvm, split /\s+/, $ENV{_JAVA_OPTIONS};
    }

    my $class = $a{ _CLASS() } or die "FinSent::start: _CLASS is required\n";
    my $argref = $a{ _ARGUMENTS() } || [];
    # Flatten the process arguments; an optional ARGV-driven value (e.g. -name with no
    # ARGV[0]) arrives as undef -> pass it as an empty string so the JVM receives -name ""
    # (which GlobalSystem treats as "unnamed") instead of warning under use warnings.
    my @arguments = map { defined $_ ? $_ : '' } (ref($argref) eq 'ARRAY' ? @$argref : ($argref));

    # Single-instance enforcement (flags => EXCLUSIVE_MODE()).
    if (flags_has_exclusive($CTX{flags})) {
        my $lockname = (defined $CTX{name} && !ref $CTX{name}) ? $CTX{name} : $class;
        acquire_exclusive_lock($lockname, $release);
    }

    my @cmd = ($java, @jvm, $class, @arguments);
    print STDERR "[finsent] exec: @cmd\n" if $ENV{FINSENT_VERBOSE};

    # Output redirection: ENV > init(_SCRIPT_OPTIONS) > cfg redirect_output. When on, the
    # launched JVM's stdout+stderr go to <LOGS_DIR>/<process>.out instead of the console.
    my $redirect = first_defined($ENV{_OPT_REDIRECT_OUTPUT}, $opts->{ _OPT_REDIRECT_OUTPUT() },
        cfg_get($raw, 'redirect_output'), 'false');
    my $status = run_jvm(\@cmd, is_true($redirect), $logs);
    exit($status >> 8);
}

# Run the JVM command, optionally redirecting its stdout+stderr to <logs>/<process>.out
# (appended). Returns the raw system() status ($?).
sub run_jvm {
    my ($cmd, $redirect, $logs) = @_;
    unless ($redirect) {
        system(@$cmd);
        return $?;
    }
    my $procname = (defined $CTX{name} && !ref $CTX{name}) ? $CTX{name} : 'FinSent';
    (my $safe = $procname) =~ s/[^\w.\-]/_/g;
    eval { make_path($logs); 1 } or warn "[finsent] cannot create logs dir $logs ($@)\n";
    my $outfile = "$logs/$safe.out";
    my $out;
    unless (open($out, '>>', $outfile)) {
        warn "[finsent] cannot open $outfile ($!) -- using console\n";
        system(@$cmd);
        return $?;
    }
    print STDERR "[finsent] redirecting output to $outfile\n" if $ENV{FINSENT_VERBOSE};
    open(my $saved_out, '>&', \*STDOUT) or warn "[finsent] cannot save STDOUT ($!)\n";
    open(my $saved_err, '>&', \*STDERR) or warn "[finsent] cannot save STDERR ($!)\n";
    open(STDOUT, '>&', $out) or warn "[finsent] cannot redirect STDOUT ($!)\n";
    open(STDERR, '>&', $out) or warn "[finsent] cannot redirect STDERR ($!)\n";
    system(@$cmd);
    my $status = $?;
    open(STDOUT, '>&', $saved_out) if defined $saved_out;   # restore the console handles
    open(STDERR, '>&', $saved_err) if defined $saved_err;
    close($out);
    return $status;
}

# ---- single-instance enforcement ----------------------------------------

sub flags_has_exclusive {
    my ($f) = @_;
    return 0 unless defined $f;
    my @list = ref($f) eq 'ARRAY' ? @$f : ($f);
    return scalar grep { $_ eq EXCLUSIVE_MODE() } @list;
}

# Acquire an exclusive lock for $name, or refuse to start (exit 11) if another
# live instance already holds it.
#
# Mechanism: atomically create release/run/<name>.lock (O_CREAT|O_EXCL) and
# record this launcher's PID. If the file already exists, the recorded PID is
# checked for liveness: a live holder -> refuse; a dead holder (stale lock from
# a crash) -> remove and retry. The file is removed on normal exit (END block);
# a leftover stale file is reclaimed on the next launch via the liveness check.
# (We use atomic create + liveness rather than flock, which does not reliably
# enforce cross-process exclusion on Windows/msys.)
sub acquire_exclusive_lock {
    my ($name, $release) = @_;
    (my $safe = $name) =~ s/[^\w.\-]/_/g;
    my $dir = "$release/run";
    unless (-d $dir) {
        eval { make_path($dir); 1 } or do {
            warn "[finsent] cannot create lock dir $dir ($@) -- proceeding without exclusivity\n";
            return;
        };
    }
    my $lockfile = "$dir/$safe.lock";

    for my $attempt (1 .. 2) {
        my $fh;
        if (sysopen($fh, $lockfile, O_CREAT | O_EXCL | O_WRONLY)) {
            print $fh "$$\n";
            close($fh);
            $LOCK_FILE = $lockfile;       # removed by the END block on exit
            return;
        }
        # Lock file exists -- inspect the recorded holder.
        my $pid;
        if (open(my $rf, '<', $lockfile)) {
            $pid = <$rf>;
            close($rf);
            chomp $pid if defined $pid;
        }
        if (defined $pid && $pid =~ /^\d+$/ && process_alive($pid)) {
            print STDERR "[finsent] $name is already running (pid $pid). "
                . "Refusing to start a second instance.\n";
            exit(EXIT_ALREADY_RUNNING);
        }
        # Stale lock (holder gone) -- remove and retry the atomic create.
        unless (unlink $lockfile) {
            warn "[finsent] cannot remove stale lock $lockfile ($!) -- proceeding without exclusivity\n";
            return;
        }
    }
    warn "[finsent] could not acquire lock for $name -- proceeding without exclusivity\n";
}

# True if a process with the given PID is alive (signal 0 probe).
sub process_alive {
    my ($pid) = @_;
    return kill(0, $pid) ? 1 : 0;
}

# Parse one [javaOptions] line into a list of java args, resolving $(var) and
# (for -cp/-classpath) the ":"-classpath into absolute, OS-joined entries.
sub parse_java_option {
    my ($raw, $line, $base, $sep) = @_;
    if ($line =~ /^(-\S+)\s+"(.*)"$/ || $line =~ /^(-\S+)\s+(.+)$/) {
        my ($flag, $val) = ($1, $2);
        if ($flag eq '-cp' || $flag eq '-classpath') {
            return ($flag, resolve_cp_value($raw, $val, $base, $sep));
        }
        return ($flag, cfg_subst($raw, $val));
    }
    return (cfg_subst($raw, $line));
}

sub resolve_cp_value {
    my ($raw, $val, $base, $sep) = @_;
    my $resolved = cfg_subst($raw, $val);
    my @entries = grep { $_ ne '' } split /:/, $resolved;
    return join($sep, map { norm_path($_, $base) } @entries);
}

# Release the single-instance lock on normal exit (incl. exit() and Ctrl+C,
# after system() returns). A SIGKILL leaves the file behind; it is reclaimed on
# the next launch by the PID liveness check in acquire_exclusive_lock.
END {
    unlink $LOCK_FILE if defined $LOCK_FILE;
}

1;
