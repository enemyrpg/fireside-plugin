package com.fireside.raid;

import com.fireside.event.EventPayload;
import com.fireside.event.PbRecords;
import com.fireside.loot.KillCountParser;
import com.fireside.raid.TfaRecordsView.Section;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads The Fractured Archive's records table ({@code TfaStats}) into
 * {@code pb.records} events (#86). Client thread only.
 * <p>
 * Only reads what the player opened: never opens the table or clicks tabs.
 * While the table is open it is read once per game tick, unless a tab is
 * still loading. Only {@code ALL_STATS_TABLES} is read, never the party
 * panels (other players' names). The game only builds a tab's section when
 * the tab is opened, so the records of one opening are collected over many
 * reads. At most one event is sent per opening (#16): on the tick after the
 * table closes (a close and reopen in the same tick is one opening), or on
 * {@link #flush()} (logout, hop, client or plugin shutdown). It is sent only
 * if the opening has a record not yet sent for the account, or a faster one,
 * and holds every record of the opening. Covered by the "Kill counts" setting.
 */
@Slf4j
public class TfaRecordsReader
{
	/** The raid's name as its kill count message gives it; the server normalizes it. */
	static final String BOSS = Raids.Group.FRACTURED_ARCHIVE.kcPrefix();

	private static final String OVERALL_TITLE = "Overall";
	static final String PERSONAL_HEADER = "Personal";
	private static final Pattern TEAM_SIZE = Pattern.compile("(\\d{1,3}) Players?", Pattern.CASE_INSENSITIVE);

	private final TfaRecordsView view;
	/** Sends an event in the current session; false if there is none. */
	private final Predicate<EventPayload> sink;
	private final BooleanSupplier kcEnabled;

	private boolean open;
	/** The table closed; the opening is sent on the next tick unless it reopens first. */
	private boolean closing;
	/** The records of the current opening, lowest time per (scope, team size). */
	private final Map<Key, PbRecords.Record> pending = new LinkedHashMap<>();
	/** The account the pending records were read for; null if none. */
	private Long pendingAccount;
	/** Per account: the fastest time sent per (scope, team size). In memory only. */
	private final Map<Long, Map<Key, Double>> sent = new HashMap<>();

	@Value
	private static class Key
	{
		String scope;
		int teamSize;
	}

	public TfaRecordsReader(TfaRecordsView view, Predicate<EventPayload> sink, BooleanSupplier kcEnabled)
	{
		this.view = view;
		this.sink = sink;
		this.kcEnabled = kcEnabled;
	}

	/** The records table was loaded (opened). */
	public void onTableOpened()
	{
		log.debug("Records table opened");
		open = true;
		closing = false;
	}

	/** The records table was closed. The opening is sent on the next tick. */
	public void onTableClosed()
	{
		log.debug("Records table closed, {} records collected", pending.size());
		open = false;
		closing = true;
	}

	/** Forgets the current opening (logout, hop). Call {@link #flush()} first to send it. What was sent is kept. */
	public void reset()
	{
		open = false;
		closing = false;
		pending.clear();
		pendingAccount = null;
	}

	/** Every game tick: sends a closed opening, or collects the records of the open table. */
	public void onTick()
	{
		if (closing)
		{
			closing = false;
			flush();
		}
		if (!open || !kcEnabled.getAsBoolean() || view.isLoading())
		{
			return;
		}
		List<PbRecords.Record> records = parse(view.sections());
		if (records.isEmpty())
		{
			return;
		}
		long account = view.accountHash();
		if (pendingAccount != null && pendingAccount != account)
		{
			// Should not happen while the table is open.
			flush();
		}
		pendingAccount = account;
		for (PbRecords.Record record : records)
		{
			pending.merge(keyOf(record), record, (a, b) -> b.getSeconds() < a.getSeconds() ? b : a);
		}
	}

	/**
	 * Sends the records collected so far, as one event, if any is new or
	 * faster for the account, and starts a new collection. Without a session
	 * or with "Kill counts" off they are dropped; the next opening sends them.
	 */
	public void flush()
	{
		if (pending.isEmpty())
		{
			return;
		}
		List<PbRecords.Record> records = new ArrayList<>(pending.values());
		long account = pendingAccount;
		pending.clear();
		pendingAccount = null;
		if (!kcEnabled.getAsBoolean())
		{
			return;
		}
		Map<Key, Double> known = sent.computeIfAbsent(account, a -> new HashMap<>());
		boolean anyNew = false;
		for (PbRecords.Record record : records)
		{
			Double before = known.get(keyOf(record));
			if (before == null || record.getSeconds() < before)
			{
				anyNew = true;
				break;
			}
		}
		if (!anyNew)
		{
			log.debug("Records table: nothing new in {} records", records.size());
			return;
		}
		if (!sink.test(new PbRecords(BOSS, records)))
		{
			log.debug("Records table: no session, {} records dropped", records.size());
			return;
		}
		log.debug("Records table: sent {} records", records.size());
		for (PbRecords.Record record : records)
		{
			known.merge(keyOf(record), record.getSeconds(), Math::min);
		}
	}

	/**
	 * The personal bests in the sections: the "Personal" column of every
	 * "N Player" row. Rows without a PB ("-") or with an unknown time format,
	 * and sections that are not loaded or not understood, are skipped. Each
	 * (scope, team size) pair once, with the lowest time. Pure.
	 */
	static List<PbRecords.Record> parse(List<Section> sections)
	{
		Map<Key, PbRecords.Record> records = new LinkedHashMap<>();
		for (Section section : sections)
		{
			String title = section.getTitle().trim();
			int columns = section.getHeaders().size();
			int personal = indexOf(section.getHeaders(), PERSONAL_HEADER);
			if (title.isEmpty() || personal < 0 || section.getLabels().isEmpty()
				|| section.getCells().size() != section.getLabels().size() * columns)
			{
				continue;
			}
			String scope = title.equalsIgnoreCase(OVERALL_TITLE) ? PbRecords.OVERALL : title;
			for (int row = 0; row < section.getLabels().size(); row++)
			{
				Integer teamSize = teamSize(section.getLabels().get(row));
				if (teamSize == null)
				{
					continue;
				}
				Double seconds = KillCountParser.toSeconds(section.getCells().get(row * columns + personal).trim());
				if (seconds == null)
				{
					continue;
				}
				PbRecords.Record record = new PbRecords.Record(scope, teamSize, seconds);
				records.merge(keyOf(record), record, (a, b) -> b.getSeconds() < a.getSeconds() ? b : a);
			}
		}
		return new ArrayList<>(records.values());
	}

	private static Integer teamSize(String label)
	{
		Matcher matcher = TEAM_SIZE.matcher(label.trim());
		if (!matcher.matches())
		{
			return null;
		}
		int size = Integer.parseInt(matcher.group(1));
		return size >= 1 && size <= RaidMessageParser.MAX_TEAM_SIZE ? size : null;
	}

	private static int indexOf(List<String> headers, String header)
	{
		for (int i = 0; i < headers.size(); i++)
		{
			if (headers.get(i).trim().equalsIgnoreCase(header))
			{
				return i;
			}
		}
		return -1;
	}

	private static Key keyOf(PbRecords.Record record)
	{
		return new Key(record.getScope(), record.getTeamSize());
	}
}
