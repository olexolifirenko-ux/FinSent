# FinSent prod deployment

Tooling for running FinSent 24/7 from an immutable, rollback-able release tree,
separate from the dev checkout. Source of truth for the prod box's scripts and
systemd unit.

## Layout on the prod box

```
~/finsent-prod/
  releases/<ts>_<sha>/   immutable build (release/ minus state), one per deploy
  current -> releases/…  symlink the service runs from
  shared/                persistent state, symlinked INTO each release:
    .env                 secrets (0600, never committed; see env.example)
    data/  logs/  run/   pipeline data, logs, single-instance lock
  deploy.sh  rollback.sh
```

Deploys replace **code only**; `shared/` state survives every deploy and rollback.

## Files here

- `deploy.sh` — build dev `HEAD` (`gradlew deployRelease`), package `release/` minus
  state into `releases/<ts>_<sha>`, symlink shared state + bundled JRE, flip
  `current`, restart the service, prune to the last 5 releases.
- `rollback.sh` — repoint `current` at the previous (or a named) release and restart.
- `finsent.service` — user systemd unit (`Type=simple`). Prod JVM env: no JDWP
  debug port, stdout→`logs/FinSent.out`, `StandardInput=null`.
- `env.example` — template for `shared/.env`. Copy to `~/finsent-prod/shared/.env`,
  fill keys, `chmod 600`. Blank broker keys => paper mode (safe default).

Paths default to this box but are overridable: `FINSENT_DEV`, `FINSENT_PROD`,
`FINSENT_JDK`.

## Install (one-time)

```
cp deploy/finsent.service ~/.config/systemd/user/finsent.service
systemctl --user daemon-reload
cp deploy/env.example ~/finsent-prod/shared/.env && chmod 600 ~/finsent-prod/shared/.env
./deploy/deploy.sh
```

## Operate

```
systemctl --user start|stop|status finsent      # start/stop on demand
journalctl --user -u finsent -f                 # follow service output
tail -f ~/finsent-prod/shared/logs/FSSatellite.$(date +%F).log
./deploy/deploy.sh                              # ship the current dev commit
./deploy/rollback.sh                            # revert to previous release
```

## Conventions / safety

- **Prod does NOT auto-start.** The unit is left `disabled` with linger off; start it
  only when explicitly intended (`systemctl --user start finsent`). Do not `enable`
  it or enable linger unless that decision is made deliberately.
- **Going live is deliberate:** fill `WHITEBIT_API_KEY` / `WHITEBIT_API_SECRET` in
  `shared/.env`, run `release/bin/wbstop_probe.pl` to verify venue stop behavior,
  then `trade on` in the console. Otherwise the trader stays paper + paused.
- **One instance only** (`EXCLUSIVE_MODE` lock): run either the systemd daemon or an
  interactive console (`cd ~/finsent-prod/current && perl bin/FSSatellite.pl`, ideally
  under tmux) — never both against the same `shared/data` at once.
