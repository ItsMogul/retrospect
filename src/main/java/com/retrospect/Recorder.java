package com.retrospect;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the rolling recording and saves clips and screenshots when a moment happens.
 * All the slow work (compressing and writing) happens off the game thread.
 *
 * Frames come from RuneLite's own renderer, the same source the built-in Screenshot plugin
 * uses, so only the game image can ever be captured: never the desktop or another window.
 */
@Singleton
class Recorder
{
	private static final Logger log = LoggerFactory.getLogger(Recorder.class);

	static final File ROOT = new File(RuneLite.RUNELITE_DIR, "retrospect");
	static final File CLIPS = new File(ROOT, "clips");
	static final File SHOTS = new File(ROOT, "screenshots");
	static final File FOLDERS = new File(ROOT, "folders");
	/** A run of back-to-back moments is saved as one clip, but never longer than this. */
	private static final long MAX_CLIP_MS = 45_000;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH-mm-ss");

	private static final class Pending
	{
		long startMs;
		long endMs;
		String base;
		File dir;
	}

	private final DrawManager drawManager;
	private final RetrospectConfig config;
	private final FrameBuffer buffer = new FrameBuffer();
	private final AtomicBoolean frameInFlight = new AtomicBoolean();

	private ScheduledExecutorService timer;
	private ExecutorService compressor;
	private ExecutorService writer;
	private ScheduledFuture<?> captureTask;
	private volatile boolean active;
	private Pending pending;
	/** Told about every saved file: (file, what kind of thing it is). */
	private volatile BiConsumer<File, String> onSaved = (f, k) -> { };

	@Inject
	Recorder(DrawManager drawManager, RetrospectConfig config)
	{
		this.drawManager = drawManager;
		this.config = config;
	}

	void setOnSaved(BiConsumer<File, String> onSaved)
	{
		this.onSaved = onSaved;
	}

	boolean isRecording()
	{
		return active && config.recordClips() && captureTask != null;
	}

	double bufferMegabytes()
	{
		return buffer.getBytes() / 1048576.0;
	}

