package com.retrospect;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Past fights, kept in one small file so they survive restarts. */
final class FightHistory
{
	static final int MAX_ENTRIES = 1000;

	private final File file;
	private final Gson gson;
	private List<FightSummary> entries = new ArrayList<>();

	FightHistory(File file, Gson gson)
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
			String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			List<FightSummary> read = gson.fromJson(json, new TypeToken<List<FightSummary>>()
			{
			}.getType());
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
			File aside = new File(file.getParentFile(), file.getName() + ".unreadable");
			try
			{
				Files.copy(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
		file.getParentFile().mkdirs();
		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		Files.write(tmp.toPath(), gson.toJson(entries).getBytes(StandardCharsets.UTF_8));
		Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
