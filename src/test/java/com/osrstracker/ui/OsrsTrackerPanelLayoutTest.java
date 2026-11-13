package com.osrstracker.ui;

import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstracker.net.VerifyResponse;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Test;

/**
 * Lays the panel out at RuneLite's sidebar width (off-screen) and checks that
 * nothing ends up outside the visible area. Also writes a PNG to
 * {@code build/panel-preview.png} for a quick look.
 */
public class OsrsTrackerPanelLayoutTest
{
	private static final int SIDEBAR_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH;

	@Test
	public void longTextStaysInsideThePanel() throws Exception
	{
		List<String> problems = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			// RuneLite's look and feel and fonts, as in the client.
			RuneLiteLAF.setup();
			OsrsTrackerPanel panel = new OsrsTrackerPanel(() -> {}, () -> {});
			VerifyResponse response = VerifyResponse.parse(new Gson(),
				"{\"ok\":true,\"keyName\":\"My very long development client key name\",\"serverVersion\":\"0.1.0\","
					+ "\"contractVersion\":\"1.12.0\",\"serverTime\":\"2026-11-13T18:00:00.000Z\"}");
			StatusView view = StatusView.compute("http://192.168.100.200:3100/some/long/prefix", "k", false, null);
			List<String> warnings = new ArrayList<>(view.getWarnings());
			warnings.add("Your computer's clock is 2 min 0 s ahead of the server's. Turn on automatic time sync "
				+ "(NTP) on both machines, or sessions may be closed too early.");
			warnings.add("The server is too old (contract 1.12.0, this plugin needs 1.13.0). Update the server. "
				+ "Events are kept and not sent until then.");
			StatusView connected = StatusView.forTest(StatusView.Level.WARNING, "Connected with warnings",
				"Connected as key \"My very long development client key name\". Server 0.1.0, contract 1.12.0.",
				warnings);
			panel.render(connected, "http://192.168.100.200:3100/some/long/prefix", true, response,
				Instant.parse("2026-11-13T18:00:00Z"), false, true);

			Container root = panel.getWrappedPanel();
			root.setSize(SIDEBAR_WIDTH, 1200);
			layoutAll(root);

			checkInside(panel, problems);

			BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			root.paint(g);
			g.dispose();
			try
			{
				new File("build").mkdirs();
				ImageIO.write(image, "png", new File("build/panel-preview.png"));
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	private static void layoutAll(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutAll(child);
			}
		}
		// A second pass so wrapped text gets its height for the final width.
		c.validate();
	}

	/** Every visible child must fit inside its parent's inner area (where it would be clipped). */
	private static void checkInside(Container parent, List<String> problems)
	{
		Insets in = parent.getInsets();
		int right = parent.getWidth() - in.right;
		for (Component c : parent.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			if (c.getX() < in.left || c.getX() + c.getWidth() > right)
			{
				problems.add(describe(c) + " spans x " + c.getX() + ".." + (c.getX() + c.getWidth())
					+ " but " + parent.getClass().getSimpleName() + " shows " + in.left + ".." + right);
			}
			// Text that wraps (HTML labels, text areas) must get all the width it lays itself out for;
			// otherwise its right edge is cut off. Single-line labels are cut with "…" instead, which is fine.
			boolean wraps = c instanceof JTextArea
				|| c instanceof JLabel && String.valueOf(((JLabel) c).getText()).startsWith("<html>");
			if (wraps && c.getPreferredSize().width > c.getWidth())
			{
				problems.add(describe(c) + " needs " + c.getPreferredSize().width + " px but has " + c.getWidth());
			}
			if (c instanceof Container && !(c instanceof JScrollBar))
			{
				checkInside((Container) c, problems);
			}
		}
	}

	private static String describe(Component c)
	{
		if (c instanceof JLabel)
		{
			return "JLabel(" + ((JLabel) c).getText() + ")";
		}
		if (c instanceof JTextArea)
		{
			return "Text(" + ((JTextArea) c).getText() + ")";
		}
		return c.getClass().getSimpleName();
	}
}
