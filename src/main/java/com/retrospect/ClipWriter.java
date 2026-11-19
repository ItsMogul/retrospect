package com.retrospect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/** Turns buffered frames into a video file. */
final class ClipWriter
{
	private ClipWriter()
	{
	}

	/**
	 * Picks one frame per output slot so the clip plays at real speed even if some
	 * captures were late or dropped.
	 */
	static List<FrameBuffer.Frame> resample(List<FrameBuffer.Frame> frames, int fps)
	{
		List<FrameBuffer.Frame> out = new ArrayList<>();
		if (frames.isEmpty())
		{
			return out;
		}
		long start = frames.get(0).timeMs;
		long end = frames.get(frames.size() - 1).timeMs;
		int slots = (int) Math.max(1, Math.round((end - start) * fps / 1000.0) + 1);
		int i = 0;
		for (int s = 0; s < slots; s++)
		{
			long want = start + Math.round(s * 1000.0 / fps);
			while (i + 1 < frames.size()
				&& Math.abs(frames.get(i + 1).timeMs - want) <= Math.abs(frames.get(i).timeMs - want))
			{
				i++;
			}
			out.add(frames.get(i));
		}
		return out;
	}

	/** A resized game window mid-clip would break the video, so keep only the most recent size. */
	private static List<FrameBuffer.Frame> sameSize(List<FrameBuffer.Frame> frames)
	{
		if (frames.isEmpty())
		{
			return frames;
		}
		FrameBuffer.Frame ref = frames.get(frames.size() - 1);
		List<FrameBuffer.Frame> out = new ArrayList<>();
		for (FrameBuffer.Frame f : frames)
		{
			if (f.width == ref.width && f.height == ref.height)
			{
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * AVI (Motion JPEG). Needs nothing installed and saves instantly because the frames
	 * are stored as they are. Plays in VLC and most desktop players.
	 */
	static void writeAvi(List<FrameBuffer.Frame> frames, int fps, Filepath file) throws IOException
	{
		List<FrameBuffer.Frame> seq = sameSize(resample(frames, fps));
		if (seq.isEmpty())
		{
			throw new IOException("no frames to save");
		}
		int w = seq.get(0).width;
		int h = seq.get(0).height;
		int n = seq.size();
		int maxFrame = 0;
		for (FrameBuffer.Frame f : seq)
		{
			maxFrame = Math.max(maxFrame, f.jpeg.length);
		}
		try (Out out = new Out(file.openFileChannel(
			StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)))
		{
			out.tag("RIFF");
			long riffSize = out.pos();
			out.le32(0);
			out.tag("AVI ");

			out.tag("LIST");
			long hdrlSize = out.pos();
			out.le32(0);
			out.tag("hdrl");

			out.tag("avih");
			out.le32(56);
			out.le32(1_000_000 / fps); // microseconds per frame
			out.le32(maxFrame * fps);  // peak bytes per second
			out.le32(0);
			out.le32(0x10);            // file has an index
			out.le32(n);
			out.le32(0);
			out.le32(1);               // one stream
			out.le32(maxFrame);
			out.le32(w);
			out.le32(h);
			out.le32(0);
			out.le32(0);
			out.le32(0);
			out.le32(0);

			out.tag("LIST");
			long strlSize = out.pos();
			out.le32(0);
			out.tag("strl");

			out.tag("strh");
			out.le32(56);
			out.tag("vids");
			out.tag("MJPG");
			out.le32(0);
			out.le32(0);
			out.le32(0);
			out.le32(1);   // scale
			out.le32(fps); // rate
			out.le32(0);
			out.le32(n);
			out.le32(maxFrame);
			out.le32(-1);
			out.le32(0);
			out.le16(0);
			out.le16(0);
			out.le16(w);
			out.le16(h);

			out.tag("strf");
			out.le32(40);
			out.le32(40);
			out.le32(w);
			out.le32(h);
			out.le16(1);
			out.le16(24);
			out.tag("MJPG");
			out.le32(w * h * 3);
			out.le32(0);
			out.le32(0);
			out.le32(0);
			out.le32(0);

			out.patch(strlSize);
			out.patch(hdrlSize);

			out.tag("LIST");
			long moviSize = out.pos();
			out.le32(0);
			long moviStart = out.pos();
			out.tag("movi");
			int[] offsets = new int[n];
			int[] sizes = new int[n];
			for (int i = 0; i < n; i++)
			{
				byte[] jpeg = seq.get(i).jpeg;
				offsets[i] = (int) (out.pos() - moviStart);
				sizes[i] = jpeg.length;
				out.tag("00dc");
				out.le32(jpeg.length);
				out.bytes(jpeg);
				if ((jpeg.length & 1) == 1)
				{
					out.u8(0);
				}
			}
			out.patch(moviSize);

			out.tag("idx1");
			out.le32(n * 16);
			for (int i = 0; i < n; i++)
			{
				out.tag("00dc");
				out.le32(0x10); // every frame is a key frame
				out.le32(offsets[i]);
				out.le32(sizes[i]);
			}
			out.patch(riffSize);
		}
	}

	/**
	 * A little buffered writer over the channel Filepath hands us. Plugins are not allowed to
	 * reach for java.io, so this replaces what RandomAccessFile used to do: append little-endian
	 * values, and go back afterwards to fill in a chunk size once its length is known.
	 */
	private static final class Out implements AutoCloseable
	{
		private final FileChannel ch;
		private final ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
		private long flushed;

		Out(FileChannel ch)
		{
			this.ch = ch;
		}

		/** Where the next byte will land, counting what is still sitting in the buffer. */
		long pos()
		{
			return flushed + buf.position();
		}

		private void room(int bytes) throws IOException
		{
			if (buf.remaining() < bytes)
			{
				flush();
			}
		}

		private void flush() throws IOException
		{
			buf.flip();
			while (buf.hasRemaining())
			{
				flushed += ch.write(buf);
			}
			buf.clear();
		}

		void tag(String fourcc) throws IOException
		{
			bytes(fourcc.getBytes(StandardCharsets.US_ASCII));
		}

		void le32(int v) throws IOException
		{
			room(4);
			buf.putInt(v);
		}

		void le16(int v) throws IOException
		{
			room(2);
			buf.putShort((short) v);
		}

		void u8(int v) throws IOException
		{
			room(1);
			buf.put((byte) v);
		}

		void bytes(byte[] b) throws IOException
		{
			if (b.length > buf.capacity())
			{
				flush();
				ByteBuffer direct = ByteBuffer.wrap(b);
				while (direct.hasRemaining())
				{
					flushed += ch.write(direct);
				}
				return;
			}
			room(b.length);
			buf.put(b);
		}

		/** Fills in the 4-byte size field at sizeField with the length of the chunk it opens. */
		void patch(long sizeField) throws IOException
		{
			long end = pos();
			flush();
			ByteBuffer size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			size.putInt((int) (end - sizeField - 4));
			size.flip();
			long at = sizeField;
			while (size.hasRemaining())
			{
				at += ch.write(size, at);
			}
		}

		@Override
		public void close() throws IOException
		{
			try
			{
				flush();
			}
			finally
			{
				ch.close();
			}
		}
	}

	/**
	 * Keeps the clips folder under the storage limit by removing the oldest clips first.
	 * Only ever touches .avi and .mp4 files inside the Retrospect clips folder, and never the newest clip.
	 */
	static void enforceCap(Filepath clipsDir, long capBytes, Filepath keep) throws IOException
	{
		if (!clipsDir.isDirectory())
		{
			return;
		}
		List<Filepath> found;
		try (Stream<Filepath> walk = clipsDir.walk(2))
		{
			found = walk.filter(Filepath::isFile)
				.filter(f -> f.getFileName().endsWith(".avi") || f.getFileName().endsWith(".mp4"))
				.collect(Collectors.toCollection(ArrayList::new));
		}
		// Size and timestamp both throw, so they are read in a plain loop rather than a comparator.
		List<Clip> clips = new ArrayList<>(found.size());
		long total = 0;
		for (Filepath f : found)
		{
			try
			{
				Clip c = new Clip(f, f.size(), f.getLastModifiedTime().toMillis());
				clips.add(c);
				total += c.bytes;
			}
			catch (IOException e)
			{
				// Vanished or unreadable: leave it out of the accounting rather than fail the save.
			}
		}
		clips.sort(Comparator.comparingLong(c -> c.modified));
		for (Clip c : clips)
		{
			if (total <= capBytes)
			{
				break;
			}
			if (keep != null && c.path.equals(keep))
			{
				continue;
			}
			try
			{
				c.path.delete();
				total -= c.bytes;
			}
			catch (IOException e)
			{
				// In use or already gone: skip it and try the next oldest.
			}
		}
	}

	private static final class Clip
	{
		private final Filepath path;
		private final long bytes;
		private final long modified;

		Clip(Filepath path, long bytes, long modified)
		{
			this.path = path;
			this.bytes = bytes;
			this.modified = modified;
		}
	}

	/** Turns "Drop: Vorkath (1.2m)" into something safe for a file name. */
	static String safeName(String label)
	{
		String s = label == null ? "" : label.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (s.length() > 40)
		{
			s = s.substring(0, 40).replaceAll("-+$", "");
		}
		return s.isEmpty() ? "moment" : s;
	}
}
