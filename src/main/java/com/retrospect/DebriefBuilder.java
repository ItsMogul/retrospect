package com.retrospect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads a finished fight and says the one thing worth knowing first, what was strong,
 * what was weak, and one thing to try next time.
 * Pure logic: no game objects, so every rule here is covered by tests.
 */
final class DebriefBuilder
{
	static final int MAX_TIMELINE_LINES = 28;
	private static final int LEFT_WIDTH = 10;

	/** Special attacks recognisable by their animation, so player fights read properly out of the box. */
	static final Map<String, String> KNOWN_MOVES = new HashMap<>();

	static
	{
		KNOWN_MOVES.put("1062", "dds spec");
		KNOWN_MOVES.put("1378", "dwh spec");
		KNOWN_MOVES.put("1667", "g maul spec");
		KNOWN_MOVES.put("7514", "claw spec");
		KNOWN_MOVES.put("7515", "d sword spec");
		KNOWN_MOVES.put("7638", "zgs spec");
		KNOWN_MOVES.put("7639", "zgs spec");
		KNOWN_MOVES.put("7640", "sgs spec");
		KNOWN_MOVES.put("7642", "bgs spec");
		KNOWN_MOVES.put("7644", "ags spec");
		KNOWN_MOVES.put("7645", "ags spec");
		KNOWN_MOVES.put("10427", "ags spec");
		KNOWN_MOVES.put("9171", "ancient gs spec");
		KNOWN_MOVES.put("11124", "elder maul spec");
		KNOWN_MOVES.put("11140", "burning claws spec");
		KNOWN_MOVES.put("11240", "voidwaker spec");
		KNOWN_MOVES.put("11275", "voidwaker spec");
	}

	/** A weak point and, where there is one, the thing to try about it. */
	private static final class Point
	{
		final String text;
		final String tip;

		Point(String text, String tip)
		{
			this.text = text;
			this.tip = tip;
		}
	}

	private DebriefBuilder()
	{
	}

	static Debrief build(Encounter e, List<FightSummary> past, Function<String, String> moveNames)
	{
		return build(e, past, moveNames, true);
	}

