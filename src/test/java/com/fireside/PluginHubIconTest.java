package com.fireside;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * The Plugin Hub icon ({@code icon.png} in the repo root) must be a real PNG of
 * at most 48x72 px and 256 KiB. Rendered by {@code scripts/render-icons.sh}.
 */
public class PluginHubIconTest
{
	private static final File ICON = new File("icon.png");

	@Test
	public void iconIsA48PixelPng() throws IOException
	{
		assertTrue("icon.png is missing from the repo root", ICON.isFile());
		assertTrue("icon.png is above 256 KiB", ICON.length() <= 256 * 1024);
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
		assertTrue("icon.png is not a PNG",
			Arrays.equals(signature, Arrays.copyOf(Files.readAllBytes(ICON.toPath()), 8)));
		BufferedImage image = ImageIO.read(ICON);
		assertNotNull("icon.png is not a readable image", image);
		assertEquals(48, image.getWidth());
		assertEquals(48, image.getHeight());
	}
}
