package com.fireside.raid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * {@link TfaRecordsView} backed by the {@code TfaStats} widgets. Reads only
 * {@code ALL_STATS_TABLES} and the loading spinner, never the party panels
 * ({@code PARTY_*}, other players' names). Client thread only.
 * <p>
 * Layout from #86 step 0B ({@code docs/plan/08-design.md}), checked in game
 * for PR 3: one dynamic child per side tab; in it, child 3 holds the title,
 * child 5's child 1 the row labels and child 5's child 2 all cells (a flat
 * list, one row after another). Labels and cells are layers with a text
 * child. The column headers are the children of the layer under child 4
 * that has one child per column. Anything else gives a section the parser
 * skips.
 */
@Singleton
public class ClientTfaRecordsView implements TfaRecordsView
{
	private static final int TITLE = 3;
	private static final int HEADERS = 4;
	private static final int BODY = 5;
	private static final int BODY_LABELS = 1;
	private static final int BODY_CELLS = 2;
	/** Must match the parser's column header. */
	private static final String PERSONAL = TfaRecordsReader.PERSONAL_HEADER;

	private final Client client;

	@Inject
	ClientTfaRecordsView(Client client)
	{
		this.client = client;
	}

	@Override
	public boolean isLoading()
	{
		Widget spinner = client.getWidget(InterfaceID.TfaStats.ALL_STATS_LOADING_SPINNER);
		return spinner != null && !spinner.isHidden();
	}

	@Override
	public List<Section> sections()
	{
		Widget tables = client.getWidget(InterfaceID.TfaStats.ALL_STATS_TABLES);
		if (tables == null)
		{
			return Collections.emptyList();
		}
		List<Section> sections = new ArrayList<>();
		for (Widget section : children(tables))
		{
			if (section != null)
			{
				sections.add(sectionOf(section));
			}
		}
		return sections;
	}

	@Override
	public long accountHash()
	{
		return client.getAccountHash();
	}

	private static Section sectionOf(Widget section)
	{
		Widget body = child(section, BODY);
		// Keep empty labels and cells: the position gives the row and column.
		List<String> labels = firstTexts(child(body, BODY_LABELS));
		List<String> cells = firstTexts(child(body, BODY_CELLS));
		int columns = labels.isEmpty() || cells.size() % labels.size() != 0 ? 0 : cells.size() / labels.size();
		return new Section(firstText(child(section, TITLE)), headers(child(section, HEADERS), columns), labels,
			cells);
	}

	/**
	 * The column headers: the texts of the first layer under {@code headers}
	 * that has one child per column and a "Personal" header. Empty if none.
	 */
	private static List<String> headers(@Nullable Widget headers, int columns)
	{
		if (columns == 0)
		{
			return Collections.emptyList();
		}
		for (Widget row : children(headers))
		{
			if (children(row).length != columns)
			{
				continue;
			}
			List<String> texts = firstTexts(row);
			for (String text : texts)
			{
				if (text.equalsIgnoreCase(PERSONAL))
				{
					return texts;
				}
			}
		}
		return Collections.emptyList();
	}

	private static Widget[] children(@Nullable Widget widget)
	{
		Widget[] children = widget != null ? widget.getDynamicChildren() : null;
		return children != null ? children : new Widget[0];
	}

	@Nullable
	private static Widget child(@Nullable Widget widget, int index)
	{
		Widget[] children = children(widget);
		return index < children.length ? children[index] : null;
	}

	/** The widget's own text, or its first child's text; "" if none. Tags removed. */
	private static String firstText(@Nullable Widget widget)
	{
		String own = textOf(widget);
		if (!own.isEmpty())
		{
			return own;
		}
		for (Widget child : children(widget))
		{
			String text = textOf(child);
			if (!text.isEmpty())
			{
				return text;
			}
		}
		return "";
	}

	/** {@link #firstText} of each child, in order, empty ones included. */
	private static List<String> firstTexts(@Nullable Widget widget)
	{
		List<String> texts = new ArrayList<>();
		for (Widget child : children(widget))
		{
			texts.add(firstText(child));
		}
		return texts;
	}

	private static String textOf(@Nullable Widget widget)
	{
		String text = widget != null ? widget.getText() : null;
		return text != null ? Text.removeTags(text).trim() : "";
	}
}
