package com.retrospect;

/**
 * Follows one fight at a time. The plugin feeds it what it sees in game;
 * it decides when a fight starts and when it is over.
 * Holds no game objects, so it can be tested on its own.
 */
final class EncounterTracker
{
	private Encounter current;
	private int lastActivityTick;
	/** A special attack used in the moment before the fight registered. */
	private static final int NO_TICK = Integer.MIN_VALUE;
	private static final int SPEC_GRACE_TICKS = 3;
	private int pendingSpecTick = NO_TICK;
	/** The swing that started the fight, which lands before the first hitsplat registers it. */
	private int pendingAttackTick = NO_TICK;
	private int pendingAttackSpeed = 0;
	/** Tick the target died on, or -1. The fight closes a few ticks later so the last hits are counted. */
	private int deathTick = -1;
	private Encounter.Outcome pendingOutcome;
	private int idleTimeoutTicks = 50;
	private int lastHpMilestone = 100;
	private int hp = -1;

	void setIdleTimeoutTicks(int ticks)
	{
		idleTimeoutTicks = Math.max(10, ticks);
	}

	boolean isActive()
	{
		return current != null;
	}

	Encounter getCurrent()
	{
		return current;
	}

	private void ensure(int tick, String name, boolean isPlayer, int maxHp, long nowMs)
	{
		if (current != null)
		{
			return;
		}
		current = new Encounter();
		current.target = name;
		current.targetIsPlayer = isPlayer;
		current.startTick = tick;
		current.startedAtMs = nowMs;
		current.maxHp = maxHp;
		lastActivityTick = tick;
		deathTick = -1;
		pendingOutcome = null;
		lastHpMilestone = 100;
		if (pendingSpecTick != NO_TICK && tick - pendingSpecTick <= SPEC_GRACE_TICKS)
		{
			current.specs++;
			current.events.add(new Encounter.Event(pendingSpecTick, Encounter.Kind.SPEC, 0, null));
		}
		pendingSpecTick = NO_TICK;
		if (pendingAttackTick != NO_TICK && tick - pendingAttackTick <= SPEC_GRACE_TICKS)
		{
			current.attackTicks.add(pendingAttackTick);
			current.weaponTicks.add(pendingAttackSpeed);
		}
		pendingAttackTick = NO_TICK;
		pendingAttackSpeed = 0;
		if (hp >= 0)
		{
			current.lowestHp = hp;
		}
	}

	/** One of your hitsplats landed on something. */
	void onMyHit(int tick, int amount, String targetName, int targetId, boolean isPlayer, int targetHpPct, int maxHp, long nowMs)
	{
		if (targetName == null)
		{
			return;
		}
		boolean fresh = current == null;
		ensure(tick, targetName, isPlayer, maxHp, nowMs);
		Encounter e = current;
		if (fresh)
		{
			e.targetId = isPlayer ? -1 : targetId;
		}
		if (isPlayer)
		{
			e.opponents.add(targetName);
		}
		if (amount > 0)
		{
			e.events.add(new Encounter.Event(tick, Encounter.Kind.DEALT, amount, targetName));
		}
		e.dealt += amount;
		e.hits++;
		if (amount == 0)
		{
			e.zeros++;
		}
		e.maxHit = Math.max(e.maxHit, amount);
		int total = e.dealtByName.merge(targetName, amount, Integer::sum);
		// The fight is "against" whoever you have hurt the most (bosses with minions)
		Integer lead = e.dealtByName.get(e.target);
		if (!targetName.equals(e.target) && (lead == null || total > lead))
		{
			e.target = targetName;
			e.targetIsPlayer = isPlayer;
			e.targetId = isPlayer ? -1 : targetId;
			lastHpMilestone = 100;
		}
		if (targetName.equals(e.target))
		{
			onTargetHp(tick, targetHpPct);
		}
		lastActivityTick = tick;
	}

	/** You took damage. attackerName may be null if nobody obvious did it (poison, floor damage). */
	void onHitTaken(int tick, int amount, String attackerName, boolean attackerIsPlayer, String move, int maxHp, long nowMs)
	{
		if (current == null)
		{
			if (attackerName == null || amount <= 0)
			{
				return;
			}
			ensure(tick, attackerName, attackerIsPlayer, maxHp, nowMs);
		}
		Encounter e = current;
		if (attackerIsPlayer && attackerName != null)
		{
			e.opponents.add(attackerName);
		}
		e.taken += amount;
		e.hitsTaken++;
		e.maxTaken = Math.max(e.maxTaken, amount);
		String key = move == null ? "other" : move;
		int[] agg = e.takenByMove.computeIfAbsent(key, k -> new int[2]);
		agg[0]++;
		agg[1] += amount;
		if (amount > 0)
		{
			e.events.add(new Encounter.Event(tick, Encounter.Kind.TOOK, amount, key));
		}
		lastActivityTick = tick;
	}

	/** You started an attack animation at your target. */
	void onMyAttack(int tick)
	{
		onMyAttack(tick, 0);
	}