	/**
	 * @param past      earlier fights against the same target (not including this one)
	 * @param moveNames turns a move key such as "14322" into a nickname, or returns null
	 * @param lingo     add a short line of OSRS banter where one fits
	 */
	static Debrief build(Encounter e, List<FightSummary> past, Function<String, String> moveNames, boolean lingo)
	{
		Debrief d = new Debrief();
		String length = clock(e.durationTicks());
		switch (e.outcome)
		{
			case KILL:
				d.title = e.target + ": kill in " + length + (e.lootValue > 0 ? " (+" + gp(e.lootValue) + ")" : "");
				break;
			case DEATH:
				d.title = e.target + ": you died after " + length;
				break;
			default:
				d.title = e.target + ": fight ended after " + length + " (no kill)";
		}
		if (!e.opponents.isEmpty())
		{
			d.fought = "Fought: " + String.join(", ", e.opponents);
		}

		List<FightSummary> kills = new ArrayList<>();
		for (FightSummary s : past)
		{
			if (s.isKill())
			{
				kills.add(s);
			}
		}
		boolean record = isRecord(e, kills);

		// ---------- weak ----------
		List<Point> weak = new ArrayList<>();
		if (e.outcome == Encounter.Outcome.DEATH)
		{
			weak.add(death(e, moveNames));
		}
		Point source = source(e, moveNames);
		Point idle = idle(e);
		boolean tookALot = e.maxHp > 0 ? e.taken >= e.maxHp : e.taken >= 99;
		if (source != null && tookALot)
		{
			weak.add(source);
			addIf(weak, idle);
		}
		else
		{
			addIf(weak, idle);
			addIf(weak, source);
		}
		addIf(weak, rhythm(e));
		addIf(weak, accuracyDrop(e, past));
		if (e.outcome != Encounter.Outcome.DEATH && e.lowDips >= 2)
		{
			weak.add(new Point("You dropped under a quarter health " + e.lowDips + " times and got away with it.",
				"Eat when you fall under " + e.eatFloor() + " HP. The biggest hit here was " + e.maxTaken + ", so anything lower is a coin flip."));
		}
		if (e.earlyEats >= 3)
		{
			weak.add(new Point("You healed " + e.earlyEats + " times while already above 80% health.",
				"Hold your food until you are under " + e.eatFloor() + " HP. Every early bite costs you an attack."));
		}

		// ---------- strong ----------
		List<String> strong = new ArrayList<>();
		if (record)
		{
			strong.add("Fastest kill you have on record: " + length + " (previous best " + clock(best(kills)) + ").");
		}
		if (e.outcome == Encounter.Outcome.KILL && e.taken == 0 && e.hits > 0)
		{
			strong.add("No damage taken. Flawless.");
		}
		if (e.outcome == Encounter.Outcome.KILL && e.targetIsPlayer && e.lootValue > 0)
		{
			strong.add("Killed " + e.target + " for " + gp(e.lootValue) + ".");
		}
		int attacks = e.attackTicks.size();
		int missed = e.missedAttacks();
		if (e.expectedSpeed() > 0 && attacks >= 10 && missed * 100 / (attacks + missed) <= 5)
		{
			strong.add("You stayed on the target: almost no idle time across " + attacks + " attacks.");
		}
		int specHit = bestSpecHit(e);
		if (specHit > 0)
		{
			strong.add("Your special attack landed for " + specHit + ".");
		}
		if (e.maxHp > 0 && e.taken * 2 >= e.maxHp && e.lowestHp * 5 > e.maxHp * 2)
		{
			strong.add("You took " + e.taken + " damage and never fell under " + e.lowestHp + " HP. Your healing was on time.");
		}
		addIf(strong, accuracyUp(e, past));
		addIf(strong, lessDamage(e, kills));

		// ---------- the takeaway ----------
		if (!weak.isEmpty())
		{
			Point first = weak.remove(0);
			d.takeaway = first.text;
			d.tip = first.tip;
		}
		else if (!strong.isEmpty())
		{
			d.takeaway = strong.remove(0);
		}
		else
		{
			d.takeaway = e.outcome == Encounter.Outcome.KILL
				? "Clean fight. Nothing stands out to fix."
				: "Not enough happened in this fight to say anything useful.";
		}
		for (Point p : weak)
		{
			if (d.weak.size() < 3)
			{
				d.weak.add(p.tip == null ? p.text : p.text + " Try: " + p.tip);
			}
		}
		for (String s : strong)
		{
			if (d.strong.size() < 3)
			{
				d.strong.add(s);
			}
		}
		if (lingo)
		{
			d.vibe = vibe(e, record);
		}

		stats(e, d, kills);
		timeline(e, d, moveNames);
		return d;
	}

	private static <T> void addIf(List<T> list, T item)
	{
		if (item != null)
		{
			list.add(item);
		}
	}

	// ---------- weak points ----------

