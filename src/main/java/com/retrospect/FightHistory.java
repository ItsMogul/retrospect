package com.retrospect;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;

/** Past fights, kept in one small file so they survive restarts. */
final class FightHistory
{
	static final int MAX_ENTRIES = 1000;

	private final Filepath file;
	private final Gson gson;
	private List<FightSummary> entries = new ArrayList<>();

	FightHistory(Filepath file, Gson gson)
	{
		this.file = file;
		this.gson = gson;
	}

	synchronized void load()
	{
		entries = new ArrayList<>();
		if (!file.isFile())
		{
			return;
		}
		try
		{
			List<FightSummary> read;
			try (Reader r = file.openBufferedReader())
			{
				read = gson.fromJson(r, new TypeToken<List<FightSummary>>()
				{
				}.getType());
			}
			if (read != null)
			{
				for (FightSummary s : read)
				{
					if (s != null && s.target != null)
					{
						entries.add(s);
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			// A damaged history file should never stop the plugin; start fresh and keep the old one aside
			Filepath aside = file.getParent().join(file.getFileName() + ".unreadable");
			try
			{
				file.copyTo(aside, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException ignored)
			{
				// nothing more to do
			}
		}
	}

	synchronized void add(FightSummary s) throws IOException
	{
		entries.add(s);
		while (entries.size() > MAX_ENTRIES)
		{
			entries.remove(0);
		}
		file.getParent().createDirectories();
		Filepath tmp = file.getParent().join(file.getFileName() + ".tmp");
		tmp.write(gson.toJson(entries));
		tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Earlier fights against the same target, oldest first. */
	synchronized List<FightSummary> against(String target)
	{
		List<FightSummary> out = new ArrayList<>();
		for (FightSummary s : entries)
		{
			if (s.target.equalsIgnoreCase(target))
			{
				out.add(s);
			}
		}
		return out;
	}

	/** Most recent fights first. */
	synchronized List<FightSummary> recent(int limit)
	{
		List<FightSummary> out = new ArrayList<>();
		for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--)
		{
			out.add(entries.get(i));
		}
		return out;
	}
}
