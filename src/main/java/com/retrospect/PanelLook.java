package com.retrospect;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.util.Filepath;

/** The player's own wording and colours for the panel, with the stock look to fall back on. */
final class PanelLook
{
	static final int MAX_LABEL = 40;

	/** Heading key -> stock text, in the order the panel shows them. */
	static final Map<String, String> STOCK_LABELS = new LinkedHashMap<>();
	/** Colour key -> stock hex. */
	static final Map<String, String> STOCK_COLORS = new LinkedHashMap<>();
	/** Colour key -> what it is called on the Customize tab. */
	static final Map<String, String> COLOR_NAMES = new LinkedHashMap<>();

	static
	{
		STOCK_LABELS.put("title", "RETROSPECT");
		STOCK_LABELS.put("takeaway", "THE TAKEAWAY");
		STOCK_LABELS.put("tip", "TRY THIS");
		STOCK_LABELS.put("strong", "STRONG");
		STOCK_LABELS.put("weak", "WEAK");
		STOCK_LABELS.put("numbers", "NUMBERS");
		STOCK_LABELS.put("story", "WHAT HAPPENED");
		STOCK_LABELS.put("recent", "RECENT FIGHTS");

		STOCK_COLORS.put("accent", "F0B93A");
		STOCK_COLORS.put("strong", "8BD17C");
		STOCK_COLORS.put("weak", "E8765A");
		STOCK_COLORS.put("text", "E6D8BC");

		COLOR_NAMES.put("accent", "Headings");
		COLOR_NAMES.put("strong", "Strong");
		COLOR_NAMES.put("weak", "Weak");
		COLOR_NAMES.put("text", "Body text");
	}

	private Map<String, String> labels = new LinkedHashMap<>();
	private Map<String, String> colors = new LinkedHashMap<>();

	String label(String key)
	{
		String own = labels == null ? null : labels.get(key);
		return own != null && !own.trim().isEmpty() ? own : STOCK_LABELS.getOrDefault(key, key);
	}

	/** Blank text puts the stock heading back. */
	void setLabel(String key, String text)
	{
		if (!STOCK_LABELS.containsKey(key))
		{
			return;
		}
		String clean = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").trim();
		if (clean.length() > MAX_LABEL)
		{
			clean = clean.substring(0, MAX_LABEL).trim();
		}
		if (clean.isEmpty() || clean.equals(STOCK_LABELS.get(key)))
		{
			labels.remove(key);
		}
		else
		{
			labels.put(key, clean);
		}
	}

	Color color(String key)
	{
		String hex = colors == null ? null : colors.get(key);
		Color own = parse(hex);
		return own != null ? own : parse(STOCK_COLORS.getOrDefault(key, "FFFFFF"));
	}

	void setColor(String key, Color c)
	{
		if (!STOCK_COLORS.containsKey(key) || c == null)
		{
			return;
		}
		String hex = String.format("%06X", c.getRGB() & 0xFFFFFF);
		if (hex.equals(STOCK_COLORS.get(key)))
		{
			colors.remove(key);
		}
		else
		{
			colors.put(key, hex);
		}
	}

	boolean isStock()
	{
		return labels.isEmpty() && colors.isEmpty();
	}

	void reset()
	{
		labels.clear();
		colors.clear();
	}

	private static Color parse(String hex)
	{
		if (hex == null || !hex.matches("[0-9a-fA-F]{6}"))
		{
			return null;
		}
		return new Color(Integer.parseInt(hex, 16));
	}

	/** A missing or damaged file just means the stock look. */
	static PanelLook load(Filepath file, Gson gson)
	{
		PanelLook look = null;
		try
		{
			if (file.isFile())
			{
				try (Reader r = file.openBufferedReader())
				{
					look = gson.fromJson(r, PanelLook.class);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			look = null;
		}
		if (look == null)
		{
			look = new PanelLook();
		}
		if (look.labels == null)
		{
			look.labels = new LinkedHashMap<>();
		}
		if (look.colors == null)
		{
			look.colors = new LinkedHashMap<>();
		}
		return look;
	}

	void save(Filepath file, Gson gson) throws IOException
	{
		file.getParent().createDirectories();
		file.write(gson.toJson(this));
	}
}
