package com.retrospect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.Test;

public class RetrospectLogicTest
{
	private static final Function<String, String> NAMES = k -> "14322".equals(k) ? "swipe" : null;

	/** A 4-tick weapon attacking from startTick, hitting 20 each time, with optional pauses. */
	private static void attacks(EncounterTracker t, int from, int count, int gap, int dmg)
	{
		for (int i = 0; i < count; i++)
		{
			int tick = from + i * gap;
			t.onMyAttack(tick);
			t.onMyHit(tick + 1, dmg, "Vorkath", 8061, false, Math.max(1, 100 - (i + 1) * 100 / count), 99, 1000L);
		}
	}

	private static Encounter run(EncounterTracker t, int untilTick)
	{
		for (int tick = 0; tick <= untilTick; tick++)
		{
			Encounter e = t.onTick(tick);
			if (e != null)
			{
				return e;
			}
		}
		return null;
	}

	@Test
	public void killIsDetectedAndMeasured()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 100, 20, 4, 20);
		t.onHitTaken(120, 30, "Vorkath", false, "14322", 99, 1000L);
		t.onActorDeath(180, "Vorkath");
		assertNull(t.onTick(184));
		Encounter e = t.onTick(185);
		assertNotNull(e);
		assertEquals(Encounter.Outcome.KILL, e.outcome);
		assertEquals("Vorkath", e.target);
		assertEquals(400, e.dealt);
		assertEquals(20, e.hits);
		assertEquals(100, e.accuracyPct());
		assertEquals(30, e.taken);
		assertEquals(4, e.attackSpeed());
		assertEquals(0, e.idleTicks());
		assertEquals(79, e.durationTicks()); // first hit on tick 101, death on 180
		assertFalse(t.isActive());
	}

	@Test
	public void someoneElseDyingDoesNotEndTheFight()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 100, 5, 4, 20);
		t.onActorDeath(118, "Goblin");
		assertNull(t.onTick(121));
		assertTrue(t.isActive());
	}

	@Test
	public void fightTimesOutWhenNothingHappens()
	{
		EncounterTracker t = new EncounterTracker();
		t.setIdleTimeoutTicks(50);
		attacks(t, 0, 5, 4, 10);
		Encounter e = run(t, 200);
		assertNotNull(e);
		assertEquals(Encounter.Outcome.LEFT, e.outcome);
		assertEquals(17, e.endTick); // last hit landed on tick 17
	}

	@Test
	public void damageWithNoAttackerDoesNotStartAFight()
	{
		EncounterTracker t = new EncounterTracker();
		t.onHitTaken(5, 3, null, false, null, 99, 0L);
		assertFalse(t.isActive());
	}

	@Test
	public void targetFollowsWhoYouHurtMost()
	{
		EncounterTracker t = new EncounterTracker();
		t.onMyHit(1, 10, "Minion", 1, false, 50, 99, 0L);
		t.onMyHit(2, 40, "Boss", 2, false, 90, 99, 0L);
		assertEquals("Boss", t.getCurrent().target);
		t.onActorDeath(3, "Minion");
		assertNull(t.onTick(6));
	}

	@Test
	public void idleTimeBecomesMissedAttacks()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 10, 4, 20);    // ticks 0..36
		attacks(t, 60, 10, 4, 20);   // a 24-tick hole: 20 idle ticks = 5 attacks
		Encounter e = t.forceEnd(100);
		assertEquals(4, e.attackSpeed());
		assertEquals(20, e.idleTicks());
		assertEquals(5, e.missedAttacks());
	}

	@Test
	public void deathDebriefLeadsWithTheDeath()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 10, 4, 20);
		t.onHeal(10, 20, -1);
		t.onHitTaken(33, 31, "Vorkath", false, "14322", 99, 0L);
		t.onHitTaken(36, 47, "Vorkath", false, "14326", 99, 0L);
		t.onTargetHp(36, 23);
		t.onMyDeath(38);
		Encounter e = t.onTick(43);
		assertEquals(Encounter.Outcome.DEATH, e.outcome);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertEquals("Vorkath: you died after 0:22", d.title);
		assertEquals("You died 0:22 in with Vorkath at 23% health. The last 6 seconds cost you 78 HP: "
			+ "31 during swipe, 47 during an attack of its own. Your last heal was 17 seconds before that.", d.takeaway);
		assertEquals("Eat when you fall under 52 HP. The biggest hit here was 47, so anything lower is a coin flip.", d.tip);
		assertEquals("0:22 YOU DIED", d.timeline.get(d.timeline.size() - 1));
		assertEquals("</3", d.vibe);
	}

	@Test
	public void oneMoveDoingMostOfTheDamageIsTheTakeaway()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 30, 4, 20);
		for (int i = 0; i < 6; i++)
		{
			t.onHitTaken(10 + i * 15, 25, "Vorkath", false, "14322", 99, 0L);
		}
		t.onHitTaken(50, 10, "Vorkath", false, "14330", 99, 0L);
		t.onActorDeath(120, "Vorkath");
		Encounter e = t.onTick(125);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertEquals("150 of the 160 damage you took (94%) landed during swipe, in 6 hits. That is the first thing to fix.", d.takeaway);
		assertTrue(d.stats.get(1).startsWith("Took 160 in 7 hits, biggest 25. That is 1.6 of your health bars."));
	}

	@Test
	public void idleTimeIsTheTakeawayWhenLittleDamageWasTaken()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 10, 4, 20);
		attacks(t, 60, 10, 4, 20);
		t.onActorDeath(100, "Vorkath");
		Encounter e = t.onTick(105);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertEquals("You went about 12 seconds without attacking, room for roughly 5 more attacks (about 100 damage). "
			+ "Some of that the fight forces on you; the rest is free damage.", d.takeaway);
	}

	@Test
	public void cleanKillSaysSoAndComparesWithHistory()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 20, 4, 20);
		t.onActorDeath(80, "Vorkath");
		Encounter e = t.onTick(85);
		List<FightSummary> past = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			FightSummary s = new FightSummary();
			s.target = "Vorkath";
			s.outcome = "KILL";
			s.durationTicks = 100 + i * 10;
			s.taken = 60;
			s.accuracyPct = 100;
			past.add(s);
		}
		Debrief d = DebriefBuilder.build(e, past, NAMES);
		assertEquals("Fastest kill you have on record: 0:47 (previous best 1:00).", d.takeaway);
		assertNull(d.tip);
		assertTrue(d.strong.contains("No damage taken. Flawless."));
		assertTrue(d.weak.isEmpty());
		assertTrue(d.stats.contains("Against your last 3 kills: time 0:47 (average 1:06), damage taken 0 (average 60)."));
		assertEquals("gz, new pb", d.vibe);
	}

	@Test
	public void nothingToSayIsSaidPlainly()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 5, 4, 20);
		t.onHitTaken(10, 5, "Vorkath", false, "14322", 99, 0L);
		t.onActorDeath(20, "Vorkath");
		Debrief d = DebriefBuilder.build(t.onTick(25), Collections.emptyList(), NAMES);
		assertEquals("Clean fight. Nothing stands out to fix.", d.takeaway);
		assertNull(d.vibe);
	}

	@Test
	public void longTimelinesAreTrimmedToTheBiggestMoments()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 5, 4, 20);
		for (int i = 0; i < 60; i++)
		{
			t.onHitTaken(20 + i * 5, 1 + i, "Vorkath", false, "14322", 99, 0L);
		}
		t.onActorDeath(400, "Vorkath");
		Debrief d = DebriefBuilder.build(t.onTick(405), Collections.emptyList(), NAMES);
		assertEquals(DebriefBuilder.MAX_TIMELINE_LINES + 1, d.timeline.size());
		assertTrue(d.timeline.get(d.timeline.size() - 1).contains("smaller moments left out"));
		assertTrue(d.timeline.get(d.timeline.size() - 2).endsWith("KILL"));
	}

	@Test
	public void clockFormatsTicks()
	{
		assertEquals("0:00", DebriefBuilder.clock(0));
		assertEquals("1:00", DebriefBuilder.clock(100));
		assertEquals("2:14", DebriefBuilder.clock(223));
	}

	@Test
	public void historySurvivesARestartAndABrokenFile() throws Exception
	{
		File dir = Files.createTempDirectory("retrospect").toFile();
		File file = new File(dir, "history.json");
		FightHistory h = new FightHistory(file, new Gson());
		h.load();
		FightSummary s = new FightSummary();
		s.target = "Vorkath";
		s.outcome = "KILL";
		s.durationTicks = 200;
		h.add(s);
		FightHistory again = new FightHistory(file, new Gson());
		again.load();
		assertEquals(1, again.against("vorkath").size());
		assertEquals(200, again.recent(5).get(0).durationTicks);

		Files.write(file.toPath(), "{not json".getBytes());
		FightHistory broken = new FightHistory(file, new Gson());
		broken.load();
		assertEquals(0, broken.recent(5).size());
		assertTrue(new File(dir, "history.json.unreadable").isFile());
	}

	private static BufferedImage picture(int w, int h, Color c)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(c);
		g.fillRect(0, 0, w, h);
		g.dispose();
		return img;
	}

	@Test
	public void bufferKeepsOnlyTheRecentPastAndEvenSizes() throws Exception
	{
		FrameBuffer b = new FrameBuffer();
		b.setKeepMs(2_000);
		for (int i = 0; i < 50; i++)
		{
			b.add(picture(801, 603, Color.RED), i * 100L, 480, 0.7f);
		}
		List<FrameBuffer.Frame> all = b.snapshot(0, Long.MAX_VALUE);
		assertEquals(21, all.size()); // 2.9s..4.9s
		assertEquals(2_900, all.get(0).timeMs);
		assertEquals(0, all.get(0).width % 2);
		assertEquals(480, all.get(0).height);
		assertEquals(11, b.snapshot(3_000, 4_000).size());
	}

	@Test
	public void resampleFillsDroppedFramesSoSpeedStaysReal()
	{
		List<FrameBuffer.Frame> in = new ArrayList<>();
		for (long t : new long[]{0, 100, 200, 900, 1000})
		{
			in.add(new FrameBuffer.Frame(t, new byte[1], 2, 2));
		}
		List<FrameBuffer.Frame> out = ClipWriter.resample(in, 10);
		assertEquals(11, out.size()); // one second at 10 fps, both ends included
		assertEquals(200, out.get(5).timeMs);  // the hole repeats the nearest frame
		assertEquals(900, out.get(6).timeMs);
		assertEquals(1000, out.get(10).timeMs);
	}

	@Test
	public void aviFileHasTheRightShape() throws Exception
	{
		FrameBuffer b = new FrameBuffer();
		for (int i = 0; i < 30; i++)
		{
			b.add(picture(640, 360, new Color(i * 8, 40, 200 - i * 4)), i * 100L, 360, 0.7f);
		}
		File f = File.createTempFile("retrospect", ".avi");
		ClipWriter.writeAvi(b.snapshot(0, Long.MAX_VALUE), 10, f);
		byte[] bytes = Files.readAllBytes(f.toPath());
		assertEquals("RIFF", new String(bytes, 0, 4));
		assertEquals("AVI ", new String(bytes, 8, 4));
		int riff = (bytes[4] & 0xff) | (bytes[5] & 0xff) << 8 | (bytes[6] & 0xff) << 16 | (bytes[7] & 0xff) << 24;
		assertEquals(bytes.length - 8, riff);
		int frames = (bytes[48] & 0xff) | (bytes[49] & 0xff) << 8;
		assertEquals(30, frames);
	}

	@Test
	public void storageCapDeletesOldestClipsOnlyAndNeverTheNewest() throws Exception
	{
		File root = Files.createTempDirectory("retrospect-clips").toFile();
		File day = new File(root, "2026-11-15");
		day.mkdirs();
		File[] clips = new File[4];
		for (int i = 0; i < 4; i++)
		{
			clips[i] = new File(day, "clip" + i + (i % 2 == 0 ? ".mp4" : ".avi"));
			Files.write(clips[i].toPath(), new byte[1000]);
			clips[i].setLastModified(1_000_000_000_000L + i * 60_000L);
		}
		File notes = new File(day, "notes.txt");
		Files.write(notes.toPath(), new byte[5000]);
		ClipWriter.enforceCap(root.toPath(), 2500, clips[3]);
		assertFalse(clips[0].exists());
		assertFalse(clips[1].exists());
		assertTrue(clips[2].exists());
		assertTrue(clips[3].exists());
		assertTrue(notes.exists()); // not a clip: never touched

		ClipWriter.enforceCap(root.toPath(), 0, clips[3]);
		assertFalse(clips[2].exists());
		assertTrue(clips[3].exists()); // the clip just saved survives even a zero limit
	}

	@Test
	public void labelsBecomeSafeFileNames()
	{
		assertEquals("drop-vorkath-1-2m", ClipWriter.safeName("Drop: Vorkath (1.2m)"));
		assertEquals("moment", ClipWriter.safeName("../.."));
		assertEquals("kill-xx-c-windows", ClipWriter.safeName("kill xX\\..\\C:\\Windows"));
		assertTrue(ClipWriter.safeName("a".repeat(200)).length() <= 40);
	}

	@Test
	public void timelineIsInTimeOrderWhateverOrderThingsWereRecorded()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 10, 4, 20);
		t.onHitTaken(30, 40, "Vorkath", false, "14322", 99, 0L);
		t.onHeal(12, 20, -1);
		t.onSpec(5);
		t.onMyDeath(39);
		Debrief d = DebriefBuilder.build(t.onTick(44), Collections.emptyList(), NAMES);
		String previous = "";
		for (String line : d.timeline)
		{
			assertTrue(line.substring(0, 4).compareTo(previous) >= 0);
			previous = line.substring(0, 4);
		}
		assertTrue(d.timeline.get(d.timeline.size() - 1).endsWith("YOU DIED"));
		assertTrue(d.takeaway.contains("cost you 40 HP, all during swipe."));
	}

	@Test
	public void stayingOnTheTargetIsCalledOutAsStrong()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 20, 4, 20);
		t.onHitTaken(10, 5, "Vorkath", false, "14322", 99, 0L);
		t.onActorDeath(80, "Vorkath");
		Debrief d = DebriefBuilder.build(t.onTick(85), Collections.emptyList(), NAMES);
		assertEquals("You stayed on the target: almost no idle time across 20 attacks.", d.takeaway);
		assertTrue(d.weak.isEmpty());
	}

	@Test
	public void weakPointsCarryASpecificThingToTry()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 30, 4, 20);
		for (int i = 0; i < 6; i++)
		{
			t.onHitTaken(10 + i * 15, i == 3 ? 40 : 25, "Vorkath", false, "14322", 99, 0L);
		}
		t.onActorDeath(120, "Vorkath");
		Debrief d = DebriefBuilder.build(t.onTick(125), Collections.emptyList(), NAMES);
		assertTrue(d.takeaway.startsWith("165 of the 165 damage you took (100%) landed during swipe"));
		assertEquals("Rewatch 0:32, where swipe hit you for 40, and find what it does right before it lands.", d.tip);
	}

	@Test
	public void idleTimeIsMeasuredAgainstTheWeaponNotYourHabits()
	{
		// Six-tick weapon swung every eight ticks: two ticks lost per attack, even though
		// the player was perfectly consistent and the old modal-gap reading saw no idle.
		EncounterTracker slow = new EncounterTracker();
		for (int i = 0; i < 6; i++)
		{
			slow.onMyAttack(i * 8, 6);
			slow.onMyHit(i * 8 + 1, 20, "Vorkath", 8061, false, Math.max(1, 100 - i * 16), 99, 0L);
		}
		slow.onActorDeath(50, "Vorkath");
		Encounter e = slow.onTick(55);
		assertEquals(6, e.weaponSpeed());
		assertEquals(8, e.attackSpeed());        // the old yardstick called eight ticks "normal"
		assertEquals(10, e.idleTicks());         // five gaps, two ticks lost each

		// Same rhythm, but the weapon really is that slow: nothing lost
		EncounterTracker right = new EncounterTracker();
		for (int i = 0; i < 6; i++)
		{
			right.onMyAttack(i * 8, 8);
			right.onMyHit(i * 8 + 1, 20, "Vorkath", 8061, false, Math.max(1, 100 - i * 16), 99, 0L);
		}
		right.onActorDeath(50, "Vorkath");
		assertEquals(0, right.onTick(55).idleTicks());

		// Unknown weapon speed falls back to the rhythm we observed
		EncounterTracker unknown = new EncounterTracker();
		attacks(unknown, 0, 6, 4, 20);
		unknown.onActorDeath(40, "Vorkath");
		assertEquals(4, unknown.onTick(45).expectedSpeed());
	}

	@Test
	public void aWeaponSwitchMidFightUsesTheSpeedInForceAtTheTime()
	{
		EncounterTracker t = new EncounterTracker();
		t.onMyAttack(0, 6);      // slow weapon, then a correct 6-tick gap
		t.onMyAttack(6, 3);      // switched to something fast, then a 3-tick gap
		t.onMyAttack(9, 3);
		t.onMyHit(10, 20, "Vorkath", 8061, false, 20, 99, 0L);
		t.onActorDeath(20, "Vorkath");
		assertEquals(0, t.onTick(25).idleTicks());
	}

	@Test
	public void theSavingSwitchOverrulesEveryMoment()
	{
		RetrospectConfig.Capture both = RetrospectConfig.Capture.BOTH;
		RetrospectConfig.Capture clip = RetrospectConfig.Capture.CLIP;
		RetrospectConfig.Capture shot = RetrospectConfig.Capture.SCREENSHOT;
		RetrospectConfig.Capture off = RetrospectConfig.Capture.OFF;

		// As set: each moment keeps its own setting
		assertEquals(both, RetrospectConfig.Saving.AS_SET.apply(both));
		assertEquals(shot, RetrospectConfig.Saving.AS_SET.apply(shot));

		// Narrowed: a moment that would save, saves only that kind
		assertEquals(clip, RetrospectConfig.Saving.CLIPS.apply(both));
		assertEquals(shot, RetrospectConfig.Saving.SHOTS.apply(both));
		assertEquals(clip, RetrospectConfig.Saving.CLIPS.apply(shot));

		// Off means off, and a moment already off stays off whatever the switch says
		assertEquals(off, RetrospectConfig.Saving.OFF.apply(both));
		assertEquals(off, RetrospectConfig.Saving.CLIPS.apply(off));
		assertEquals(off, RetrospectConfig.Saving.AS_SET.apply(null));
	}

	@Test
	public void theSavingSwitchCyclesBackToWhereItStarted()
	{
		RetrospectConfig.Saving at = RetrospectConfig.Saving.AS_SET;
		for (int i = 0; i < RetrospectConfig.Saving.values().length; i++)
		{
			at = at.next();
		}
		assertEquals(RetrospectConfig.Saving.AS_SET, at);
		assertEquals(RetrospectConfig.Saving.CLIPS, RetrospectConfig.Saving.AS_SET.next());
		assertEquals("Off", RetrospectConfig.Saving.OFF.label());
	}

	@Test
	public void beingLateOnEveryAttackIsCalledOut()
	{
		// A tick late every swing: no single idle gap, but a third of the damage missing
		EncounterTracker late = new EncounterTracker();
		for (int i = 0; i < 8; i++)
		{
			late.onMyAttack(i * 4, 3);
			late.onMyHit(i * 4 + 1, 20, "Vorkath", 8061, false, Math.max(1, 100 - i * 12), 99, 0L);
		}
		late.onActorDeath(34, "Vorkath");
		Debrief d = DebriefBuilder.build(late.onTick(40), Collections.emptyList(), NAMES);
		assertTrue(d.takeaway.contains("3 tick weapon") && d.takeaway.contains("33%"));

		// On rhythm: nothing to say about it
		EncounterTracker onTime = new EncounterTracker();
		for (int i = 0; i < 8; i++)
		{
			onTime.onMyAttack(i * 3, 3);
			onTime.onMyHit(i * 3 + 1, 20, "Vorkath", 8061, false, Math.max(1, 100 - i * 12), 99, 0L);
		}
		onTime.onActorDeath(26, "Vorkath");
		Debrief clean = DebriefBuilder.build(onTime.onTick(32), Collections.emptyList(), NAMES);
		assertFalse(clean.takeaway.contains("slower than it can go"));
	}

	@Test
	public void aSpecThatOpensTheFightStillCounts()
	{
		// The spec energy drop lands a tick before the first hitsplat registers the fight
		EncounterTracker t = new EncounterTracker();
		t.onSpec(0);
		attacks(t, 1, 6, 5, 20);
		t.onSpec(12);
		t.onActorDeath(40, "Vorkath");
		Encounter e = t.onTick(45);
		assertEquals(2, e.specs);

		// A spec from a fight that ended long ago is not adopted by the next one
		EncounterTracker u = new EncounterTracker();
		u.onSpec(0);
		attacks(u, 40, 4, 5, 20);
		u.onActorDeath(70, "Vorkath");
		assertEquals(0, u.onTick(75).specs);
	}

	@Test
	public void idleTimeAroundEatingGetsTheEatingTip()
	{
		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 10, 4, 20);
		t.onHeal(45, 20, 60);       // healed inside the hole in the attacks
		attacks(t, 60, 10, 4, 20);
		t.onActorDeath(100, "Vorkath");
		Encounter e = t.onTick(105);
		assertEquals(20, e.eatIdleTicks());
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertTrue(d.tip.startsWith("About 12 of those seconds were around healing. Eat right after your attack lands"));

		// A fight whose damage came from an actual move gets the mechanic tip
		EncounterTracker u = new EncounterTracker();
		attacks(u, 0, 10, 4, 20);
		u.onHitTaken(30, 12, "Vorkath", false, "14326", 99, 0L);
		attacks(u, 60, 10, 4, 20);
		u.onActorDeath(100, "Vorkath");
		assertEquals("Click back onto the target the moment each mechanic ends, before you fix anything else.",
			DebriefBuilder.build(u.onTick(105), Collections.emptyList(), NAMES).tip);

		// Nothing hit you at all, so the gap was entirely yours
		EncounterTracker v = new EncounterTracker();
		attacks(v, 0, 10, 4, 20);
		attacks(v, 60, 10, 4, 20);
		v.onActorDeath(100, "Vorkath");
		assertTrue(DebriefBuilder.build(v.onTick(105), Collections.emptyList(), NAMES).tip
			.startsWith("Nothing in this fight forced that gap"));
	}

	@Test
	public void playerFightNamesEveryoneAndReadsLikeAFight()
	{
		EncounterTracker t = new EncounterTracker();
		t.onHp(0, 99);
		t.onMyAttack(9);
		t.onMyHit(10, 30, "Zezima", -1, true, 70, 99, 0L);
		t.onHitTaken(12, 72, "Zezima", true, "7644", 99, 0L);   // ags spec
		t.onHp(12, 27);
		t.onHeal(12, 22, 49);
		t.onHp(12, 49);
		t.onHitTaken(14, 9, "Durial321", true, "@Durial321", 99, 0L);
		t.onSpec(16);
		t.onMyHit(17, 61, "Zezima", -1, true, 0, 99, 0L);
		t.onActorDeath(18, "Zezima");
		t.onLoot(22, 25_000_000L);
		assertNull(t.onTick(25));
		Encounter e = t.onTick(26);  // player kills stay open 8 ticks for the loot
		assertEquals(Encounter.Outcome.KILL, e.outcome);
		assertEquals(-1, e.targetId);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), null);
		assertEquals("Zezima: kill in 0:05 (+25.0m)", d.title);
		assertEquals("Fought: Zezima, Durial321", d.fought);
		assertEquals("gf", d.vibe);
		assertEquals("72 of the 81 damage you took (89%) came from ags spec, in 1 hit.", d.takeaway);
		assertEquals("Eat up before they can do it again: the biggest of those was 72.", d.tip);
		assertTrue(d.strong.contains("Killed Zezima for 25.0m."));
		assertTrue(d.strong.contains("Your special attack landed for 61."));
		assertEquals("     YOU       | ZEZIMA", d.timeline.get(0));
		assertEquals(java.util.Arrays.asList(
			"     YOU       | ZEZIMA",
			"0:00           | at 75%",
			"0:01           | 72 ags spec",
			"0:01 ate +22   |",
			"0:02           | 9 Durial321",
			"0:04 spec 61   |",
			"0:05 KILL +25.0m"), d.timeline);
	}

	@Test
	public void banterFitsTheSituationAndCanBeSwitchedOff()
	{
		Encounter e = new Encounter();
		e.target = "Vorkath";
		e.outcome = Encounter.Outcome.DEATH;
		e.targetHpPct = 4;
		assertEquals("so close </3", DebriefBuilder.vibe(e, false));
		e.targetIsPlayer = true;
		e.targetHpPct = 60;
		assertEquals("gf </3", DebriefBuilder.vibe(e, false));
		e.outcome = Encounter.Outcome.KILL;
		e.maxHp = 99;
		e.taken = 80;
		e.lowestHp = 6;
		assertEquals("clutch", DebriefBuilder.vibe(e, false));
		e.lowestHp = 99;
		e.taken = 0;
		assertEquals("ezpz", DebriefBuilder.vibe(e, false));
		e.outcome = Encounter.Outcome.LEFT;
		assertNull(DebriefBuilder.vibe(e, false));

		EncounterTracker t = new EncounterTracker();
		attacks(t, 0, 5, 4, 20);
		t.onMyDeath(20);
		assertNull(DebriefBuilder.build(t.onTick(25), Collections.emptyList(), NAMES, false).vibe);
	}

	@Test
	public void healthLowsAndEarlyEatingAreTracked()
	{
		EncounterTracker t = new EncounterTracker();
		t.onHp(0, 99);
		attacks(t, 0, 12, 4, 20);
		t.onHp(5, 20);   // under a quarter
		t.onHp(6, 60);
		t.onHp(9, 18);   // and again
		t.onHp(10, 95);
		for (int i = 0; i < 3; i++)
		{
			t.onHeal(20 + i, 10, 99);  // healing from 89: already above 80%
		}
		t.onHitTaken(30, 20, "Vorkath", false, "14322", 99, 0L);
		t.onActorDeath(48, "Vorkath");
		Encounter e = t.onTick(53);
		assertEquals(18, e.lowestHp);
		assertEquals(2, e.lowDips);
		assertEquals(3, e.earlyEats);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertEquals("You dropped under a quarter health 2 times and got away with it.", d.takeaway);
		assertEquals("Eat when you fall under 25 HP. The biggest hit here was 20, so anything lower is a coin flip.", d.tip);
		assertEquals("You healed 3 times while already above 80% health. Try: Hold your food until you are under 25 HP. "
			+ "Every early bite costs you an attack.", d.weak.get(0));
	}

	@Test
	public void knownSpecialAttacksHaveNamesButNicknamesWin()
	{
		assertEquals("ags spec", DebriefBuilder.moveLabel("7644", null));
		assertEquals("my name", DebriefBuilder.moveLabel("7644", k -> "my name"));
		assertEquals("an attack of its own", DebriefBuilder.moveLabel("999", k -> null));
		assertEquals("untracked", DebriefBuilder.moveLabel("other", null));
	}

	@Test
	public void panelLookKeepsEditsAndRevertsToStock() throws Exception
	{
		File file = new File(Files.createTempDirectory("retrospect-look").toFile(), "look.json");
		PanelLook look = PanelLook.load(file, new Gson());
		assertTrue(look.isStock());
		assertEquals("THE TAKEAWAY", look.label("takeaway"));
		look.setLabel("takeaway", "  what went\nwrong  ");
		look.setLabel("nonsense", "ignored");
		look.setColor("accent", new Color(0x00, 0xAA, 0xFF));
		look.save(file, new Gson());

		PanelLook again = PanelLook.load(file, new Gson());
		assertEquals("what went wrong", again.label("takeaway"));
		assertEquals(new Color(0x00AAFF), again.color("accent"));
		assertEquals(new Color(0x8BD17C), again.color("strong"));
		again.setLabel("takeaway", "   ");
		assertEquals("THE TAKEAWAY", again.label("takeaway"));
		again.setLabel("title", "x".repeat(100));
		assertEquals(PanelLook.MAX_LABEL, again.label("title").length());
		again.reset();
		assertTrue(again.isStock());
		assertEquals(new Color(0xF0B93A), again.color("accent"));

		Files.write(file.toPath(), "garbage".getBytes());
		assertTrue(PanelLook.load(file, new Gson()).isStock());
	}

	@Test
	public void foldersAreMadePerMonsterSharedForPvpAndCustomOnesWin() throws Exception
	{
		File file = new File(Files.createTempDirectory("retrospect-folders").toFile(), "folders.json");
		FightFolders folders = new FightFolders(file, new Gson());
		folders.load();
		assertNull(folders.forFight("Vorkath", 8061, false, false));
		FightFolders.Folder vork = folders.forFight("Vorkath", 8061, false, true);
		assertEquals("Vorkath [8061]", vork.dir);
		assertTrue(vork.log);
		assertFalse(vork.clipKills);
		assertTrue(vork.clipDeaths);
		// another form of the same boss has another id but belongs in the same folder
		assertTrue(vork == folders.forFight("Vorkath", 8059, false, true));
		FightFolders.Folder pvp = folders.forFight("Zezima", -1, true, true);
		assertEquals("PvP", pvp.dir);
		assertTrue(pvp == folders.forFight("Durial321", -1, true, true));

		assertNull(folders.createCustom("   ", "x"));
		FightFolders.Folder dragons = folders.createCustom("Dragons", "vorkath, King Black Dragon");
		assertTrue(dragons == folders.forFight("Vorkath", 8061, false, true));
		assertTrue(dragons == folders.forFight("King Black Dragon", 239, false, true));
		assertEquals("Dragons", folders.all().get(0).name);
		assertEquals("dragons (2)", folders.createCustom("dragons", "").dir);

		Encounter e = new Encounter();
		e.outcome = Encounter.Outcome.KILL;
		e.startTick = 0;
		e.endTick = 200;
		folders.record(dragons, e);
		e.endTick = 150;
		folders.record(dragons, e);
		e.outcome = Encounter.Outcome.DEATH;
		folders.record(dragons, e);
		assertEquals("3 fights \u00b7 2 kills \u00b7 1 death \u00b7 best 1:30", dragons.statsLine());
		folders.save();

		FightFolders again = new FightFolders(file, new Gson());
		again.load();
		assertEquals(4, again.all().size());
		assertEquals(3, again.forFight("Vorkath", 8061, false, false).fights);
	}

	@Test
	public void folderNamesCanNeverEscapeTheirParent()
	{
		assertEquals("Folder", FightFolders.dirName("../../.."));
		assertEquals("C Windows", FightFolders.dirName("C:\\Windows\\"));
		assertEquals("K'ril Tsutsaroth [3129]", FightFolders.dirName("K'ril Tsutsaroth [3129]"));
		assertEquals("Folder", FightFolders.dirName(null));
	}

	@Test
	public void respawningAfterDeathIsNotCountedAsHealing()
	{
		EncounterTracker t = new EncounterTracker();
		t.onHp(0, 99);
		attacks(t, 0, 5, 4, 20);
		t.onHitTaken(18, 60, "Vorkath", false, "14322", 99, 0L);
		t.onHp(18, 0);
		t.onMyDeath(19);
		t.onHeal(21, 99, 99);   // the respawn
		t.onHp(21, 99);
		Encounter e = t.onTick(24);
		assertEquals(0, e.heals);
		assertEquals(0, e.lowestHp);
		Debrief d = DebriefBuilder.build(e, Collections.emptyList(), NAMES);
		assertTrue(d.takeaway.contains("You did not heal at all."));
		assertTrue(d.timeline.get(d.timeline.size() - 1).endsWith("YOU DIED"));
	}

	@Test
	public void memoryLimitShortensTheBufferInsteadOfGrowing() throws Exception
	{
		FrameBuffer b = new FrameBuffer();
		b.setKeepMs(600_000);
		b.setMaxBytes(1);                 // clamps to the 8 MB floor
		java.util.Random r = new java.util.Random(3);
		BufferedImage noisy = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 720; y++)
		{
			for (int x = 0; x < 1280; x++)
			{
				noisy.setRGB(x, y, r.nextInt(0xFFFFFF));
			}
		}
		for (int i = 0; i < 40; i++)
		{
			b.add(noisy, i * 100L, 720, 0.9f);
		}
		assertTrue(b.getBytes() <= 8L * 1024 * 1024);
		assertTrue(b.size() < 40 && b.size() > 0);
		List<FrameBuffer.Frame> kept = b.snapshot(0, Long.MAX_VALUE);
		assertEquals(3_900, kept.get(kept.size() - 1).timeMs); // the newest frames are the ones kept
	}
}
