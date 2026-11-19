# Retrospect

**Every fight, reviewed. Win or lose.**

Everyone says to review your deaths. Almost nobody does, because reviewing means
recording, hunting for the clip, and watching yourself make the mistake again.

Retrospect does it for you. The moment a fight ends it tells you **the one thing worth
fixing**, one thing to try next time, and what you actually did well, then hands you a
clip of the moment it is talking about. It measures whether you were really swinging, by
comparing your cadence to your weapon's true speed, and it credits damage to the attack
that caused it rather than guessing. Bosses, slayer, and other players.

It never speaks during a fight. No overlays, no prompts, nothing drawn on your screen.
You play, it watches, and it has something to say when it is over.

## What it tells you

- The one thing worth fixing, and one thing to try next time.
- Where your damage went, and where theirs came from, grouped by the attack that did it.
- Whether you were actually swinging: your cadence against the weapon's real speed, and
  what the gaps cost you in damage.
- What you did well, so it is not only a list of mistakes.
- A clip of the moment it is talking about.

## The panel

- **Fight**: the last fight. The takeaway, "try this", strong, weak, and under "Show the
  detail" the numbers and a you-versus-them timeline. Player fights list who you fought.
- **Folders**: one folder per boss, each with its own switches.
- **Customize**: rename any heading, recolour the panel, and "Revert to stock".

## Everyday and Serious

The **Mode** button at the top of the panel switches between them.

- **Everyday** (default) is meant to stay on. It shows the debrief and catches your
  moments. It files nothing else.
- **Serious** also files each fight's debrief, clips and screenshots into that boss's
  folder. A folder is made the first time you fight something, named with its game id,
  for example `Vorkath [8061]`. All player fights share one `PvP` folder.
  **+ Create custom** makes a folder of your own for any set of bosses.

## The Saving switch

Next to **Mode** is **Saving**, one click that covers every automatic save:
**Clips + shots** (each moment does its own thing), **Clips only**, **Shots only**, or
**Off**. Walking into Soul Wars or a busy PK trip is one click to Off, and the button
turns red so you know it is. **"Clip that" always works**, even on Off, so the one moment
you do want is still a keypress away.

Each moment can also be set on its own, in the Customize tab or in the settings.

## Moments it saves

| Moment | Default |
| --- | --- |
| Player kill (the moment they drop) | Clip + screenshot |
| Your death | Clip |
| Valuable drop (1m+ by default) | Clip + screenshot |
| New personal best | Clip + screenshot |
| Pet or new collection log item | Clip + screenshot |
| Every boss kill | Off |
| Big hit (optionally special attacks only) | Off |
| "Clip that" hotkey or panel button | Clip |

Each can be set to Off, Screenshot, Clip, or both.

## How recording works, and privacy

- **It is not a screen recorder.** Frames come from RuneLite's own renderer, the same
  source the built-in Screenshot plugin uses. It can only ever see the game image, never
  your desktop or other windows.
- It only captures while you are logged in.
- The last few seconds are held **in memory only** and constantly overwritten. Nothing is
  written to disk until a moment happens or you press "Clip that".
- Nothing is uploaded anywhere. Everything stays in `.runelite/plugin-data/retrospect/` on
  your PC, the directory RuneLite gives the plugin. It cannot write anywhere else. If you
  ran an earlier build, your old `.runelite/retrospect/` folder is moved there on first start.
- Clips are AVI (Motion JPEG) files with no sound. They play in VLC and most desktop
  players. Discord will not preview them inline.

## Memory and storage

- The rolling recording is capped at 64 MB by default ("Memory limit"). RuneLite has
  768 MB in total unless you have raised it, so keep this low. If clips come out shorter
  than you asked for, lower the frame rate or clip size first.
- The dated `clips` folder has a size limit (5 GB by default). When it fills, the
  **oldest clips in that folder are deleted** to make room. Files in boss folders are
  never deleted automatically.

## Things to know

- An attack with no name is called "an attack of its own", and the debrief tells you the
  number to nickname it with in the settings: `14322=swipe, 14326=donut`. Common special
  attack weapons are named out of the box.
- Attack cadence is measured against the weapon's real speed, read from the game, so
  being a tick late every swing shows up as what it costs you. Autocast magic falls back
  to your observed rhythm, because a staff's listed speed is not the spell's speed.
- Damage is credited to the attacker's most recent animation. That is a good guess, not a
  certainty: delayed projectiles can be credited to the wrong move.
- "Time not attacking" includes downtime the fight forces on you. It says so.
- The short lines like "ezpz" and "</3" can be switched off ("OSRS lingo").

## Development

    ./gradlew run     # starts RuneLite with the plugin loaded
    ./gradlew test    # runs the fight-logic, clip-file and storage tests
