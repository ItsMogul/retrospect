package com.retrospect;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(RetrospectConfig.GROUP)
public interface RetrospectConfig extends Config
{
	String GROUP = "retrospect";

	/** What to save when a moment happens. */
	/**
	 * One switch over every automatic save, so a session like Soul Wars can be made
	 * quiet from the panel without touching seven separate settings. The "Clip that"
	 * button and hotkey are deliberate, so they keep working whatever this says.
	 */
	enum Saving
	{
		AS_SET("Clips + shots"),
		CLIPS("Clips only"),
		SHOTS("Shots only"),
		OFF("Off");

		private final String label;

		Saving(String label)
		{
			this.label = label;
		}

		String label()
		{
			return label;
		}

		Saving next()
		{
			return values()[(ordinal() + 1) % values().length];
		}

		/** What a moment that wanted {@code wanted} should actually save. */
		Capture apply(Capture wanted)
		{
			if (wanted == null || wanted == Capture.OFF || this == OFF)
			{
				return Capture.OFF;
			}
			switch (this)
			{
				case CLIPS:
					return Capture.CLIP;
				case SHOTS:
					return Capture.SCREENSHOT;
				default:
					return wanted;
			}
		}
	}

	enum Capture
	{
		OFF("Off"),
		SCREENSHOT("Screenshot"),
		CLIP("Clip"),
		BOTH("Clip + screenshot");

		private final String label;

		Capture(String label)
		{
			this.label = label;
		}

		boolean clip()
		{
			return this == CLIP || this == BOTH;
		}

