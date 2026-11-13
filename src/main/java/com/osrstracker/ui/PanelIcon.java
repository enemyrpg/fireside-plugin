package com.osrstracker.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The side panel icon: a small rising bar chart, drawn in code. Our own
 * artwork, no image file and no Jagex graphics.
 */
public final class PanelIcon
{
	static final int SIZE = 16;

	private static final Color BAR = new Color(0x4F, 0xB3, 0xBF);
	private static final Color LINE = new Color(0xF2, 0xB1, 0x34);

	private PanelIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			// Three bars of increasing height.
			g.setColor(BAR);
			g.fillRect(1, 10, 4, 5);
			g.fillRect(6, 7, 4, 8);
			g.fillRect(11, 3, 4, 12);

			// A trend line across the bars.
			g.setColor(LINE);
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawPolyline(new int[]{1, 7, 10, 15}, new int[]{8, 5, 6, 1}, 4);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
