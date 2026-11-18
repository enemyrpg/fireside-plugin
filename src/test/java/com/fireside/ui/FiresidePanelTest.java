package com.fireside.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.Test;

public class FiresidePanelTest
{
	private static final String FOLDER = "/home/someone/.runelite/plugin-data/fireside/queue";

	@Test
	public void showsTheQueueFolderInsteadOfAnOpenFolderButton() throws Exception
	{
		List<String> buttons = new ArrayList<>();
		JTextComponent[] path = new JTextComponent[1];
		SwingUtilities.invokeAndWait(() ->
		{
			FiresidePanel panel = new FiresidePanel(() -> {}, () -> {}, () -> {}, FOLDER);
			walk(panel, c ->
			{
				if (c instanceof AbstractButton)
				{
					buttons.add(((AbstractButton) c).getText());
				}
				if (c instanceof JTextComponent && FOLDER.equals(((JTextComponent) c).getText()))
				{
					path[0] = (JTextComponent) c;
				}
			});
		});
		assertTrue(buttons.toString(), buttons.contains("Send now"));
		assertTrue(buttons.toString(), buttons.stream().noneMatch(b -> b.contains("folder")));
		assertNotNull("queue folder path is shown", path[0]);
		// Selectable so it can be copied, but not editable.
		assertTrue(path[0].isFocusable());
		assertNotNull(path[0].getHighlighter());
		assertEquals(false, path[0].isEditable());
	}

	@Test
	public void clockTickOnlyChangesTheStatusTextAndLastContact() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			FiresidePanel panel = new FiresidePanel(() -> {}, () -> {}, () -> {}, FOLDER);
			StatusView first = StatusView.forTest(StatusView.Level.OK, "Connected", "Last contact: 1 s ago.",
				java.util.Collections.singletonList("A warning"));
			panel.render(first, "http://127.0.0.1:3000", true, null, "1 s ago", false, true);
			List<String> labels = new ArrayList<>();
			walk(panel, c ->
			{
				if (c instanceof javax.swing.JLabel)
				{
					labels.add(((javax.swing.JLabel) c).getText());
				}
			});
			assertTrue(labels.toString(), labels.contains("Last contact"));
			assertTrue(labels.toString(), labels.contains("1 s ago"));
			assertTrue(labels.toString(), !labels.contains("Last test"));

			List<Component> before = new ArrayList<>();
			walk(panel, before::add);
			StatusView later = StatusView.forTest(StatusView.Level.WARNING, "Can't reach the server – retrying",
				"Down.", java.util.Collections.singletonList("A warning"));
			panel.updateTimeText(later, "2 min ago");
			List<Component> after = new ArrayList<>();
			walk(panel, after::add);
			// Same components: nothing was rebuilt.
			assertEquals(before, after);
			List<String> texts = new ArrayList<>();
			walk(panel, c ->
			{
				if (c instanceof javax.swing.JLabel)
				{
					texts.add(((javax.swing.JLabel) c).getText());
				}
				if (c instanceof JTextComponent)
				{
					texts.add(((JTextComponent) c).getText());
				}
			});
			assertTrue(texts.toString(), texts.contains("Can't reach the server – retrying"));
			assertTrue(texts.toString(), texts.contains("Down."));
			assertTrue(texts.toString(), texts.contains("2 min ago"));
		});
	}

	private interface Visitor
	{
		void visit(Component c);
	}

	private static void walk(Component c, Visitor visitor)
	{
		visitor.visit(c);
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				walk(child, visitor);
			}
		}
	}
}