	/** @param weaponTicks the equipped weapon's attack speed, or 0 if it could not be read */
	void onMyAttack(int tick, int weaponTicks)
	{
		if (current == null)
		{
			pendingAttackTick = tick;
			pendingAttackSpeed = weaponTicks;
			return;
		}
		if (current.attackTicks.isEmpty() || current.attackTicks.get(current.attackTicks.size() - 1) != tick)
		{
			current.attackTicks.add(tick);
			current.weaponTicks.add(weaponTicks);
		}
		lastActivityTick = tick;
	}

	/** Your hitpoints right now. Call whenever they change, in or out of a fight. */
	void onHp(int tick, int newHp)
	{
		int before = hp;
		hp = newHp;
		// After you die your health snaps back to full: that is the respawn, not part of the fight
		if (current == null || newHp < 0 || pendingOutcome == Encounter.Outcome.DEATH)
		{
			return;
		}
		Encounter e = current;
		if (e.lowestHp < 0 || newHp < e.lowestHp)
		{
			e.lowestHp = newHp;
		}
		int quarter = e.maxHp / 4;
		if (quarter > 0 && newHp > 0 && newHp <= quarter && (before < 0 || before > quarter))
		{
			e.lowDips++;
			e.events.add(new Encounter.Event(tick, Encounter.Kind.LOW_HP, newHp, null));
		}
	}

	/** The kill dropped something. Counted while the fight is still being wrapped up. */
	void onLoot(int tick, long value)
	{
		if (current != null && value > 0)
		{
			current.lootValue += value;
			current.events.add(new Encounter.Event(tick, Encounter.Kind.LOOT, (int) Math.min(Integer.MAX_VALUE, value), null));
		}
	}

	/**
	 * @param hpAfter your hitpoints after the heal, or -1 if unknown
	 */
	void onHeal(int tick, int amount, int hpAfter)
	{
		if (current == null || amount <= 0 || pendingOutcome == Encounter.Outcome.DEATH)
		{
			return;
		}
		if (hpAfter >= 0 && current.maxHp > 0 && (hpAfter - amount) * 5 >= current.maxHp * 4)
		{
			current.earlyEats++;
		}
		current.healed += amount;
		current.heals++;
		current.lastHealTick = tick;
		current.events.add(new Encounter.Event(tick, Encounter.Kind.HEAL, amount, null));
	}

	void onPrayerUsed(int amount)
	{
		if (current != null && amount > 0)
		{
			current.prayerUsed += amount;
		}
	}

	void onSpec(int tick)
	{
		if (current == null)
		{
			// Opening a fight with a spec fires this before the first hitsplat starts
			// the encounter. Hold it, and count it once the fight actually begins.
			pendingSpecTick = tick;
			return;
		}
		current.specs++;
		current.events.add(new Encounter.Event(tick, Encounter.Kind.SPEC, 0, null));
	}

	/** Target health in percent. Logs a line each time it crosses 75, 50 and 25. */
	void onTargetHp(int tick, int pct)
	{
		if (current == null || pct < 0)
		{
			return;
		}
		current.targetHpPct = pct;
		// One big hit can cross several marks at once: only the lowest is worth a line
		int crossed = -1;
		for (int mark : new int[]{75, 50, 25})
		{
			if (pct <= mark && lastHpMilestone > mark)
			{
				crossed = mark;
			}
		}
		if (crossed > 0 && pct > 0)
		{
			current.events.add(new Encounter.Event(tick, Encounter.Kind.TARGET_HP, crossed, null));
		}
		lastHpMilestone = Math.min(lastHpMilestone, pct);
	}

	/** Something died. Only matters if it is the thing you are fighting. */
	void onActorDeath(int tick, String name)
	{
		if (current != null && name != null && name.equals(current.target) && pendingOutcome == null)
		{
			pendingOutcome = Encounter.Outcome.KILL;
			deathTick = tick;
			current.targetHpPct = 0;
		}
	}

	void onMyDeath(int tick)
	{
		if (current != null)
		{
			pendingOutcome = Encounter.Outcome.DEATH;
			deathTick = tick;
		}
	}

	/** Call once per game tick. Returns the finished fight when one has just ended, otherwise null. */
	Encounter onTick(int tick)
	{
		if (current == null)
		{
			return null;
		}
		// Stay open a few ticks after a death so the last hits and the loot are counted
		int wrapUp = current.targetIsPlayer ? 8 : 5;
		if (pendingOutcome != null && tick >= deathTick + wrapUp)
		{
			return finish(deathTick, pendingOutcome);
		}
		if (pendingOutcome == null && tick - lastActivityTick > idleTimeoutTicks)
		{
			return finish(lastActivityTick, Encounter.Outcome.LEFT);
		}
		return null;
	}

	/** Ends whatever is in progress right now (logout, plugin switched off). */
	Encounter forceEnd(int tick)
	{
		if (current == null)
		{
			return null;
		}
		return pendingOutcome != null ? finish(deathTick, pendingOutcome) : finish(Math.max(lastActivityTick, current.startTick), Encounter.Outcome.LEFT);
	}

	private Encounter finish(int endTick, Encounter.Outcome outcome)
	{
		Encounter e = current;
		e.endTick = Math.max(endTick, e.startTick + 1);
		e.outcome = outcome;
		e.events.add(new Encounter.Event(e.endTick, Encounter.Kind.END, 0, null));
		current = null;
		pendingOutcome = null;
		deathTick = -1;
		return e;
	}
}