	private static Point death(Encounter e, Function<String, String> moveNames)
	{
		StringBuilder sb = new StringBuilder("You died ").append(clock(e.durationTicks())).append(" in");
		if (e.targetHpPct >= 0)
		{
			sb.append(" with ").append(e.target).append(" at ").append(e.targetHpPct).append("% health");
		}
		sb.append(".");

		// What the last few seconds looked like
		int window = 10;
		Map<String, Integer> byMove = new LinkedHashMap<>();
		int lost = 0;
		for (Encounter.Event ev : e.events)
		{
			if (ev.kind == Encounter.Kind.TOOK && ev.tick >= e.endTick - window)
			{
				lost += ev.amount;
				byMove.merge(ev.note, ev.amount, Integer::sum);
			}
		}
		if (lost > 0)
		{
			sb.append(" The last ").append(window * 6 / 10).append(" seconds cost you ").append(lost).append(" HP");
			if (byMove.size() == 1)
			{
				sb.append(", all ").append(during(byMove.keySet().iterator().next(), moveNames));
			}
			else if (byMove.size() <= 3)
			{
				List<String> parts = new ArrayList<>();
				for (Map.Entry<String, Integer> en : byMove.entrySet())
				{
					parts.add(en.getValue() + " " + during(en.getKey(), moveNames));
				}
				sb.append(": ").append(String.join(", ", parts));
			}
			sb.append(".");
		}
		String tip = "Eat when you fall under " + e.eatFloor() + " HP. The biggest hit here was " + e.maxTaken
			+ ", so anything lower is a coin flip.";
		if (e.heals == 0)
		{
			sb.append(" You did not heal at all.");
		}
		else
		{
			int gap = (int) Math.round((e.endTick - e.lastHealTick) * 0.6);
			if (gap >= 5)
			{
				sb.append(" Your last heal was ").append(gap).append(" seconds before that.");
			}
		}
		return new Point(sb.toString(), e.maxTaken > 0 ? tip : null);
	}

	/** One move doing a large share of the damage. */
	private static Point source(Encounter e, Function<String, String> moveNames)
	{
		String worst = e.worstMove();
		if (worst == null || e.taken <= 0)
		{
			return null;
		}
		int[] agg = e.takenByMove.get(worst);
		int share = (int) Math.round(100.0 * agg[1] / e.taken);
		int floor = e.maxHp > 0 ? Math.max(20, e.maxHp * 3 / 10) : 30;
		if (share < 40 || agg[1] < floor || "other".equals(worst))
		{
			return null;
		}
		// Point at the single worst moment so there is something specific to rewatch
		int at = -1;
		int biggest = 0;
		for (Encounter.Event ev : e.events)
		{
			if (ev.kind == Encounter.Kind.TOOK && worst.equals(ev.note) && ev.amount > biggest)
			{
				biggest = ev.amount;
				at = ev.tick;
			}
		}
		String label = moveLabel(worst, moveNames);
		if (e.targetIsPlayer)
		{
			return new Point(agg[1] + " of the " + e.taken + " damage you took (" + share + "%) came from " + label
				+ ", in " + agg[0] + (agg[0] == 1 ? " hit" : " hits") + ".",
				"Eat up before they can do it again: the biggest of those was " + biggest + ".");
		}
		String rewatch = "Rewatch " + clock(at - e.startTick) + ", where " + label + " hit you for " + biggest
			+ ", and find what it does right before it lands.";
		if (namedMove(worst, moveNames) == null && !"other".equals(worst) && !worst.startsWith("@"))
		{
			rewatch += " Call it something in Move nicknames (" + worst + "=your name) and it will show up by name next time.";
		}
		return new Point(agg[1] + " of the " + e.taken + " damage you took (" + share + "%) landed " + during(worst, moveNames)
			+ ", in " + agg[0] + (agg[0] == 1 ? " hit" : " hits") + ". That is the first thing to fix.", rewatch);
	}

	/**
	 * Being a little late on every attack. Idle time only notices whole attacks lost in
	 * one lump; a tick dropped every swing never shows up there, but over a fight it is
	 * the same damage. Only possible because the weapon's real speed is known.
	 */
	private static Point rhythm(Encounter e)
	{
		int weapon = e.weaponSpeed();
		int attacks = e.attackTicks.size();
		if (weapon <= 0 || attacks < 5)
		{
			return null;
		}
		double gap = e.averageGap();
		double late = gap - weapon;
		if (late < 0.5)
		{
			return null;
		}
		int percent = (int) Math.round(late / weapon * 100);
		double avgHit = e.hits == 0 ? 0 : (double) e.dealt / e.hits;
		int lost = (int) Math.round(e.idleTicks() / (double) weapon * avgHit);
		String cost = lost > 0 ? " Across this fight that is about " + lost + " damage you never threw." : "";
		return new Point(String.format("You attacked every %.1f ticks with a %d tick weapon: %d%% slower than it can go.",
			gap, weapon, percent) + cost,
			"Click your next attack while the current one is still swinging, rather than waiting to see it land.");
	}