		boolean screenshot()
		{
			return this == SCREENSHOT || this == BOTH;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** How much Retrospect keeps. */
	enum Mode
	{
		EVERYDAY("Everyday"),
		SERIOUS("Serious");

		private final String label;

		Mode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum ClipSize
	{
		P480("480p (smallest files)", 480),
		P720("720p", 720),
		P1080("1080p (heavy)", 1080);

		private final String label;
		final int height;

		ClipSize(String label, int height)
		{
			this.label = label;
			this.height = height;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(name = "Moments", description = "Which moments get saved, and how", position = 0)
	String moments = "moments";

	@ConfigSection(name = "Clips", description = "How clips are recorded and stored", position = 1)
	String clips = "clips";

	@ConfigSection(name = "Fight debrief", description = "The short write-up after each fight", position = 2)
	String debrief = "debrief";

	@ConfigItem(keyName = "saving", name = "Saving", description = "Master switch over every automatic save. Clips + shots uses each moment's own setting below; Off keeps nothing until you turn it back on. The Clip that button and hotkey always work.", position = -2)
	default Saving saving()
	{
		return Saving.AS_SET;
	}

	@ConfigItem(keyName = "mode", name = "Mode", description = "Everyday: catches your moments and shows the debrief, keeps nothing else. Serious: also files every fight's debrief and clips into that boss's folder.", position = -1)
	default Mode mode()
	{
		return Mode.EVERYDAY;
	}

	// ---------- Moments ----------

	@ConfigItem(keyName = "valuableDrop", name = "Valuable drop", description = "Loot worth at least the value below", section = moments, position = 0)
	default Capture valuableDrop()
	{
		return Capture.BOTH;
	}

	@Range(min = 1, max = 2_000_000_000)
	@ConfigItem(keyName = "dropValue", name = "Drop worth at least (gp)", description = "Total value of one drop that counts as valuable", section = moments, position = 1)
	default int dropValue()
	{
		return 1_000_000;
	}

	@ConfigItem(keyName = "personalBest", name = "New personal best", description = "The game says (new personal best) on a kill, lap or raid", section = moments, position = 2)
	default Capture personalBest()
	{
		return Capture.BOTH;
	}

	@ConfigItem(keyName = "pkKill", name = "Player kill", description = "A player you were fighting dies", section = moments, position = 3)
	default Capture pkKill()
	{
		return Capture.BOTH;
	}

	@ConfigItem(keyName = "myDeath", name = "Your death", description = "Useful for reviewing what went wrong", section = moments, position = 4)
	default Capture myDeath()
	{
		return Capture.CLIP;
	}

	@ConfigItem(keyName = "collection", name = "Pet or collection log", description = "A pet drop or a new collection log item", section = moments, position = 5)
	default Capture collection()
	{
		return Capture.BOTH;
	}

	@ConfigItem(keyName = "bossKill", name = "Every boss kill", description = "Any kill that prints a kill count. Fills storage fast.", section = moments, position = 6)
	default Capture bossKill()
	{
		return Capture.OFF;
	}

	@ConfigItem(keyName = "bigHit", name = "Big hit", description = "One of your hits at or above the number below. Can fire often, so it is off by default.", section = moments, position = 7)
	default Capture bigHit()
	{
		return Capture.OFF;
	}

	@Range(min = 1, max = 500)
	@ConfigItem(keyName = "bigHitMin", name = "Big hit is at least", description = "Smallest hit that counts as big", section = moments, position = 8)
	default int bigHitMin()
	{
		return 50;
	}

	@ConfigItem(keyName = "bigHitSpecOnly", name = "Big hit: special attacks only", description = "Only count hits that land right after you use a special attack", section = moments, position = 9)
	default boolean bigHitSpecOnly()
	{
		return true;
	}

	@ConfigItem(keyName = "clipHotkey", name = "Clip that (hotkey)", description = "Saves the last few seconds whenever you press it", section = moments, position = 10)
	default Keybind clipHotkey()
	{
		return Keybind.NOT_SET;
	}

	// ---------- Clips ----------

	@ConfigItem(keyName = "recordClips", name = "Keep a rolling recording", description = "Holds the last few seconds of your screen in memory so a clip can include what led up to the moment. Turn off for screenshots only.", section = clips, position = 0)
	default boolean recordClips()
	{
		return true;
	}

	@Range(min = 3, max = 30)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "secondsBefore", name = "Seconds before the moment", description = "How far back a clip reaches. More seconds uses more memory.", section = clips, position = 1)
	default int secondsBefore()
	{
		return 10;
	}

	@Range(min = 1, max = 10)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "secondsAfter", name = "Seconds after the moment", description = "How long a clip keeps rolling afterwards", section = clips, position = 2)
	default int secondsAfter()
	{
		return 4;
	}

	@Range(min = 5, max = 20)
	@ConfigItem(keyName = "fps", name = "Frames per second", description = "Higher is smoother but costs more of your computer. 15 is a good balance.", section = clips, position = 3)
	default int fps()
	{
		return 15;
	}

	@ConfigItem(keyName = "clipSize", name = "Clip size", description = "Clips are scaled down to this height", section = clips, position = 4)
	default ClipSize clipSize()
	{
		return ClipSize.P720;
	}

	@Range(min = 16, max = 512)
	@ConfigItem(keyName = "maxMemoryMb", name = "Memory limit (MB)", description = "The most memory the rolling recording may use. RuneLite has 768 MB in total unless you have raised it, so keep this low. If clips come out shorter than you asked for, lower the frame rate or clip size before raising this.", section = clips, position = 5)
	default int maxMemoryMb()
	{
		return 64;
	}

	@Range(min = 1, max = 200)
	@ConfigItem(keyName = "maxStorageGb", name = "Storage limit (GB)", description = "When the dated clips folder grows past this, the OLDEST clips in it are deleted to make room. Move any clip you want to keep forever out of the folder.", section = clips, position = 6)
	default int maxStorageGb()
	{
		return 5;
	}

	// ---------- Debrief ----------

	@ConfigItem(keyName = "debrief", name = "Write a debrief after fights", description = "Tracks each fight and tells you the one thing worth fixing", section = debrief, position = 0)
	default boolean debrief()
	{
		return true;
	}

	@Range(min = 5, max = 600)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "minFightSeconds", name = "Ignore fights shorter than", description = "Keeps slayer tasks and trash mobs from spamming debriefs. Deaths always count.", section = debrief, position = 1)
	default int minFightSeconds()
	{
		return 20;
	}

	@Range(min = 10, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "fightTimeout", name = "Fight is over after", description = "Seconds with no hits either way before a fight counts as finished. Raise it for bosses with long pauses.", section = debrief, position = 2)
	default int fightTimeout()
	{
		return 30;
	}

	@ConfigItem(keyName = "chatTakeaway", name = "Show takeaway in chat", description = "Prints the one-line takeaway in your chatbox (only you see it)", section = debrief, position = 3)
	default boolean chatTakeaway()
	{
		return true;
	}

	@ConfigItem(keyName = "chatSaves", name = "Say when a clip is saved", description = "Prints a line in your chatbox each time a clip or screenshot is saved (only you see it)", section = clips, position = 7)
	default boolean chatSaves()
	{
		return true;
	}

	@ConfigItem(keyName = "lingo", name = "OSRS lingo", description = "Adds a short line like ezpz or </3 when one fits the fight", section = debrief, position = 4)
	default boolean lingo()
	{
		return true;
	}

	@ConfigItem(keyName = "moveNames", name = "Move nicknames", description = "Give attack animations a name so debriefs read better. Format: 14322=swipe, 14326=donut", section = debrief, position = 5)
	default String moveNames()
	{
		return "";
	}
}
