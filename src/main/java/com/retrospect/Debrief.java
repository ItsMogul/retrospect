package com.retrospect;

import java.util.ArrayList;
import java.util.List;

/** The readable result of one fight: one takeaway first, the detail underneath. */
final class Debrief
{
	String title;
	/** A short line of OSRS banter for the situation, or null. */
	String vibe;
	/** "Fought: A, B" for player fights, or null. */
	String fought;
	String takeaway;
	/** One concrete thing to try next time, tied to the takeaway. May be null. */
	String tip;
	final List<String> strong = new ArrayList<>();
	final List<String> weak = new ArrayList<>();
	final List<String> stats = new ArrayList<>();
	/** Two columns: your moments on the left, theirs on the right. */
	final List<String> timeline = new ArrayList<>();
	String clip;

	String toText()
	{
		StringBuilder sb = new StringBuilder();
		String nl = System.lineSeparator();
		sb.append(title);
		if (vibe != null)
		{
			sb.append("  ").append(vibe);
		}
		sb.append(nl);
		if (fought != null)
		{
			sb.append(fought).append(nl);
		}
		sb.append(nl).append("THE TAKEAWAY").append(nl).append(takeaway).append(nl);
		if (tip != null)
		{
			sb.append(nl).append("TRY THIS").append(nl).append(tip).append(nl);
		}
		section(sb, "STRONG", strong, nl);
		section(sb, "WEAK", weak, nl);
		section(sb, "NUMBERS", stats, nl);
		sb.append(nl).append("WHAT HAPPENED").append(nl);
		for (String s : timeline)
		{
			sb.append(s).append(nl);
		}
		if (clip != null)
		{
			sb.append(nl).append("Clip: ").append(clip).append(nl);
		}
		return sb.toString();
	}

	private static void section(StringBuilder sb, String name, List<String> lines, String nl)
	{
		if (lines.isEmpty())
		{
			return;
		}
		sb.append(nl).append(name).append(nl);
		for (String s : lines)
		{
			sb.append("- ").append(s).append(nl);
		}
	}
}
