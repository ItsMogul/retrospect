package com.retrospect;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	static void writeAvi(List<FrameBuffer.Frame> frames, int fps, File file) throws IOException
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
		try (RandomAccessFile out = new RandomAccessFile(file, "rw"))
		{
			out.setLength(0);
			tag(out, "RIFF");
			long riffSize = out.getFilePointer();
			le32(out, 0);
			tag(out, "AVI ");

			tag(out, "LIST");
			long hdrlSize = out.getFilePointer();
			le32(out, 0);
			tag(out, "hdrl");

			tag(out, "avih");
			le32(out, 56);
			le32(out, 1_000_000 / fps); // microseconds per frame
			le32(out, maxFrame * fps);  // peak bytes per second
			le32(out, 0);
			le32(out, 0x10);            // file has an index
			le32(out, n);
			le32(out, 0);
			le32(out, 1);               // one stream
			le32(out, maxFrame);
			le32(out, w);
			le32(out, h);
			le32(out, 0);
			le32(out, 0);
			le32(out, 0);
			le32(out, 0);

			tag(out, "LIST");
			long strlSize = out.getFilePointer();
			le32(out, 0);
			tag(out, "strl");

			tag(out, "strh");
			le32(out, 56);
			tag(out, "vids");
			tag(out, "MJPG");
			le32(out, 0);
			le32(out, 0);
			le32(out, 0);
			le32(out, 1);   // scale
			le32(out, fps); // rate
			le32(out, 0);
			le32(out, n);
			le32(out, maxFrame);
			le32(out, -1);
			le32(out, 0);
			le16(out, 0);
			le16(out, 0);
			le16(out, w);
			le16(out, h);

			tag(out, "strf");
			le32(out, 40);
			le32(out, 40);
			le32(out, w);
			le32(out, h);
			le16(out, 1);
			le16(out, 24);
			tag(out, "MJPG");
			le32(out, w * h * 3);
			le32(out, 0);
			le32(out, 0);
			le32(out, 0);
			le32(out, 0);

			patch(out, strlSize);
			patch(out, hdrlSize);

			tag(out, "LIST");
			long moviSize = out.getFilePointer();
			le32(out, 0);
			long moviStart = out.getFilePointer();
			tag(out, "movi");
			int[] offsets = new int[n];
			int[] sizes = new int[n];
			for (int i = 0; i < n; i++)
			{
				byte[] jpeg = seq.get(i).jpeg;
				offsets[i] = (int) (out.getFilePointer() - moviStart);
				sizes[i] = jpeg.length;
				tag(out, "00dc");
				le32(out, jpeg.length);
				out.write(jpeg);
				if ((jpeg.length & 1) == 1)
				{
					out.write(0);
				}
			}
			patch(out, moviSize);

			tag(out, "idx1");
			le32(out, n * 16);
			for (int i = 0; i < n; i++)
			{
				tag(out, "00dc");
				le32(out, 0x10); // every frame is a key frame
				le32(out, offsets[i]);
				le32(out, sizes[i]);
			}
			patch(out, riffSize);
		}
	}

	private static void patch(RandomAccessFile out, long sizeField) throws IOException
	{
		long end = out.getFilePointer();
		out.seek(sizeField);
		le32(out, (int) (end - sizeField - 4));
		out.seek(end);
	}

	private static void tag(RandomAccessFile out, String fourcc) throws IOException
	{
		out.write(fourcc.getBytes(StandardCharsets.US_ASCII));
	}

	private static void le32(RandomAccessFile out, int v) throws IOException
	{
		out.write(v & 0xff);
		out.write((v >>> 8) & 0xff);
		out.write((v >>> 16) & 0xff);
		out.write((v >>> 24) & 0xff);
	}

	private static void le16(RandomAccessFile out, int v) throws IOException
	{
		out.write(v & 0xff);
		out.write((v >>> 8) & 0xff);
	}

	/**
	 * Keeps the clips folder under the storage limit by removing the oldest clips first.
	 * Only ever touches .avi and .mp4 files inside the Retrospect clips folder, and never the newest clip.
	 */
	static void enforceCap(Path clipsDir, long capBytes, File keep) throws IOException
	{
		if (!Files.isDirectory(clipsDir))
		{
			return;
		}
		List<File> clips;
		try (Stream<Path> walk = Files.walk(clipsDir, 2))
		{
			clips = walk.map(Path::toFile)
				.filter(File::isFile)
				.filter(f -> f.getName().endsWith(".avi") || f.getName().endsWith(".mp4"))
				.sorted(Comparator.comparingLong(File::lastModified))
				.collect(Collectors.toCollection(ArrayList::new));
		}
		long total = 0;
		for (File f : clips)
		{
			total += f.length();
		}
		for (File f : clips)
		{
			if (total <= capBytes)
			{
				break;
			}
			if (keep != null && f.equals(keep))
			{
				continue;
			}
			long size = f.length();
			if (f.delete())
			{
				total -= size;
			}
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
