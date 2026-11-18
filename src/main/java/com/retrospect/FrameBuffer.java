package com.retrospect;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Keeps the last few seconds of the game screen in memory as small JPEGs.
 * Nothing is written to disk until a moment is worth saving.
 */
final class FrameBuffer
{
	/** One captured frame: when it was taken and its JPEG bytes. */
	static final class Frame
	{
		final long timeMs;
		final byte[] jpeg;
		final int width;
		final int height;

		Frame(long timeMs, byte[] jpeg, int width, int height)
		{
			this.timeMs = timeMs;
			this.jpeg = jpeg;
			this.width = width;
			this.height = height;
		}
	}

	/** Memory ceiling until the settings say otherwise. RuneLite runs with 768 MB in total by default. */
	static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

	private final ArrayDeque<Frame> frames = new ArrayDeque<>();
	private long bytes;
	private long keepMs = 20_000;
	private long maxBytes = DEFAULT_MAX_BYTES;

	synchronized void setKeepMs(long keepMs)
	{
		this.keepMs = Math.max(1_000, keepMs);
	}

	/** When the buffer would grow past this, the oldest frames go first, so clips get shorter instead of the client running out of memory. */
	synchronized void setMaxBytes(long maxBytes)
	{
		this.maxBytes = Math.max(8L * 1024 * 1024, maxBytes);
	}

	synchronized long getBytes()
	{
		return bytes;
	}

	synchronized int size()
	{
		return frames.size();
	}

	synchronized void clear()
	{
		frames.clear();
		bytes = 0;
	}

	/** Shrinks, compresses and stores one frame. Safe to call from a background thread. */
	void add(Image image, long timeMs, int maxHeight, float quality) throws IOException
	{
		BufferedImage scaled = scale(image, maxHeight);
		byte[] jpeg = toJpeg(scaled, quality);
		Frame frame = new Frame(timeMs, jpeg, scaled.getWidth(), scaled.getHeight());
		synchronized (this)
		{
			frames.addLast(frame);
			bytes += jpeg.length;
			while (!frames.isEmpty() && (frames.peekFirst().timeMs < timeMs - keepMs || bytes > maxBytes))
			{
				bytes -= frames.removeFirst().jpeg.length;
			}
		}
	}

	/** Copies out every frame between the two times (inclusive), oldest first. */
	synchronized List<Frame> snapshot(long fromMs, long toMs)
	{
		List<Frame> out = new ArrayList<>();
		for (Frame f : frames)
		{
			if (f.timeMs >= fromMs && f.timeMs <= toMs)
			{
				out.add(f);
			}
		}
		return out;
	}

	/** Scales down to at most maxHeight, keeping the shape, with even width and height (video needs that). */
	static BufferedImage scale(Image image, int maxHeight)
	{
		int w = image.getWidth(null);
		int h = image.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			throw new IllegalArgumentException("empty frame");
		}
		double ratio = h > maxHeight ? (double) maxHeight / h : 1.0;
		int tw = Math.max(2, (int) Math.round(w * ratio) & ~1);
		int th = Math.max(2, (int) Math.round(h * ratio) & ~1);
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, tw, th, null);
		g.dispose();
		return out;
	}

	static byte[] toJpeg(BufferedImage image, float quality) throws IOException
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		try
		{
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(Math.max(0.3f, Math.min(0.95f, quality)));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);
			try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
			{
				writer.setOutput(out);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return bytes.toByteArray();
		}
		finally
		{
			writer.dispose();
		}
	}
}