	/** Time spent not attacking. */
	private static Point idle(Encounter e)
	{
		int missed = e.missedAttacks();
		int attacks = e.attackTicks.size();
		if (missed < 3 || attacks == 0 || missed * 100 / (attacks + missed) < 15)
		{
			return null;
		}
		double avgHit = e.hits == 0 ? 0 : (double) e.dealt / e.hits;
		int lostDamage = (int) Math.round(missed * avgHit);
		int idleTicks = e.idleTicks();
		int idleSecs = (int) Math.round(idleTicks * 0.6);
		int eatTicks = e.eatIdleTicks();
		String tip;
		if (eatTicks * 5 >= idleTicks * 2)
		{
			tip = "About " + Math.round(eatTicks * 0.6) + " of those seconds were around healing. Eat right after your attack lands, "
				+ "or combo eat (food, then a karambwan straight after), so healing stops costing you swings.";
		}
		else if (e.takenByMove.size() > 1 || (e.takenByMove.size() == 1 && !e.takenByMove.containsKey("other")))
		{
			tip = "Click back onto the target the moment each mechanic ends, before you fix anything else.";
		}
		else
		{
			tip = "Nothing in this fight forced that gap, so it is all yours to take back: reclick the target "
				+ "as soon as you are in range again.";
		}
		return new Point("You went about " + idleSecs + " seconds without attacking, room for roughly " + missed
			+ " more attacks (about " + lostDamage + " damage). Some of that the fight forces on you; the rest is free damage.", tip);
	}

	private static int usualAccuracy(List<FightSummary> past)
	{
		List<FightSummary> last = past.subList(Math.max(0, past.size() - 10), past.size());
		double sum = 0;
		for (FightSummary s : last)
		{
			sum += s.accuracyPct;
		}
		return (int) Math.round(sum / last.size());
	}

	private static Point accuracyDrop(Encounter e, List<FightSummary> past)
	{
		if (e.hits < 20 || past.size() < 3)
		{
			return null;
		}
		int usual = usualAccuracy(past);
		if (usual - e.accuracyPct() >= 10)
		{
			return new Point("Only " + e.accuracyPct() + "% of your hits landed. Your usual here is " + usual + "%.",
				"Check your gear, your boosts and the attack style you had on before the next one.");
		}
		return null;
	}

	// ---------- strong points ----------

	private static String accuracyUp(Encounter e, List<FightSummary> past)
	{
		if (e.hits < 20 || past.size() < 3)
		{
			return null;
		}
		int usual = usualAccuracy(past);
		return e.accuracyPct() - usual >= 5
			? e.accuracyPct() + "% of your hits landed, up from your usual " + usual + "%." : null;
	}

	private static String lessDamage(Encounter e, List<FightSummary> kills)
	{
		if (e.outcome != Encounter.Outcome.KILL || kills.size() < 3 || e.taken == 0)
		{
			return null;
		}
		List<FightSummary> last = kills.subList(Math.max(0, kills.size() - 10), kills.size());
		double sum = 0;
		for (FightSummary s : last)
		{
			sum += s.taken;
		}
		long usual = Math.round(sum / last.size());
		return usual >= 20 && e.taken * 10 <= usual * 7
			? "You took " + e.taken + " damage against your usual " + usual + "." : null;
	}

	private static boolean isRecord(Encounter e, List<FightSummary> kills)
	{
		return e.outcome == Encounter.Outcome.KILL && kills.size() >= 2 && e.durationTicks() < best(kills);
	}

	private static int best(List<FightSummary> kills)
	{
		int best = Integer.MAX_VALUE;
		for (FightSummary s : kills)
		{
			best = Math.min(best, s.durationTicks);
		}
		return best;
	}

