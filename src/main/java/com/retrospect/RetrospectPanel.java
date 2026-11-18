package com.retrospect;

import java.util.LinkedHashMap;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel. Three tabs: the last fight, the boss folders, and a Customize tab
 * where every heading and colour can be changed and put back.
 */
class RetrospectPanel extends PluginPanel
{
	/** What the panel asks the plugin to do. */
	interface Actions
	{
		void clipNow();

		void copyRoot();

		void toggleMode();

		/** Steps the master saving switch to its next setting. */
		void cycleSaving();

		/** Sets one moment's capture setting, by its config key. */
		void setMoment(String key, RetrospectConfig.Capture value);

		void lookChanged();

		void foldersChanged();

		void copyFolderPath(FightFolders.Folder folder);

		void createFolder(String name, String targets);
	}

	// RuneScape interface brown, with gold for anything you can press
	private static final Color BACK = new Color(0x2B2118);
	private static final Color CARD = new Color(0x3A2D1F);
	private static final Color EDGE = new Color(0x6B5230);
	private static final Color BUTTON = new Color(0x54401F);
	private static final Color GOLD = new Color(0xF0B93A);
	private static final Color DIM = new Color(0xA8987A);
	/** Usable width inside the side panel, and inside a padded card within it. */
	private static final int WIDE = PANEL_WIDTH - 2 * BORDER_OFFSET;
	private static final int INNER = WIDE - 16;
	private static final String[] TABS = {"Fight", "Folders", "Customize"};

	private final Actions actions;
	private final JPanel column = new JPanel();
	private PanelLook look;
	private String tab = TABS[0];
	private String status = "Starting...";
	private boolean serious;
	private String saving = "Clips + shots";
	private Map<String, RetrospectConfig.Capture> moments = new LinkedHashMap<>();
	private boolean detailOpen;
	private Debrief shown;
	private List<FightSummary> recent = new ArrayList<>();
	private List<FightFolders.Folder> folders = new ArrayList<>();

	RetrospectPanel(Actions actions, PanelLook look)
	{
		this.actions = actions;
		this.look = look;
		setLayout(new BorderLayout());
		setBackground(BACK);
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(BACK);
		add(column, BorderLayout.NORTH);
		rebuild();
	}

	// ---------- what the plugin tells the panel ----------

	void setStatus(String text)
	{
		status = text;
		rebuild();
	}

	void setSerious(boolean serious)
	{
		this.serious = serious;
		rebuild();
	}

	/** @param saving  label of the master switch  @param moments per-moment settings by config key */
	void setSaving(String saving, Map<String, RetrospectConfig.Capture> moments)
	{
		this.saving = saving;
		this.moments = moments == null ? new LinkedHashMap<>() : moments;
		rebuild();
	}

	/** Shows a fight and jumps to the Fight tab. Null shows the empty state. */
	void showDebrief(Debrief d)
	{
		shown = d;
		if (d != null)
		{
			tab = TABS[0];
		}
		rebuild();
	}

	void setRecent(List<FightSummary> fights)
	{
		recent = fights;
		rebuild();
	}

	void setFolders(List<FightFolders.Folder> list)
	{
		folders = list;
		rebuild();
	}

	private static RetrospectConfig.Capture nextCapture(RetrospectConfig.Capture now)
	{
		RetrospectConfig.Capture[] all = RetrospectConfig.Capture.values();
		return all[(now.ordinal() + 1) % all.length];
	}

	private static String shortCapture(RetrospectConfig.Capture c)
	{
		switch (c)
		{
			case SCREENSHOT:
				return "shot";
			case CLIP:
				return "clip";
			case BOTH:
				return "both";
			default:
				return "off";
		}
	}

	// ---------- drawing ----------

	private void rebuild()
	{
		column.removeAll();

		JLabel title = new JLabel(look.label("title"));
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		title.setForeground(look.color("accent"));
		column.add(left(title));
		JTextArea statusText = body(status, DIM, FontManager.getRunescapeSmallFont(), WIDE);
		statusText.setBorder(null);
		column.add(statusText);
		column.add(gap(6));

		column.add(row(button("Clip that", "Save the last few seconds as a clip", actions::clipNow),
			button("Copy path", "Copies the path to your clips, screenshots and folders", actions::copyRoot)));
		column.add(gap(4));
		column.add(row(button("Mode: " + (serious ? "Serious" : "Everyday"),
			serious ? "Serious: every fight's debrief and clips are filed into its folder. Click for Everyday."
				: "Everyday: catches your moments and shows the debrief, files nothing. Click for Serious.",
			actions::toggleMode)));
		column.add(gap(4));
		boolean quiet = "Off".equals(saving);
		JButton savingButton = button("Saving: " + saving,
			quiet ? "Nothing is being saved automatically. Clip that still works. Click to turn saving back on."
				: "What your moments save automatically. Click to step through clips only, shots only, off.",
			actions::cycleSaving);
		if (quiet)
		{
			savingButton.setForeground(new Color(0xE8765A));
		}
		column.add(row(savingButton));
		column.add(gap(8));

		JButton[] tabButtons = new JButton[TABS.length];
		for (int i = 0; i < TABS.length; i++)
		{
			final String name = TABS[i];
			tabButtons[i] = button(name, null, () ->
			{
				tab = name;
				rebuild();
			});
			tabButtons[i].setEnabled(!name.equals(tab));
		}
		column.add(row(tabButtons));
		column.add(gap(8));

		if (TABS[1].equals(tab))
		{
			foldersTab();
		}
		else if (TABS[2].equals(tab))
		{
			customizeTab();
		}
		else
		{
			fightTab();
		}
		column.revalidate();
		column.repaint();
	}

