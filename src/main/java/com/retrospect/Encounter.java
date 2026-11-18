package com.retrospect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything recorded about one fight, from first hit to the end. */
final class Encounter
{
	enum Outcome
	{
		KILL, DEATH, LEFT
	}

	enum Kind
	{
		TOOK, DEALT, HEAL, SPEC, TARGET_HP, LOW_HP, LOOT, END
	}

	/** One thing that happened, with the tick it happened on. */
	static final class Event
	{
		final int tick;
		final Kind kind;
		final int amount;
		final String note;

		Event(int tick, Kind kind, int amount, String note)
		{
			this.tick = tick;
			this.kind = kind;
			this.amount = amount;
			this.note = note;
		}
	}

	String target;
	/** The game's id for the monster, or -1 for a player. */
	int targetId = -1;
	boolean targetIsPlayer;
	/** Every player you traded hits with, in the order they joined. */
	final Set<String> opponents = new LinkedHashSet<>();
	long startedAtMs;
	int startTick;
	int endTick;
	Outcome outcome = Outcome.LEFT;

	int dealt;
	int hits;
	int zeros;
	int maxHit;

	int taken;
	int hitsTaken;
	int maxTaken;

	int healed;
	int heals;
	int lastHealTick = -1;
	int prayerUsed;
	int specs;
	/** Lowest your hitpoints got. -1 = never seen. */
	int lowestHp = -1;
	/** Times you fell under a quarter of your health. */
	int lowDips;
	/** Times you healed while already above 80% health. */
	int earlyEats;
	/** Value of what the kill dropped, in gp. */
	long lootValue;

	/** Your hitpoints level, so damage can be read against your health bar. 0 = unknown. */
	int maxHp;
	/** Target health in percent the last time it was seen. -1 = unknown. */
	int targetHpPct = -1;

	/** Ticks on which you started an attack. */
	final List<Integer> attackTicks = new ArrayList<>();
	/** The equipped weapon's attack speed at each of those ticks, 0 where it was not known. */
	final List<Integer> weaponTicks = new ArrayList<>();
	/** Damage dealt per target name, to work out who the fight was really against. */
	final Map<String, Integer> dealtByName = new LinkedHashMap<>();
	/** Move -> {times it hit you, total damage}. */
	final Map<String, int[]> takenByMove = new LinkedHashMap<>();
	final List<Event> events = new ArrayList<>();

	int durationTicks()
	{
		return Math.max(1, endTick - startTick);
	}

	double seconds()
	{
		return durationTicks() * 0.6;
	}

	double dps()
	{
		return dealt / seconds();
	}

	/** Share of your hits that did damage, 0-100. */
	int accuracyPct()
	{
		return hits == 0 ? 0 : (int) Math.round(100.0 * (hits - zeros) / hits);
	}

	/**
	 * Your weapon's attack speed in ticks, read from the fight itself:
	 * the most common gap between your attacks.
	 */
	int attackSpeed()
	{
		int[] count = new int[11];
		for (int i = 1; i < attackTicks.size(); i++)
		{
			int gap = attackTicks.get(i) - attackTicks.get(i - 1);
			if (gap >= 2 && gap <= 10)
			{
				count[gap]++;
			}
		}
		int best = 0;
		for (int gap = 2; gap <= 10; gap++)
		{
			if (count[gap] > count[best])
			{
				best = gap;
			}
		}
		return count[best] >= 3 ? best : 0;
	}

	/**
	 * The weapon's own attack speed, as the game reports it, rather than the rhythm you
	 * happened to keep. Modal value, so a mid-fight weapon switch does not skew it.
	 */
	int weaponSpeed()
	{
		int[] count = new int[11];
		int best = 0;
		for (int ticks : weaponTicks)
		{
			if (ticks >= 1 && ticks <= 10)
			{
				count[ticks]++;
				if (count[ticks] > count[best])
				{
					best = ticks;
				}
			}
		}
		return best;
	}

	/**
	 * What one attack should cost: the real weapon speed when the game told us, and
	 * otherwise the rhythm we observed. Measuring against the weapon matters: against
	 * your own rhythm, being consistently slow looks like being on time.
	 */
	int expectedSpeed()
	{
		int weapon = weaponSpeed();
		return weapon > 0 ? weapon : attackSpeed();
	}

	/** The weapon speed in force at a given attack, falling back to the fight's usual one. */
	private int speedAt(int index)
	{
		if (index >= 0 && index < weaponTicks.size())
		{
			int ticks = weaponTicks.get(index);
			if (ticks >= 1 && ticks <= 10)
			{
				return ticks;
			}
		}
		return expectedSpeed();
	}

	/** Average ticks between your attacks, or 0 with fewer than two attacks. */
	double averageGap()
	{
		if (attackTicks.size() < 2)
		{
			return 0;
		}
		int span = attackTicks.get(attackTicks.size() - 1) - attackTicks.get(0);
		return (double) span / (attackTicks.size() - 1);
	}

	/** Ticks spent not attacking beyond what the weapon needs. Includes time the fight forced on you. */
	int idleTicks()
	{
		if (expectedSpeed() == 0)
		{
			return 0;
		}
		int idle = 0;
		for (int i = 1; i < attackTicks.size(); i++)
		{
			int gap = attackTicks.get(i) - attackTicks.get(i - 1);
			int speed = speedAt(i - 1);
			if (speed > 0 && gap > speed)
			{
				idle += gap - speed;
			}
		}
		return idle;
	}

	/** The part of the idle time that fell in gaps where you healed. */
	int eatIdleTicks()
	{
		if (expectedSpeed() == 0)
		{
			return 0;
		}
		int idle = 0;
		for (int i = 1; i < attackTicks.size(); i++)
		{
			int from = attackTicks.get(i - 1);
			int to = attackTicks.get(i);
			int speed = speedAt(i - 1);
			if (speed <= 0 || to - from <= speed)
			{
				continue;
			}
			for (Event ev : events)
			{
				if (ev.kind == Kind.HEAL && ev.tick >= from && ev.tick <= to)
				{
					idle += to - from - speed;
					break;
				}
			}
		}
		return idle;
	}

	/** A sensible health floor to eat at: just above the biggest hit you took. */
	int eatFloor()
	{
		return maxTaken + 5;
	}

	/** How many attacks would have fitted in the idle time. */
	int missedAttacks()
	{
		int speed = expectedSpeed();
		return speed == 0 ? 0 : idleTicks() / speed;
	}

	/** The move that did the most damage to you, or null. */
	String worstMove()
	{
		String worst = null;
		int most = 0;
		for (Map.Entry<String, int[]> e : takenByMove.entrySet())
		{
			if (e.getValue()[1] > most)
			{
				most = e.getValue()[1];
				worst = e.getKey();
			}
		}
		return worst;
	}
}