	/** The biggest hit that landed within a few ticks of a special attack, if it was a good one. */
	private static int bestSpecHit(Encounter e)
	{
		int bestHit = 0;
		for (Encounter.Event spec : e.events)
		{
			if (spec.kind != Encounter.Kind.SPEC)
			{
				continue;
			}
			for (Encounter.Event hit : e.events)
			{
				if (hit.kind == Encounter.Kind.DEALT && hit.tick >= spec.tick && hit.tick <= spec.tick + 6)
				{
					bestHit = Math.max(bestHit, hit.amount);
				}
			}
		}
		return bestHit >= 25 && bestHit * 5 >= e.maxHit * 4 ? bestHit : 0;
	}

	// ---------- banter ----------

	/** One short line for the situation. Decided by what happened, never at random. */
	static String vibe(Encounter e, boolean record)
	{
		if (e.outcome == Encounter.Outcome.DEATH)
		{
			if (e.targetHpPct >= 1 && e.targetHpPct <= 10)
			{
				return "so close </3";
			}
			return e.targetIsPlayer ? "gf </3" : "</3";
		}
		if (e.outcome != Encounter.Outcome.KILL)
		{
			return null;
		}
		if (record)
		{
			return "gz, new pb";
		}
		if (e.lowestHp >= 0 && e.maxHp > 0 && e.lowestHp <= Math.max(5, e.maxHp / 10))
		{
			return "clutch";
		}
		if (e.taken == 0)
		{
			return "ezpz";
		}
		return e.targetIsPlayer ? "gf" : null;
	}

	// ---------- numbers ----------

	private static void stats(Encounter e, Debrief d, List<FightSummary> kills)
	{
		if (e.hits > 0)
		{
			d.stats.add(String.format("Dealt %d in %s (%.1f per second). %d%% of %d hits landed, biggest %d.",
				e.dealt, clock(e.durationTicks()), e.dps(), e.accuracyPct(), e.hits, e.maxHit));
		}
		else
		{
			d.stats.add("You dealt no damage.");
		}
		String took = "Took " + e.taken + " in " + e.hitsTaken + (e.hitsTaken == 1 ? " hit" : " hits") + ", biggest " + e.maxTaken + ".";
		if (e.maxHp > 0 && e.taken > 0)
		{
			double bars = (double) e.taken / e.maxHp;
			took += bars < 0.1
				? " That barely scratched you."
				: String.format(" That is %.1f of your health bars.", bars);
		}
		if (e.lowestHp >= 0 && e.taken > 0)
		{
			took += " Lowest you got: " + e.lowestHp + " HP.";
		}
		d.stats.add(took);
		d.stats.add("Healed " + e.healed + " in " + e.heals + (e.heals == 1 ? " heal" : " heals") + ". Prayer used: "
			+ e.prayerUsed + ". Special attacks: " + e.specs + ".");
		// The hit count above is hitsplats (a spec can be two); this line is about cadence,
		// so it says how often you swung rather than repeating a second, different total.
		int weapon = e.weaponSpeed();
		double gap = e.averageGap();
		if (weapon > 0 && gap > 0)
		{
			String cadence = String.format("You swung every %.1f ticks; the weapon goes every %d.", gap, weapon);
			if (gap <= weapon + 0.25)
			{
				cadence = String.format("You swung every %.1f ticks, which is the weapon's %d.", gap, weapon);
			}
			d.stats.add(cadence + " Time not attacking: " + Math.round(e.idleTicks() * 0.6) + "s.");
		}
		else if (e.attackSpeed() > 0)
		{
			d.stats.add("You swung every " + e.attackSpeed() + " ticks while you were on it. Time not attacking: "
				+ Math.round(e.idleTicks() * 0.6) + "s.");
		}
		if (e.outcome == Encounter.Outcome.KILL && kills.size() >= 3)
		{
			List<FightSummary> last = kills.subList(Math.max(0, kills.size() - 10), kills.size());
			double time = 0;
			double taken = 0;
			for (FightSummary s : last)
			{
				time += s.durationTicks;
				taken += s.taken;
			}
			d.stats.add("Against your last " + last.size() + " kills: time " + clock(e.durationTicks()) + " (average "
				+ clock((int) Math.round(time / last.size())) + "), damage taken " + e.taken + " (average "
				+ Math.round(taken / last.size()) + ").");
		}
	}

