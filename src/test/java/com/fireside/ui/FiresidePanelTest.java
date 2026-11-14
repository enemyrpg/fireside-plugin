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