	private void fightTab()
	{
		Color text = look.color("text");
		JPanel card = card();
		Debrief d = shown;
		if (d == null)
		{
			card.add(heading("NO FIGHT YET", look.color("accent")));
			card.add(body("Fight something for 20 seconds or more and the takeaway shows up here.", text));
		}
		else
		{
			card.add(body(d.title, DIM));
			if (d.vibe != null)
			{
				card.add(body(d.vibe, look.color("accent"), FontManager.getRunescapeBoldFont(), INNER));
			}
			if (d.fought != null)
			{
				card.add(body(d.fought, DIM));
			}
			card.add(gap(6));
			card.add(heading(look.label("takeaway"), look.color("accent")));
			card.add(body(d.takeaway, Color.WHITE, FontManager.getRunescapeFont(), INNER));
			if (d.tip != null)
			{
				card.add(gap(8));
				card.add(heading(look.label("tip"), look.color("accent")));
				card.add(body(d.tip, text));
			}
			bullets(card, look.label("strong"), d.strong, look.color("strong"), text);
			bullets(card, look.label("weak"), d.weak, look.color("weak"), text);
			if (d.clip != null)
			{
				card.add(gap(8));
				card.add(body("Clip: " + d.clip, look.color("accent")));
			}
			card.add(gap(8));
			JButton more = button(detailOpen ? "Hide the detail" : "Show the detail", null, () ->
			{
				detailOpen = !detailOpen;
				rebuild();
			});
			card.add(left(more));
			if (detailOpen)
			{
				bullets(card, look.label("numbers"), d.stats, look.color("accent"), text);
				card.add(gap(8));
				card.add(heading(look.label("story"), look.color("accent")));
				card.add(body(String.join("\n", d.timeline), text, new Font(Font.MONOSPACED, Font.PLAIN, 10), INNER));
			}
		}
		column.add(card);

		if (!recent.isEmpty())
		{
			column.add(gap(10));
			column.add(heading(look.label("recent"), look.color("accent")));
			for (FightSummary f : recent)
			{
				String result = f.isKill() ? "kill" : "DEATH".equals(f.outcome) ? "died" : "left";
				JPanel row = card();
				row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(4, 7, 4, 7)));
				row.add(body(f.target + "  ·  " + result + "  ·  " + DebriefBuilder.clock(f.durationTicks),
					f.isKill() ? text : look.color("weak")));
				if (f.takeaway != null)
				{
					row.add(body(f.takeaway, DIM));
				}
				column.add(row);
				column.add(gap(4));
			}
		}
	}

	private void bullets(JPanel card, String name, List<String> lines, Color headingColor, Color text)
	{
		if (lines.isEmpty())
		{
			return;
		}
		card.add(gap(8));
		card.add(heading(name, headingColor));
		for (String s : lines)
		{
			card.add(body("• " + s, text));
		}
	}

	private void foldersTab()
	{
		Color text = look.color("text");
		column.add(body(serious
			? "Serious mode is on. Each fight's debrief and clips are filed into its folder below. A folder is made the first time you fight something."
			: "Folders fill up in Serious mode. Switch the mode above when you want fights filed and kept.",
			DIM, FontManager.getRunescapeSmallFont(), WIDE));
		column.add(gap(6));
		column.add(row(button("+ Create custom", "A folder of your own, for any set of bosses", this::askForFolder)));
		column.add(gap(8));
		if (folders.isEmpty())
		{
			JPanel empty = card();
			empty.add(body("No folders yet.", text));
			column.add(empty);
			return;
		}
		for (final FightFolders.Folder f : folders)
		{
			JPanel c = card();
			c.add(body(f.name + (f.npcId >= 0 ? "  #" + f.npcId : ""), Color.WHITE, FontManager.getRunescapeBoldFont(), INNER));
			c.add(body(f.statsLine(), DIM));
			if (f.custom)
			{
				c.add(body(f.targets == null || f.targets.trim().isEmpty() ? "Files: nothing yet" : "Files: " + f.targets, DIM));
			}
			c.add(gap(4));
			c.add(check("File fights here", "Off: fights against this are not filed, even in Serious mode", f.log, v -> f.log = v));
			c.add(check("Clip every kill", "Also save a clip of each kill", f.clipKills, v -> f.clipKills = v));
			c.add(check("Clip every death", "Also save a clip of each death", f.clipDeaths, v -> f.clipDeaths = v));
			c.add(gap(4));
			JButton open = button("Copy path", "Copies the path to this folder", () -> actions.copyFolderPath(f));
			if (f.custom)
			{
				JButton names = button("Edit names", "Choose which bosses are filed here", () ->
				{
					Object typed = JOptionPane.showInputDialog(this,
						"Names filed into \"" + f.name + "\", separated by commas.\nType them exactly as the game shows them.",
						"Edit names", JOptionPane.PLAIN_MESSAGE, null, null, f.targets == null ? "" : f.targets);
					if (typed != null)
					{
						f.targets = typed.toString().trim();
						actions.foldersChanged();
						rebuild();
					}
				});
				JPanel r = row(open, names);
				r.setBackground(CARD);
				r.setMaximumSize(new Dimension(INNER, 26));
				c.add(r);
			}
			else
			{
				c.add(left(open));
			}
			column.add(c);
			column.add(gap(4));
		}
	}

	private void askForFolder()
	{
		String name = JOptionPane.showInputDialog(this, "Name for the new folder:", "Create custom folder", JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		String targets = JOptionPane.showInputDialog(this,
			"Which bosses go in it? Separate names with commas,\nexactly as the game shows them. You can change this later.",
			"Create custom folder", JOptionPane.PLAIN_MESSAGE);
		actions.createFolder(name, targets == null ? "" : targets);
	}

	/** Config key to the words a player would use for it. */
	private static final String[][] MOMENT_LABELS = {
		{"myDeath", "Your death"},
		{"pkKill", "Player kill"},
		{"valuableDrop", "Valuable drop"},
		{"personalBest", "Personal best"},
		{"collection", "Pet or coll. log"},
		{"bossKill", "Every boss kill"},
		{"bigHit", "Big hit"},
	};

	private void customizeTab()
	{
		JPanel saves = card();
		saves.add(heading("WHAT GETS SAVED", look.color("accent")));
		saves.add(body("Each moment on its own. The Saving switch above still overrules all of them.",
			DIM, FontManager.getRunescapeSmallFont(), INNER));
		saves.add(gap(4));
		for (final String[] moment : MOMENT_LABELS)
		{
			final RetrospectConfig.Capture now = moments.getOrDefault(moment[0], RetrospectConfig.Capture.OFF);
			JButton b = button(moment[1] + ": " + shortCapture(now),
				"Click to step through off, screenshot, clip, both",
				() -> actions.setMoment(moment[0], nextCapture(now)));
			if (now == RetrospectConfig.Capture.OFF)
			{
				b.setForeground(DIM);
			}
			saves.add(b);
			saves.add(gap(3));
		}
		column.add(saves);
		column.add(gap(8));

		column.add(body("Click a heading and type to rename it, then press Enter. Empty puts the stock wording back.",
			DIM, FontManager.getRunescapeSmallFont(), WIDE));
		column.add(gap(6));
		JPanel c = card();
		for (final Map.Entry<String, String> en : PanelLook.STOCK_LABELS.entrySet())
		{
			final JTextField field = new JTextField(look.label(en.getKey()));
			field.setFont(FontManager.getRunescapeSmallFont());
			field.setForeground(look.color("accent"));
			field.setCaretColor(Color.WHITE);
			field.setBackground(BACK);
			field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(2, 5, 2, 5)));
			field.setToolTipText("Stock: " + en.getValue());
			field.setMaximumSize(new Dimension(INNER, 24));
			field.setPreferredSize(new Dimension(INNER, 24));
			field.setAlignmentX(Component.LEFT_ALIGNMENT);
			final Runnable keep = () ->
			{
				String before = look.label(en.getKey());
				look.setLabel(en.getKey(), field.getText());
				if (!before.equals(look.label(en.getKey())))
				{
					actions.lookChanged();
				}
			};
			field.addActionListener(e ->
			{
				keep.run();
				rebuild();
			});
			field.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					keep.run();
				}
			});
			c.add(field);
			c.add(gap(4));
		}
		column.add(c);
		column.add(gap(8));

		JPanel colours = card();
		colours.add(heading("COLOURS", look.color("accent")));
		for (final Map.Entry<String, String> en : PanelLook.COLOR_NAMES.entrySet())
		{
			JButton swatch = button(en.getValue(), "Click to pick a colour", () ->
			{
				Color picked = JColorChooser.showDialog(this, en.getValue(), look.color(en.getKey()));
				if (picked != null)
				{
					look.setColor(en.getKey(), picked);
					actions.lookChanged();
					rebuild();
				}
			});
			swatch.setIcon(new Swatch(look.color(en.getKey())));
			swatch.setIconTextGap(8);
			colours.add(left(swatch));
			colours.add(gap(4));
		}
		column.add(colours);
		column.add(gap(8));

		JButton revert = button("Revert to stock", "Put every heading and colour back", () ->
		{
			look.reset();
			actions.lookChanged();
			rebuild();
		});
		if (look.isStock())
		{
			// Nothing to put back: show it as unavailable, not as the selected tab
			revert.setEnabled(false);
			revert.setBackground(CARD);
			revert.setForeground(DIM);
		}
		column.add(row(revert));
	}

	// ---------- small pieces ----------

	/** A little square of the colour, shown on its button. */
	private static final class Swatch implements Icon
	{
		private final Color color;

		Swatch(Color color)
		{
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			g.setColor(color);
			g.fillRect(x, y, 12, 12);
			g.setColor(Color.BLACK);
			g.drawRect(x, y, 11, 11);
		}

		@Override
		public int getIconWidth()
		{
			return 12;
		}

		@Override
		public int getIconHeight()
		{
			return 12;
		}
	}

	private interface Flag
	{
		void set(boolean value);
	}

	private JCheckBox check(String text, String tip, boolean on, Flag flag)
	{
		JCheckBox box = new JCheckBox(text, on);
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setForeground(look.color("text"));
		box.setBackground(CARD);
		box.setOpaque(false);
		box.setFocusPainted(false);
		box.setToolTipText(tip);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.addActionListener(e ->
		{
			flag.set(box.isSelected());
			actions.foldersChanged();
		});
		return box;
	}

	private static JButton button(String text, String tip, Runnable action)
	{
		JButton b = new JButton(text)
		{
			@Override
			public void setEnabled(boolean on)
			{
				super.setEnabled(on);
				// A switched-off button is the tab you are on: solid gold instead of greyed out
				setBackground(on ? BUTTON : GOLD);
				setForeground(on ? GOLD : BACK);
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(getBackground());
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(getForeground());
				g.setFont(getFont());
				java.awt.FontMetrics fm = g.getFontMetrics();
				int x = getIcon() == null ? (getWidth() - fm.stringWidth(getText())) / 2 : 8;
				if (getIcon() != null)
				{
					getIcon().paintIcon(this, g, x, (getHeight() - getIcon().getIconHeight()) / 2);
					x += getIcon().getIconWidth() + getIconTextGap();
				}
				g.drawString(getText(), x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
			}
		};
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setMargin(new Insets(3, 2, 3, 2));
		b.setToolTipText(tip);
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setBackground(BUTTON);
		b.setForeground(GOLD);
		b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(4, 8, 4, 8)));
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		b.addActionListener(e -> action.run());
		return b;
	}

	/** Buttons side by side, sharing the full width. */
	private static JPanel row(JComponent... items)
	{
		JPanel p = new JPanel(new GridLayout(1, items.length, 6, 0));
		p.setBackground(BACK);
		for (JComponent c : items)
		{
			p.add(c);
		}
		p.setMaximumSize(new Dimension(WIDE, 28));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static JPanel card()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(CARD);
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(7, 7, 7, 7)));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static JLabel heading(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setBorder(new EmptyBorder(0, 0, 3, 0));
		return l;
	}

	private static JTextArea body(String text, Color color)
	{
		return body(text, color, FontManager.getRunescapeSmallFont(), INNER);
	}

	/**
	 * Wrapping, selectable text (so a takeaway can be copied into Discord).
	 * A text area only knows how tall it is once it knows how wide it is, so the width is
	 * fixed up front. The side panel is always the same width, so that is safe here.
	 */
	private static JTextArea body(String text, Color color, Font font, int width)
	{
		JTextArea a = new JTextArea(text);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setEditable(false);
		a.setOpaque(false);
		a.setForeground(color);
		a.setFont(font);
		a.setBorder(null);
		a.setAlignmentX(Component.LEFT_ALIGNMENT);
		a.setSize(width, Short.MAX_VALUE);
		Dimension size = new Dimension(width, a.getPreferredSize().height);
		a.setPreferredSize(size);
		a.setMaximumSize(size);
		return a;
	}

	private static Component gap(int px)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, px));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, px));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}
}