	// ---------- you versus them ----------

	private static final class Line
	{
		final int tick;
		final String you;
		final String them;
		/** Lines with a weight can be dropped (smallest first) when the story runs long. */
		final int weight;

		Line(int tick, String you, String them, int weight)
		{
			this.tick = tick;
			this.you = you;
			this.them = them;
			this.weight = weight;
		}
	}

	private static void timeline(Encounter e, Debrief d, Function<String, String> moveNames)
	{
		// Oldest first, with the ending always last (List.sort keeps same-tick events in the order they came)
		List<Encounter.Event> ev = new ArrayList<>(e.events);
		ev.sort(Comparator.comparingInt(x -> x.kind == Encounter.Kind.END ? Integer.MAX_VALUE : x.tick));

		List<Integer> specTicks = new ArrayList<>();
		for (Encounter.Event x : ev)
		{
			if (x.kind == Encounter.Kind.SPEC)
			{
				specTicks.add(x.tick);
			}
		}
		int bigHit = Math.max(20, e.maxHit * 7 / 10);

		List<Line> lines = new ArrayList<>();
		String ending = null;
		int endTick = e.endTick;
		for (int i = 0; i < ev.size(); i++)
		{
			Encounter.Event x = ev.get(i);
			switch (x.kind)
			{
				case TOOK:
				{
					// Hits from the same move within a couple of ticks read as one moment
					int sum = x.amount;
					int n = 1;
					while (i + 1 < ev.size() && ev.get(i + 1).kind == Encounter.Kind.TOOK
						&& ev.get(i + 1).tick - x.tick <= 2 && ev.get(i + 1).note.equals(x.note))
					{
						sum += ev.get(++i).amount;
						n++;
					}
					// The main opponent's ordinary hits need no name: the column is already theirs
					String named = namedMove(x.note, moveNames);
					String what;
					if (x.note != null && x.note.startsWith("@"))
					{
						// Another player: their name, unless they are the one you are fighting
						what = x.note.equals("@" + e.target) ? "hit " + sum : sum + " " + x.note.substring(1);
					}
					else
					{
						what = named == null ? "hit " + sum : sum + " " + named;
					}
					lines.add(new Line(x.tick, "", what + (n > 1 ? " x" + n : ""), sum));
					break;
				}
				case DEALT:
				{
					boolean fromSpec = false;
					for (int s : specTicks)
					{
						fromSpec |= x.tick >= s && x.tick <= s + 6;
					}
					if (fromSpec)
					{
						lines.add(new Line(x.tick, "spec " + x.amount, "", 0));
					}
					else if (x.amount >= bigHit)
					{
						lines.add(new Line(x.tick, "hit " + x.amount, "", x.amount));
					}
					break;
				}
				case HEAL:
				{
					int sum = x.amount;
					while (i + 1 < ev.size() && ev.get(i + 1).kind == Encounter.Kind.HEAL && ev.get(i + 1).tick - x.tick <= 2)
					{
						sum += ev.get(++i).amount;
					}
					lines.add(new Line(x.tick, "ate +" + sum, "", sum));
					break;
				}
				case SPEC:
				{
					// Only worth its own line if no hit followed it
					boolean landed = false;
					for (Encounter.Event h : ev)
					{
						landed |= h.kind == Encounter.Kind.DEALT && h.tick >= x.tick && h.tick <= x.tick + 6;
					}
					if (!landed)
					{
						lines.add(new Line(x.tick, "spec miss", "", 0));
					}
					break;
				}
				case LOW_HP:
					lines.add(new Line(x.tick, x.amount + " hp left", "", 0));
					break;
				case LOOT:
					break; // shown on the ending line
				case TARGET_HP:
					lines.add(new Line(x.tick, "", "at " + x.amount + "%", 0));
					break;
				default:
					endTick = x.tick;
					ending = e.outcome == Encounter.Outcome.KILL ? "KILL" : e.outcome == Encounter.Outcome.DEATH ? "YOU DIED" : "fight ended";
			}
		}
		int dropped = 0;
		while (lines.size() > MAX_TIMELINE_LINES - 2)
		{
			Line smallest = lines.stream().filter(l -> l.weight > 0).min(Comparator.comparingInt(l -> l.weight)).orElse(null);
			if (smallest == null)
			{
				break;
			}
			lines.remove(smallest);
			dropped++;
		}
		String them = e.target.length() > LEFT_WIDTH ? e.target.substring(0, LEFT_WIDTH) : e.target;
		d.timeline.add("     " + pad("YOU") + "| " + them.toUpperCase());
		for (Line l : lines)
		{
			d.timeline.add((clock(l.tick - e.startTick) + " " + pad(l.you) + "| " + l.them).trim());
		}
		if (ending != null)
		{
			d.timeline.add(clock(endTick - e.startTick) + " " + ending
				+ (e.outcome == Encounter.Outcome.KILL && e.lootValue > 0 ? " +" + gp(e.lootValue) : ""));
		}
		if (dropped > 0)
		{
			d.timeline.add("(" + dropped + " smaller moments left out)");
		}
	}

