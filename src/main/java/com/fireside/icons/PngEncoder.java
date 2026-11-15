package com.fireside.icons;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.annotation.Nullable;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Encodes an item icon as PNG, in memory only.
 * <p>
 * Not {@code ImageIO.write(image, "png", stream)}: for a stream, ImageIO buffers
 * through a temporary file by default ({@code FileCacheImageOutputStream}), and
 * turning that off ({@code ImageIO.setUseCache}) would change it for the whole
 * client. A {@link MemoryCacheImageOutputStream} never touches the disk.
 */
@Slf4j
public final class PngEncoder
{
	/** The server's limits (03, {@code checkPng}). */
	public static final int MAX_SIDE = 64;
	public static final int MAX_BYTES = 16_384;

	private PngEncoder()
	{
	}

	/**
	 * The PNG bytes, or {@code null} if the image is not worth uploading: fully
	 * transparent (the sprite did not load), larger than {@value #MAX_SIDE} px,
	 * over {@value #MAX_BYTES} bytes, or if encoding failed.
	 */
	@Nullable
	public static byte[] encode(BufferedImage image)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || isBlank(image))
		{
			return null;
		}
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
		if (!writers.hasNext())
		{
			return null;
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
		{
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), null);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not encode an item icon: {}", e.getClass().getSimpleName());
			return null;
		}
		finally
		{
			writer.dispose();
		}
		byte[] png = bytes.toByteArray();
		return png.length > 0 && png.length <= MAX_BYTES ? png : null;
	}

	/** True when every pixel is fully transparent. */
	static boolean isBlank(BufferedImage image)
	{
		if (!image.getColorModel().hasAlpha())
		{
			return false;
		}
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}
}
