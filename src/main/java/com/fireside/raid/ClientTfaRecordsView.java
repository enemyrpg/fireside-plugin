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
 * Layout from #86 step 0B ({@code docs/plan/08-design.md}): one dynamic child
 * per side tab; in it, child 3 is the title, child 4 the column headers,
 * child 5's child 1 the row labels and child 5's child 2 all cells, one
 * layer with a text child each. Anything else is returned as an empty
 * section, which the parser skips.
 */
@Singleton
public class ClientTfaRecordsView implements TfaRecordsView
{
	private static final int TITLE = 3;
	private static final int HEADERS = 4;
	private static final int BODY = 5;
	private static final int BODY_LABELS = 1;
	private static final int BODY_CELLS = 2;

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
		List<String> cells = new ArrayList<>();
		for (Widget cell : children(child(body, BODY_CELLS)))
		{
			// Keep empty cells: the position gives the row and column.
			cells.add(firstText(cell));
		}
		return new Section(firstText(child(section, TITLE)), texts(child(section, HEADERS)),
			texts(child(body, BODY_LABELS)), cells);
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

	/** The widget's own text if it has one, otherwise its children's non-empty texts, in order. */
	private static List<String> texts(@Nullable Widget widget)
	{
		String own = textOf(widget);
		if (!own.isEmpty())
		{
			return Collections.singletonList(own);
		}
		List<String> texts = new ArrayList<>();
		for (Widget child : children(widget))
		{
			String text = textOf(child);
			if (!text.isEmpty())
			{
				texts.add(text);
			}
		}
		return texts;
	}

	private static String textOf(@Nullable Widget widget)
	{
		String text = widget != null ? widget.getText() : null;
		return text != null ? Text.removeTags(text).trim() : "";
	}
}