	private static String pad(String s)
	{
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < LEFT_WIDTH)
		{
			sb.append(' ');
		}
		return sb.toString();
	}

	// ---------- wording helpers ----------

	/** "during swipe" / "during move 14322" / "from untracked damage". */
	private static String during(String key, Function<String, String> moveNames)
	{
		if ("other".equals(key))
		{
			return "from untracked damage";
		}
		if (key.startsWith("@"))
		{
			return "from " + key.substring(1);
		}
		return "during " + moveLabel(key, moveNames);
	}

	/** The move's name if it has one (nickname first, then the built-in list), else null. */
	static String namedMove(String key, Function<String, String> moveNames)
	{
		if (key == null || "other".equals(key) || key.startsWith("@"))
		{
			return null;
		}
		String nick = moveNames == null ? null : moveNames.apply(key);
		if (nick != null && !nick.isEmpty())
		{
			return nick;
		}
		return KNOWN_MOVES.get(key);
	}

	/** The player's own nickname wins, then the built-in special attack names, then a plain description. */
	static String moveLabel(String key, Function<String, String> moveNames)
	{
		if ("other".equals(key))
		{
			return "untracked";
		}
		if (key.startsWith("@"))
		{
			return key.substring(1);
		}
		String nick = moveNames == null ? null : moveNames.apply(key);
		if (nick != null && !nick.isEmpty())
		{
			return nick;
		}
		String known = KNOWN_MOVES.get(key);
		if (known != null)
		{
			return known;
		}
		// An animation nobody has named yet. The number means nothing to a player,
		// so describe it instead and let the tip say how to name it.
		return "an attack of its own";
	}

	/** Game ticks as m:ss. */
	static String clock(int ticks)
	{
		int secs = (int) Math.round(Math.max(0, ticks) * 0.6);
		return (secs / 60) + ":" + String.format("%02d", secs % 60);
	}

	static String gp(long gp)
	{
		if (gp >= 1_000_000_000)
		{
			return String.format("%.1fb", gp / 1e9);
		}
		if (gp >= 1_000_000)
		{
			return String.format("%.1fm", gp / 1e6);
		}
		if (gp >= 1_000)
		{
			return (gp / 1_000) + "k";
		}
		return gp + "gp";
	}
}
