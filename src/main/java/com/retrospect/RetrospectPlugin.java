package com.retrospect;

import java.util.LinkedHashMap;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.VarPlayer;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Retrospect",
	description = "Reviews every fight, win or lose: the one thing to fix, one thing to try, what you did well, and a short clip of the moment.",
	tags = {"review", "debrief", "fight", "recap", "pvm", "pvp", "pk", "boss", "death", "kill", "clip", "replay", "record", "video", "screenshot"}
)
public class RetrospectPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(RetrospectPlugin.class);

	private static final Color GOLD = new Color(0xE0B040);
	private static final int SPEC_ENERGY_VARP = 300;
	private static final int ANIM_EAT_DRINK = 829;
	private static final int UNARMED_TICKS = 4;
	private static final int RAPID_STYLE = 1;
	private static final File LOOK_FILE = new File(Recorder.ROOT, "look.json");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

	private static final Pattern VALUABLE_DROP = Pattern.compile("Valuable drop: (.+?) \\(((?:\\d+,?)+) coins\\)");
	private static final Pattern KILL_COUNT = Pattern.compile("Your (.+) (?:kill|success) count is: ?([0-9,]+)");
	private static final String COLLECTION_LOG = "New item added to your collection log:";
	private static final String[] PET_MESSAGES = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed"};

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private RetrospectConfig config;
	@Inject private Recorder recorder;
	@Inject private ItemManager itemManager;
	@Inject private KeyManager keyManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ScheduledExecutorService executor;
	@Inject private Gson gson;
	@Inject private ConfigManager configManager;

	private final EncounterTracker tracker = new EncounterTracker();
	private FightHistory history;
	private FightFolders folders;
	private PanelLook look;
	private RetrospectPanel panel;
	/** The folder the current (or just finished) fight is filed in, and when it was last used. */
	private FightFolders.Folder fightFolder;
	private int fightFolderTick = -100;
	private NavigationButton navButton;

	/** The thing you are currently fighting. */
	private Actor focus;
	/** Last animation each nearby attacker played: {animation id, tick}. */
	private final Map<Actor, int[]> lastAnim = new WeakHashMap<>();
	/** Players you have damaged recently: name -> tick. */
	private final Map<String, Integer> playersHit = new HashMap<>();
	/** Stops one event from saving the same moment twice: kind -> tick. */
	private final Map<String, Integer> lastMoment = new HashMap<>();
	private Map<String, String> moveNames = new HashMap<>();

	private int specEnergy = -1;
	private int lastSpecTick = -100;
	private int lastHp = -1;
	private int lastPrayer = -1;
	private String lastClip;
	private int lastClipTick = -100;

	private final HotkeyListener hotkey = new HotkeyListener(() -> config.clipHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invokeLater(() -> clipNow());
		}
	};

	@Provides
	RetrospectConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(RetrospectConfig.class);
	}

	@Override
	protected void startUp()
	{
		history = new FightHistory(new File(Recorder.ROOT, "history.json"), gson);
		executor.execute(() ->
		{
			history.load();
			refreshRecent();
		});

		folders = new FightFolders(new File(Recorder.ROOT, "folders.json"), gson);
		folders.load();
		look = PanelLook.load(LOOK_FILE, gson);
		panel = new RetrospectPanel(new PanelActions(), look);
		panel.setFolders(folders.all());
		panel.setSerious(serious());
		panel.setSaving(config.saving().label(), momentSettings());
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Retrospect")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		readSettings();
		recorder.setOnSaved(this::onSaved);
		recorder.start();
		recorder.setActive(client.getGameState() == GameState.LOGGED_IN);
		keyManager.registerKeyListener(hotkey);
		executor.schedule(this::refreshStatus, 3, java.util.concurrent.TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(hotkey);
		recorder.stop();
		clientToolbar.removeNavigation(navButton);
		tracker.forceEnd(client.getTickCount());
		focus = null;
		lastAnim.clear();
		playersHit.clear();
		lastMoment.clear();
		panel = null;
		navButton = null;
		fightFolder = null;
	}

	private boolean serious()
	{
		return config.mode() == RetrospectConfig.Mode.SERIOUS;
	}

	/** Everything the side panel can ask for. These run on the interface thread. */
	private final class PanelActions implements RetrospectPanel.Actions
	{
		@Override
		public void clipNow()
		{
			clientThread.invokeLater(RetrospectPlugin.this::clipNow);
		}

		@Override
		public void copyRoot()
		{
			final String path = RetrospectPlugin.copyPath(Recorder.ROOT);
			clientThread.invokeLater(() -> say("Path copied: " + path));
		}

		@Override
		public void toggleMode()
		{
			RetrospectConfig.Mode next = serious() ? RetrospectConfig.Mode.EVERYDAY : RetrospectConfig.Mode.SERIOUS;
			configManager.setConfiguration(RetrospectConfig.GROUP, "mode", next.name());
		}

		@Override
		public void cycleSaving()
		{
			RetrospectConfig.Saving next = config.saving().next();
			configManager.setConfiguration(RetrospectConfig.GROUP, "saving", next.name());
			panel.setSaving(next.label(), momentSettings());
			say(next == RetrospectConfig.Saving.OFF
				? "Automatic saving is off. Clip that still works."
				: "Saving: " + next.label().toLowerCase() + ".");
		}

		@Override
		public void setMoment(String key, RetrospectConfig.Capture value)
		{
			configManager.setConfiguration(RetrospectConfig.GROUP, key, value.name());
			panel.setSaving(config.saving().label(), momentSettings());
		}

		@Override
		public void lookChanged()
		{
			final PanelLook l = look;
			executor.execute(() ->
			{
				try
				{
					l.save(LOOK_FILE, gson);
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Could not save the panel look", e);
				}
			});
		}

		@Override
		public void foldersChanged()
		{
			saveFolders();
		}

		@Override
		public void copyFolderPath(FightFolders.Folder folder)
		{
			final String path = RetrospectPlugin.copyPath(new File(Recorder.FOLDERS, folder.dir));
			clientThread.invokeLater(() -> say("Path copied: " + path));
		}

		@Override
		public void createFolder(String name, String targets)
		{
			if (folders.createCustom(name, targets) != null)
			{
				saveFolders();
				refreshFolders();
			}
		}
	}

	private void saveFolders()
	{
		final FightFolders f = folders;
		executor.execute(() ->
		{
			try
			{
				f.save();
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not save the folders", e);
			}
		});
	}

	private void refreshFolders()
	{
		final RetrospectPanel p = panel;
		final FightFolders f = folders;
		if (p != null && f != null)
		{
			final List<FightFolders.Folder> all = f.all();
			SwingUtilities.invokeLater(() -> p.setFolders(all));
		}
	}

	/**
	 * Where a moment happening right now should be filed: the current fight's folder in
	 * Serious mode (if that folder is switched on), otherwise null for the usual dated folders.
	 */
	private File momentDir()
	{
		if (!serious())
		{
			return null;
		}
		int tick = client.getTickCount();
		FightFolders.Folder f = null;
		if (tracker.isActive())
		{
			Encounter e = tracker.getCurrent();
			f = folders.forFight(e.target, e.targetId, e.targetIsPlayer, true);
			if (f != fightFolder)
			{
				fightFolder = f;
				refreshFolders();
			}
			fightFolderTick = tick;
		}
		else if (fightFolder != null && tick - fightFolderTick <= 25)
		{
			f = fightFolder; // the drop that lands just after the kill belongs to that fight
		}
		return f != null && f.log ? new File(Recorder.FOLDERS, f.dir) : null;
	}

	private void readSettings()
	{
		tracker.setIdleTimeoutTicks((int) Math.round(config.fightTimeout() / 0.6));
		Map<String, String> names = new HashMap<>();
		for (String pair : config.moveNames().split(","))
		{
			String[] kv = pair.split("=", 2);
			if (kv.length == 2 && !kv[0].trim().isEmpty() && !kv[1].trim().isEmpty())
			{
				names.put(kv[0].trim(), kv[1].trim());
			}
		}
		moveNames = names;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!RetrospectConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		readSettings();
		recorder.applyConfig();
		// The same settings live in RuneLite's config panel, so keep the side panel in step
		SwingUtilities.invokeLater(() ->
		{
			panel.setSerious(serious());
			panel.setSaving(config.saving().label(), momentSettings());
		});
		refreshStatus();
		executor.schedule(this::refreshStatus, 3, java.util.concurrent.TimeUnit.SECONDS);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		boolean in = e.getGameState() == GameState.LOGGED_IN;
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			recorder.setActive(false);
			finishFight(tracker.forceEnd(client.getTickCount()));
			focus = null;
			lastHp = -1;
			lastPrayer = -1;
			specEnergy = -1;
		}
		else if (in)
		{
			recorder.setActive(true);
		}
		refreshStatus();
	}

	// ---------- the fight itself ----------

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		Player me = client.getLocalPlayer();
		Actor actor = e.getActor();
		Hitsplat hit = e.getHitsplat();
		if (me == null || actor == null || hit == null || hit.getHitsplatType() == HitsplatID.HEAL)
		{
			return;
		}
		int tick = client.getTickCount();
		int amount = hit.getAmount();
		int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		long now = System.currentTimeMillis();

		if (actor == me)
		{
			Actor attacker = attackerOf(me);
			String move = null;
			if (attacker != null)
			{
				int[] anim = lastAnim.get(attacker);
				if (anim != null && tick - anim[1] <= 12)
				{
					move = String.valueOf(anim[0]);
				}
				// A player's ordinary attacks are not worth a number: name the player instead
				if (attacker instanceof Player && attacker.getName() != null
					&& (move == null || !DebriefBuilder.KNOWN_MOVES.containsKey(move)))
				{
					move = "@" + attacker.getName();
				}
			}
			tracker.onHitTaken(tick, amount, attacker == null ? null : attacker.getName(), attacker instanceof Player, move, maxHp, now);
			if (attacker != null && focus == null)
			{
				focus = attacker;
			}
			return;
		}

		if (!hit.isMine() || actor.getName() == null)
		{
			return;
		}
		tracker.onMyHit(tick, amount, actor.getName(), actor instanceof NPC ? ((NPC) actor).getId() : -1,
			actor instanceof Player, healthPct(actor), maxHp, now);
		if (tracker.isActive() && actor.getName().equals(tracker.getCurrent().target))
		{
			focus = actor;
		}
		if (actor instanceof Player)
		{
			playersHit.put(actor.getName(), tick);
		}
		if (amount >= config.bigHitMin() && (!config.bigHitSpecOnly() || tick - lastSpecTick <= 6))
		{
			fire("hit", "hit " + amount + " on " + actor.getName(), config.bigHit());
		}
	}

	/** Best guess at who just hurt you. */
	/**
	 * How many ticks one attack should take with what is currently equipped, from the
	 * game's own item stats. Rapid takes a tick off ranged weapons. Returns 0 when the
	 * weapon is unknown, in which case the debrief falls back to the observed rhythm.
	 */
	private int weaponTicks()
	{
		try
		{
			ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
			if (worn == null)
			{
				return 0;
			}
			Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
			if (weapon == null || weapon.getId() <= 0)
			{
				return UNARMED_TICKS;
			}
			ItemStats stats = itemManager.getItemStats(weapon.getId());
			ItemEquipmentStats gear = stats == null ? null : stats.getEquipment();
			if (gear == null || gear.getAspeed() <= 0)
			{
				return 0;
			}
			int ticks = gear.getAspeed();
			boolean ranged = gear.getArange() > Math.max(Math.max(gear.getAstab(), gear.getAslash()),
				Math.max(gear.getAcrush(), gear.getAmagic()));
			if (ranged && client.getVarpValue(VarPlayer.ATTACK_STYLE) == RAPID_STYLE)
			{
				ticks--;
			}
			return Math.max(1, ticks);
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	private Actor attackerOf(Player me)
	{
		if (focus != null && focus.getInteracting() == me)
		{
			return focus;
		}
		Actor mine = me.getInteracting();
		if (mine != null && mine.getInteracting() == me)
		{
			return mine;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
		{
			for (NPC npc : wv.npcs())
			{
				if (npc != null && npc.getInteracting() == me)
				{
					return npc;
				}
			}
			for (Player p : wv.players())
			{
				if (p != null && p != me && p.getInteracting() == me)
				{
					return p;
				}
			}
		}
		// Many bosses never "target" you in the game's eyes: if a fight is on, it was them
		return tracker.isActive() ? focus : null;
	}

	private static int healthPct(Actor a)
	{
		int ratio = a.getHealthRatio();
		int scale = a.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return -1;
		}
		int pct = (int) Math.round(100.0 * ratio / scale);
		return ratio > 0 ? Math.max(1, pct) : 0;
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		Actor actor = e.getActor();
		Player me = client.getLocalPlayer();
		if (actor == null || me == null || actor.getAnimation() == -1)
		{
			return;
		}
		int tick = client.getTickCount();
		if (actor == me)
		{
			// Animations report their own end as a change to -1; counting that as a
			// second attack doubled every attack count in the debrief.
			int anim = actor.getAnimation();
			if (tracker.isActive() && anim != -1 && anim != ANIM_EAT_DRINK && me.getInteracting() != null)
			{
				tracker.onMyAttack(tick, weaponTicks());
			}
		}
		else if (actor == focus || actor.getInteracting() == me)
		{
			lastAnim.put(actor, new int[]{actor.getAnimation(), tick});
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		int tick = client.getTickCount();
		if (e.getSkill() == Skill.HITPOINTS)
		{
			int hp = e.getBoostedLevel();
			// +1 is natural regeneration; anything bigger is food, a potion or a heal
			if (lastHp >= 0 && hp - lastHp >= 2)
			{
				tracker.onHeal(tick, hp - lastHp, hp);
			}
			tracker.onHp(tick, hp);
			lastHp = hp;
		}
		else if (e.getSkill() == Skill.PRAYER)
		{
			int prayer = e.getBoostedLevel();
			if (lastPrayer >= 0 && prayer < lastPrayer)
			{
				tracker.onPrayerUsed(lastPrayer - prayer);
			}
			lastPrayer = prayer;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarpId() != SPEC_ENERGY_VARP)
		{
			return;
		}
		int energy = e.getValue();
		if (specEnergy >= 0 && energy < specEnergy)
		{
			lastSpecTick = client.getTickCount();
			tracker.onSpec(lastSpecTick);
		}
		specEnergy = energy;
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		Actor actor = e.getActor();
		if (actor == null)
		{
			return;
		}
		int tick = client.getTickCount();
		if (actor == client.getLocalPlayer())
		{
			tracker.onMyDeath(tick);
			RetrospectConfig.Capture mode = config.myDeath();
			if (!mode.clip() && folderWants(false))
			{
				mode = mode.screenshot() ? RetrospectConfig.Capture.BOTH : RetrospectConfig.Capture.CLIP;
			}
			fire("death", "death", mode);
			return;
		}
		String name = actor.getName();
		if (actor instanceof Player && name != null)
		{
			Integer hitAt = playersHit.get(name);
			if (hitAt != null && tick - hitAt <= 25)
			{
				fire("kill", "kill " + name, config.pkKill());
			}
		}
		if (tracker.isActive() && name != null && name.equals(tracker.getCurrent().target) && folderWants(true))
		{
			fire("fightkill", "kill " + name, RetrospectConfig.Capture.CLIP);
		}
		tracker.onActorDeath(tick, name);
	}

	/** In Serious mode, does the current fight's folder ask for a clip of every kill (or every death)? */
	private boolean folderWants(boolean kill)
	{
		if (!serious() || !tracker.isActive())
		{
			return false;
		}
		Encounter e = tracker.getCurrent();
		FightFolders.Folder f = folders.forFight(e.target, e.targetId, e.targetIsPlayer, true);
		return f != null && f.log && (kill ? f.clipKills : f.clipDeaths);
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		int tick = client.getTickCount();
		if (tracker.isActive() && focus != null && focus.getName() != null
			&& focus.getName().equals(tracker.getCurrent().target))
		{
			tracker.onTargetHp(tick, healthPct(focus));
		}
		finishFight(tracker.onTick(tick));
		if (tick % 100 == 0)
		{
			playersHit.values().removeIf(t -> tick - t > 100);
			lastMoment.values().removeIf(t -> tick - t > 100);
		}
	}

	private void finishFight(Encounter e)
	{
		if (e == null)
		{
			return;
		}
		focus = null;
		if (!config.debrief())
		{
			return;
		}
		boolean died = e.outcome == Encounter.Outcome.DEATH;
		if (!died && (e.seconds() < config.minFightSeconds() || e.hits == 0))
		{
			return;
		}
		final Debrief d = DebriefBuilder.build(e, history.against(e.target), moveNames::get, config.lingo());
		if (lastClip != null && client.getTickCount() - lastClipTick <= 25)
		{
			d.clip = lastClip;
		}
		if (config.chatTakeaway())
		{
			say(d.title + (d.vibe == null ? "" : " (" + d.vibe + ")") + ". " + d.takeaway);
			if (d.tip != null)
			{
				say("Try: " + d.tip);
			}
		}

		// Serious mode: file this fight in its folder
		File fileInto = null;
		if (serious())
		{
			FightFolders.Folder f = folders.forFight(e.target, e.targetId, e.targetIsPlayer, true);
			if (f != null && f.log)
			{
				folders.record(f, e);
				fileInto = new File(Recorder.FOLDERS, f.dir);
				fightFolder = f;
				fightFolderTick = client.getTickCount();
			}
			saveFolders();
			refreshFolders();
		}

		final FightSummary summary = FightSummary.of(e, d.takeaway, d.clip);
		final File dir = fileInto;
		final String stamp = STAMP.format(Instant.ofEpochMilli(e.startedAtMs));
		final String result = e.outcome == Encounter.Outcome.KILL ? "kill" : died ? "death" : "left";
		executor.execute(() ->
		{
			try
			{
				history.add(summary);
				if (dir != null)
				{
					dir.mkdirs();
					File out = new File(dir, stamp + "_" + result + "_debrief.txt");
					Files.write(out.toPath(), d.toText().getBytes(StandardCharsets.UTF_8));
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Could not save the debrief", ex);
			}
			refreshRecent();
		});
		final RetrospectPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showDebrief(d));
		}
	}

	// ---------- moments worth saving ----------

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		ChatMessageType type = e.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		if (msg.startsWith("[Retrospect]"))
		{
			return;
		}
		if (msg.contains("(new personal best)"))
		{
			fire("pb", "personal best", config.personalBest());
		}
		Matcher drop = VALUABLE_DROP.matcher(msg);
		if (drop.find())
		{
			try
			{
				long value = Long.parseLong(drop.group(2).replace(",", ""));
				if (value >= config.dropValue())
				{
					fire("drop", "drop " + drop.group(1) + " " + shortGp(value), config.valuableDrop());
				}
			}
			catch (NumberFormatException ignored)
			{
				// not a number we understand: let the loot event handle it
			}
		}
		if (msg.startsWith(COLLECTION_LOG))
		{
			fire("collection", "collection log " + msg.substring(COLLECTION_LOG.length()).trim(), config.collection());
		}
		for (String pet : PET_MESSAGES)
		{
			if (msg.contains(pet))
			{
				fire("collection", "pet", config.collection());
				break;
			}
		}
		Matcher kc = KILL_COUNT.matcher(msg);
		if (kc.find())
		{
			fire("kc", kc.group(1) + " kill " + kc.group(2).replace(",", ""), config.bossKill());
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		long value = worth(e.getItems());
		tracker.onLoot(client.getTickCount(), value);
		if (value >= config.dropValue())
		{
			String from = e.getNpc() == null || e.getNpc().getName() == null ? "" : e.getNpc().getName() + " ";
			fire("drop", "drop " + from + shortGp(value), config.valuableDrop());
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived e)
	{
		String name = e.getPlayer() == null ? null : e.getPlayer().getName();
		// The kill itself is normally caught at the moment they die; this covers the times it is not
		fire("kill", "kill " + (name == null ? "player" : name), config.pkKill());
		long value = worth(e.getItems());
		tracker.onLoot(client.getTickCount(), value);
		if (value >= config.dropValue())
		{
			fire("drop", "pk loot " + shortGp(value), config.valuableDrop());
		}
	}

	private long worth(Collection<ItemStack> items)
	{
		long total = 0;
		if (items != null)
		{
			for (ItemStack item : items)
			{
				total += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
			}
		}
		return total;
	}

	private static String shortGp(long gp)
	{
		return DebriefBuilder.gp(gp);
	}

	/** The per-moment capture settings, for the Customize tab. Keyed by config key. */
	private Map<String, RetrospectConfig.Capture> momentSettings()
	{
		Map<String, RetrospectConfig.Capture> m = new LinkedHashMap<>();
		m.put("myDeath", config.myDeath());
		m.put("pkKill", config.pkKill());
		m.put("valuableDrop", config.valuableDrop());
		m.put("personalBest", config.personalBest());
		m.put("collection", config.collection());
		m.put("bossKill", config.bossKill());
		m.put("bigHit", config.bigHit());
		return m;
	}

	/** Saves a moment, unless the same kind of moment was saved a few ticks ago. Game thread only. */
	private void fire(String kind, String label, RetrospectConfig.Capture mode)
	{
		mode = config.saving().apply(mode);
		if (mode == RetrospectConfig.Capture.OFF)
		{
			return;
		}
		int tick = client.getTickCount();
		Integer last = lastMoment.get(kind);
		// A player kill is seen twice (the death, then the loot), a few seconds apart
		int quiet = "kill".equals(kind) ? 20 : 8;
		if (last != null && tick - last < quiet)
		{
			return;
		}
		lastMoment.put(kind, tick);
		String clip = recorder.moment(label, mode, momentDir());
		if (clip != null)
		{
			lastClip = clip;
			lastClipTick = tick;
		}
	}

	private void clipNow()
	{
		if (!config.recordClips())
		{
			say("Rolling recording is switched off, so there is nothing to clip. Turn it on in the Retrospect settings.");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		lastMoment.remove("manual");
		fire("manual", "clip", RetrospectConfig.Capture.CLIP);
		say("Clipping the last " + config.secondsBefore() + " seconds...");
	}

	// ---------- talking to the player ----------

	private void onSaved(File file, String kind)
	{
		if (config.chatSaves())
		{
			clientThread.invokeLater(() -> say("Saved " + kind + ": " + file.getName()));
		}
	}

	/** Prints a line only this player sees. Game thread only. */
	private void say(String message)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			ColorUtil.wrapWithColorTag("[Retrospect]", GOLD) + " " + message, null);
	}

	private void refreshRecent()
	{
		final RetrospectPanel p = panel;
		final FightHistory h = history;
		if (p == null || h == null)
		{
			return;
		}
		final List<FightSummary> recent = h.recent(12);
		SwingUtilities.invokeLater(() -> p.setRecent(recent));
	}

	private void refreshStatus()
	{
		final RetrospectPanel p = panel;
		if (p == null)
		{
			return;
		}
		final String text;
		if (!config.recordClips())
		{
			text = "Screenshots only. Rolling recording is off.";
		}
		else if (!recorder.isRecording())
		{
			text = "Waiting for you to log in.";
		}
		else
		{
			text = "Recording the last " + config.secondsBefore() + " seconds, in memory only. Nothing is saved until a moment happens.";
		}
		final boolean seriousNow = serious();
		SwingUtilities.invokeLater(() ->
		{
			p.setStatus(text);
			p.setSerious(seriousNow);
		});
	}

	/**
	 * Puts a folder's path on the clipboard. Plugins are not allowed to open a file manager,
	 * so the path is handed to the player to paste instead.
	 */
	private static String copyPath(File dir)
	{
		dir.mkdirs();
		final String path = dir.getAbsolutePath();
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not copy the folder path", e);
		}
		return path;
	}
}
