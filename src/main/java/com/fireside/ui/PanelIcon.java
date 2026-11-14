package com.fireside.ui;

import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * The side panel icon: the Fireside logo, rendered to a 16x16 PNG from
 * {@code assets/logo/fireside.svg} by {@code scripts/render-icons.sh}. Our own
 * artwork, no Jagex graphics.
 */
public final class PanelIcon
{
	static final int SIZE = 16;
	static final String RESOURCE = "/com/fireside/panel-icon.png";

	private PanelIcon()
	{
	}

	public static BufferedImage create()
	{
		return ImageUtil.loadImageResource(PanelIcon.class, RESOURCE);
	}
}