	synchronized void start()
	{
		timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "retrospect-timer"));
		compressor = Executors.newSingleThreadExecutor(r -> daemon(r, "retrospect-compress"));
		writer = Executors.newSingleThreadExecutor(r -> daemon(r, "retrospect-writer"));
		applyConfig();
	}

	synchronized void stop()
	{
		active = false;
		if (captureTask != null)
		{
			captureTask.cancel(false);
			captureTask = null;
		}
		for (ExecutorService e : new ExecutorService[]{timer, compressor, writer})
		{
			if (e != null)
			{
				e.shutdown();
			}
		}
		timer = null;
		compressor = null;
		writer = null;
		pending = null;
		buffer.clear();
		frameInFlight.set(false);
	}

	private static Thread daemon(Runnable r, String name)
	{
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	}

	/** Only record while logged in to the game. */
	void setActive(boolean active)
	{
		this.active = active;
		if (!active)
		{
			buffer.clear();
		}
	}

	/** Re-reads the settings and restarts the capture timer. */
	synchronized void applyConfig()
	{
		if (timer == null)
		{
			return;
		}
		buffer.setKeepMs((config.secondsBefore() + config.secondsAfter() + 3) * 1000L);
		buffer.setMaxBytes(config.maxMemoryMb() * 1024L * 1024L);
		if (captureTask != null)
		{
			captureTask.cancel(false);
			captureTask = null;
		}
		if (config.recordClips())
		{
			long period = Math.max(50, 1000 / Math.max(1, config.fps()));
			captureTask = timer.scheduleAtFixedRate(this::requestFrame, period, period, TimeUnit.MILLISECONDS);
		}
		else
		{
			buffer.clear();
		}
	}

	private void requestFrame()
	{
		try
		{
			if (!active || !frameInFlight.compareAndSet(false, true))
			{
				return; // logged out, or the last frame is still being handled: skip this one
			}
			final int height = config.clipSize().height;
			drawManager.requestNextFrameListener(image ->
			{
				final long now = System.currentTimeMillis();
				ExecutorService c = compressor;
				if (c == null)
				{
					frameInFlight.set(false);
					return;
				}
				try
				{
					c.execute(() ->
					{
						try
						{
							if (active && config.recordClips())
							{
								buffer.add(image, now, height, 0.72f);
							}
							// Recording may have been switched off while this frame was being compressed
							if (!active || !config.recordClips())
							{
								buffer.clear();
							}
						}
						catch (IOException | RuntimeException e)
						{
							log.debug("Could not buffer a frame", e);
						}
						finally
						{
							frameInFlight.set(false);
						}
					});
				}
				catch (RejectedExecutionException e)
				{
					frameInFlight.set(false); // the plugin was switched off between the request and the frame
				}
			});
		}
		catch (RuntimeException e)
		{
			frameInFlight.set(false);
			log.debug("Frame request failed", e);
		}
	}

	/**
	 * Something worth keeping just happened.
	 *
	 * @return the name the clip will be saved under (no extension), or null if no clip is being made
	 */
	String moment(String label, RetrospectConfig.Capture mode)
	{
		return moment(label, mode, null);
	}

	/**
	 * @param folder file this moment here instead of the dated clips and screenshots folders
	 *               (used in Serious mode to keep a boss's media together). Null for the usual place.
	 */
	String moment(String label, RetrospectConfig.Capture mode, File folder)
	{
		if (mode == null || mode == RetrospectConfig.Capture.OFF || timer == null)
		{
			return null;
		}
		LocalDateTime when = LocalDateTime.now();
		String safe = ClipWriter.safeName(label);
		if (mode.screenshot())
		{
			final File file = folder != null
				? new File(folder, when.format(DAY) + "_" + when.format(TIME) + "_" + safe + ".png")
				: new File(new File(SHOTS, when.format(DAY)), when.format(TIME) + "_" + safe + ".png");
			drawManager.requestNextFrameListener(image -> screenshot(image, file));
		}
		if (!mode.clip() || !config.recordClips() || !active)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		long after = config.secondsAfter() * 1000L;
		synchronized (this)
		{
			if (pending != null)
			{
				// A second moment while a clip is still rolling: stretch that clip instead of starting another
				pending.endMs = Math.min(Math.max(pending.endMs, now + after), pending.startMs + MAX_CLIP_MS);
				return pending.base;
			}
			pending = new Pending();
			pending.startMs = now - config.secondsBefore() * 1000L;
			pending.endMs = now + after;
			pending.dir = folder != null ? folder : new File(CLIPS, when.format(DAY));
			pending.base = (folder != null ? when.format(DAY) + "_" : "") + when.format(TIME) + "_" + safe;
			timer.schedule(this::finishPending, after + 400, TimeUnit.MILLISECONDS);
			return pending.base;
		}
	}

	private void finishPending()
	{
		final Pending p;
		synchronized (this)
		{
			p = pending;
			if (p == null || timer == null)
			{
				return;
			}
			long wait = p.endMs + 400 - System.currentTimeMillis();
			if (wait > 0)
			{
				timer.schedule(this::finishPending, wait, TimeUnit.MILLISECONDS);
				return;
			}
			pending = null;
		}
		final List<FrameBuffer.Frame> frames = buffer.snapshot(p.startMs, p.endMs);
		final int fps = Math.max(1, config.fps());
		final long capBytes = config.maxStorageGb() * 1024L * 1024L * 1024L;
		ExecutorService w = writer;
		if (w == null)
		{
			return;
		}
		try
		{
			w.execute(() -> saveClip(p, frames, fps, capBytes));
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Clip {} dropped: the plugin was switched off", p.base);
		}
	}

	private void saveClip(Pending p, List<FrameBuffer.Frame> frames, int fps, long capBytes)
	{
		if (frames.size() < 2)
		{
			log.debug("No frames buffered for clip {}", p.base);
			return;
		}
		try
		{
			p.dir.mkdirs();
			File clip = new File(p.dir, p.base + ".avi");
			ClipWriter.writeAvi(frames, fps, clip);
			ClipWriter.enforceCap(CLIPS.toPath(), capBytes, clip);
			onSaved.accept(clip, "clip");
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not save clip {}", p.base, e);
		}
	}

	private void screenshot(Image image, File file)
	{
		final BufferedImage copy = ImageUtil.bufferedImageFromImage(image);
		ExecutorService w = writer;
		if (w == null)
		{
			return;
		}
		try
		{
			w.execute(() ->
			{
				try
				{
					file.getParentFile().mkdirs();
					ImageIO.write(copy, "png", file);
					onSaved.accept(file, "screenshot");
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Could not save screenshot {}", file, e);
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Screenshot dropped: the plugin was switched off");
		}
	}
}
