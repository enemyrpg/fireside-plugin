package com.fireside.ui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.View;

/**
 * Read-only text that wraps at the width it is given (breaking long words if
 * needed). Unlike an HTML {@code JLabel} with a fixed CSS width, it can never
 * be wider than its parent.
 */
final class WrappedText extends JTextArea
{
	/** Width used before the first layout, so the first height is about right. */
	private final int fallbackWidth;

	WrappedText(int fallbackWidth)
	{
		this.fallbackWidth = fallbackWidth;
		setLineWrap(true);
		setWrapStyleWord(true);
		setEditable(false);
		setFocusable(false);
		setOpaque(false);
		setHighlighter(null);
		setBorder(null);
		setMargin(new Insets(0, 0, 0, 0));
		// Same font as labels (RuneLite sets it in the look and feel).
		setFont(UIManager.getFont("Label.font"));
	}

	/** Lets the user select and copy the text (still read-only). */
	WrappedText selectable()
	{
		setFocusable(true);
		setHighlighter(new DefaultHighlighter());
		return this;
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width = availableWidth();
		View root = getUI().getRootView(this);
		root.setSize(width, Float.MAX_VALUE);
		int height = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS));
		Insets insets = getInsets();
		return new Dimension(width, height + insets.top + insets.bottom);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, getPreferredSize().height);
	}

	private int availableWidth()
	{
		Insets own = getInsets();
		Container parent = getParent();
		if (parent != null && parent.getWidth() > 0)
		{
			Insets p = parent.getInsets();
			return Math.max(1, parent.getWidth() - p.left - p.right - own.left - own.right);
		}
		return fallbackWidth;
	}
}
