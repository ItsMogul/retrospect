package com.retrospect;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One folder per thing you fight. In Serious mode a fight's debrief and clips are filed
 * into its folder; each folder carries its own switches for what gets kept.
 */
final class FightFolders
{
	static final class Folder
	{
		String key;
		String name;
		/** The game's id for the monster the first time it was seen, or -1. */
		int npcId = -1;
		/** Made by the player with "+ Create custom". */
		boolean custom;
		/** For custom folders: the names that are filed here, separated by commas. */
		String targets;
		/** Folder name on disk. */
		String dir;
		boolean log = true;
		boolean clipKills;
		boolean clipDeaths = true;
		int fights;
		int kills;
		int deaths;
		int bestTicks;

		boolean claims(String target)
		{
			if (!custom || targets == null || target == null)
			{
				return false;
			}
			for (String t : targets.split(","))
			{
				if (!t.trim().isEmpty() && t.trim().equalsIgnoreCase(target.trim()))
				{
					return true;
				}
			}
			return false;
		}

		String statsLine()
		{
			if (fights == 0)
			{
				return "No fights filed yet";
			}
			return fights + (fights == 1 ? " fight" : " fights") + " \u00b7 " + kills + (kills == 1 ? " kill" : " kills")
				+ " \u00b7 " + deaths + (deaths == 1 ? " death" : " deaths")
				+ (bestTicks > 0 ? " \u00b7 best " + DebriefBuilder.clock(bestTicks) : "");
		}
	}

	private final File file;
	private final Gson gson;
	private List<Folder> folders = new ArrayList<>();

	FightFolders(File file, Gson gson)
	{
		this.file = file;
		this.gson = gson;
	}

	synchronized void load()
	{
		folders = new ArrayList<>();
		try
		{
			if (file.isFile())
			{
				List<Folder> read = gson.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
					new TypeToken<List<Folder>>()
					{
					}.getType());
				if (read != null)
				{
					for (Folder f : read)
					{
						if (f != null && f.key != null && f.name != null && f.dir != null)
						{
							folders.add(f);
						}
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			folders = new ArrayList<>();
		}
	}

	synchronized void save() throws IOException
	{
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), gson.toJson(folders).getBytes(StandardCharsets.UTF_8));
	}

	synchronized List<Folder> all()
	{
		return new ArrayList<>(folders);
	}

	/**
	 * The folder a fight belongs in. A custom folder that names the target wins; every player
	 * fight shares one PvP folder; otherwise each monster gets its own, made the first time
	 * you fight it.
	 *
	 * @param create make the folder if it does not exist yet
	 */
	synchronized Folder forFight(String target, int npcId, boolean isPlayer, boolean create)
	{
		if (target == null)
		{
			return null;
		}
		for (Folder f : folders)
		{
			if (f.claims(target))
			{
				return f;
			}
		}
		String key = isPlayer ? "pvp" : "name:" + target.toLowerCase(Locale.ROOT);
		for (Folder f : folders)
		{
			if (f.key.equals(key))
			{
				return f;
			}
		}
		if (!create)
		{
			return null;
		}
		Folder f = new Folder();
		f.key = key;
		f.name = isPlayer ? "PvP" : target;
		f.npcId = isPlayer ? -1 : npcId;
		f.dir = uniqueDir(isPlayer ? "PvP" : target + (npcId >= 0 ? " [" + npcId + "]" : ""));
		folders.add(f);
		return f;
	}

	/** Returns the new folder, or null if the name is empty. */
	synchronized Folder createCustom(String name, String targets)
	{
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty())
		{
			return null;
		}
		Folder f = new Folder();
		f.key = "custom:" + System.nanoTime();
		f.name = clean.length() > 40 ? clean.substring(0, 40) : clean;
		f.custom = true;
		f.targets = targets == null ? "" : targets.trim();
		f.dir = uniqueDir(f.name);
		folders.add(0, f);
		return f;
	}

	synchronized void record(Folder f, Encounter e)
	{
		f.fights++;
		if (e.outcome == Encounter.Outcome.KILL)
		{
			f.kills++;
			if (f.bestTicks == 0 || e.durationTicks() < f.bestTicks)
			{
				f.bestTicks = e.durationTicks();
			}
		}
		else if (e.outcome == Encounter.Outcome.DEATH)
		{
			f.deaths++;
		}
	}

	/** A name that is safe on disk and not already used by another folder. */
	private String uniqueDir(String wanted)
	{
		String base = dirName(wanted);
		String name = base;
		int n = 2;
		while (taken(name))
		{
			name = base + " (" + n++ + ")";
		}
		return name;
	}

	private boolean taken(String dir)
	{
		for (Folder f : folders)
		{
			if (f.dir.equalsIgnoreCase(dir))
			{
				return true;
			}
		}
		return false;
	}

	/** Keeps letters, digits, spaces and a few safe marks. Never empty, never a path. */
	static String dirName(String wanted)
	{
		String s = wanted == null ? "" : wanted.replaceAll("[^A-Za-z0-9 _\\-\\[\\]'()]+", " ").replaceAll("\\s+", " ").trim();
		while (s.endsWith(".") || s.endsWith(" "))
		{
			s = s.substring(0, s.length() - 1);
		}
		if (s.length() > 50)
		{
			s = s.substring(0, 50).trim();
		}
		return s.isEmpty() ? "Folder" : s;
	}
}
