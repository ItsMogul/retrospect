package com.retrospect;

/** The few numbers kept about each past fight, so new fights can be compared with old ones. */
final class FightSummary
{
	String target;
	long startedAtMs;
	String outcome;
	int durationTicks;
	int dealt;
	int taken;
	int accuracyPct;
	int missedAttacks;
	String takeaway;
	String clip;

	static FightSummary of(Encounter e, String takeaway, String clip)
	{
		FightSummary s = new FightSummary();
		s.target = e.target;
		s.startedAtMs = e.startedAtMs;
		s.outcome = e.outcome.name();
		s.durationTicks = e.durationTicks();
		s.dealt = e.dealt;
		s.taken = e.taken;
		s.accuracyPct = e.accuracyPct();
		s.missedAttacks = e.missedAttacks();
		s.takeaway = takeaway;
		s.clip = clip;
		return s;
	}

	boolean isKill()
	{
		return "KILL".equals(outcome);
	}
}
