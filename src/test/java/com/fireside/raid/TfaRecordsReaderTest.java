package com.fireside.raid;

import com.fireside.event.EventPayload;
import com.fireside.event.PbRecords;
import com.fireside.event.PbRecords.Record;
import com.fireside.raid.TfaRecordsView.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class TfaRecordsReaderTest
{
	private static final List<String> HEADERS = Arrays.asList("Personal", "Global", "Global Today", "World Today");
	private static final long ME = 1234567890123456789L;
	private static final long ALT = 42L;

	private final FakeView view = new FakeView();
	private final List<EventPayload> sent = new ArrayList<>();
	private boolean kcEnabled = true;
	private boolean inSession = true;
	private TfaRecordsReader reader;

	@Before
	public void setUp()
	{
		reader = new TfaRecordsReader(view, payload ->
		{
			if (!inSession)
			{
				return false;
			}
			sent.add(payload);
			return true;
		}, () -> kcEnabled);
	}

	/** A section as 0B saw it: counts first, then "Completion Time" (empty cells), then "N Player" rows. */
	private static Section section(String title, String... playerRows)
	{
		List<String> labels = new ArrayList<>(Arrays.asList("Attempts", "Completions", "Completion Time"));
		List<String> cells = new ArrayList<>(Arrays.asList(
			"140", "9001", "1200", "30",
			"138", "8000", "1100", "25",
			"", "", "", ""));
		for (int i = 0; i < playerRows.length; i++)
		{
			labels.add((i + 1) + " Player");
			cells.addAll(Arrays.asList(playerRows[i], "10:00.00", "11:00.00", "12:00.00"));
		}
		return new Section(title, HEADERS, labels, cells);
	}

	private static Record record(String scope, int teamSize, double seconds)
	{
		return new Record(scope, teamSize, seconds);
	}

	// Parser

	@Test
	public void readsThePersonalColumnOfPlayerRows()
	{
		assertEquals(Arrays.asList(record("overall", 1, 1905.2), record("overall", 3, 1088.4),
				record("Ocellus", 1, 105.0)),
			TfaRecordsReader.parse(Arrays.asList(
				section("Overall", "31:45.20", "-", "18:08.40"),
				section("Ocellus", "1:45.00"))));
	}

	@Test
	public void skipsRowsWithoutAPbOrWithAnUnknownFormat()
	{
		assertEquals(Collections.singletonList(record("overall", 4, 1150.8)),
			TfaRecordsReader.parse(Collections.singletonList(
				section("Overall", "-", "", "about 18 minutes", "19:10.80", "0:00.00"))));
	}

	@Test
	public void skipsRowsThatAreNotTeamSizes()
	{
		Section section = new Section("Overall", HEADERS,
			Arrays.asList("Completions", "Fastest Player", "9 Players", "0 Player"),
			Arrays.asList(
				"5:00.00", "", "", "",
				"6:00.00", "", "", "",
				"7:00.00", "", "", "",
				"8:00.00", "", "", ""));
		assertEquals(Collections.singletonList(record("overall", 9, 420.0)),
			TfaRecordsReader.parse(Collections.singletonList(section)));
	}

	@Test
	public void findsThePersonalColumnByName()
	{
		Section section = new Section("Moia", Arrays.asList("Global", "Personal"),
			Collections.singletonList("2 Player"), Arrays.asList("1:00.00", "2:30.60"));
		assertEquals(Collections.singletonList(record("Moia", 2, 150.6)),
			TfaRecordsReader.parse(Collections.singletonList(section)));
	}

	@Test
	public void skipsSectionsThatAreNotLoadedOrUnknown()
	{
		List<Section> sections = Arrays.asList(
			// Not loaded yet: an empty frame.
			new Section("Xarth", HEADERS, Collections.emptyList(), Collections.emptyList()),
			// Cells missing.
			new Section("Bork", HEADERS, Collections.singletonList("1 Player"), Arrays.asList("1:00.00", "-")),
			// No Personal column.
			new Section("Ezekiel", Arrays.asList("Global", "World Today"), Collections.singletonList("1 Player"),
				Arrays.asList("1:00.00", "2:00.00")),
			// No title.
			new Section("", HEADERS, Collections.singletonList("1 Player"), Arrays.asList("1:00.00", "", "", "")),
			section("Dreadnoth", "2:10.20"));
		assertEquals(Collections.singletonList(record("Dreadnoth", 1, 130.2)), TfaRecordsReader.parse(sections));
	}

	@Test
	public void keepsUnknownRoomNamesAsShownAndOverallInLowerCase()
	{
		assertEquals(Arrays.asList(record("overall", 1, 60.0), record("The Dagon'hai", 1, 61.0),
				record("Some New Room", 1, 62.0)),
			TfaRecordsReader.parse(Arrays.asList(section("OVERALL", "1:00.00"),
				section("The Dagon'hai", "1:01.00"), section("Some New Room", "1:02.00"))));
	}

	@Test
	public void keepsEachScopeAndTeamSizeOnceWithTheLowestTime()
	{
		assertEquals(Collections.singletonList(record("overall", 1, 60.0)),
			TfaRecordsReader.parse(Arrays.asList(section("Overall", "1:10.00"), section("Overall", "1:00.00"))));
	}

	// Reading and sending

	@Test
	public void sendsTheRecordsWhenTheTableIsOpen()
	{
		view.sections = Collections.singletonList(section("Overall", "-", "-", "18:08.40"));
		reader.onTick();
		assertTrue("Nothing is read before the table is open", sent.isEmpty());
		reader.onTableOpened();
		reader.onTick();
		assertEquals(Collections.singletonList(new PbRecords("Fractured Archive",
			Collections.singletonList(record("overall", 3, 1088.4)))), sent);
	}

	@Test
	public void waitsWhileATabIsLoadingAndStopsWhenClosed()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		view.loading = true;
		reader.onTick();
		assertTrue(sent.isEmpty());
		reader.onTableClosed();
		view.loading = false;
		reader.onTick();
		assertTrue(sent.isEmpty());
	}

	@Test
	public void sendsNothingWithoutRecords()
	{
		view.sections = Collections.singletonList(section("Overall", "-", "-"));
		reader.onTableOpened();
		reader.onTick();
		assertTrue(sent.isEmpty());
	}

	@Test
	public void sendsTheSameRecordsOnce()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		reader.onTick();
		reader.onTick();
		reader.onTableClosed();
		reader.onTableOpened();
		reader.onTick();
		assertEquals(1, sent.size());
	}

	@Test
	public void sendsAgainOnlyForANewTabOrAFasterTime()
	{
		reader.onTableOpened();
		view.sections = Arrays.asList(section("Overall", "18:08.40"), section("Ocellus", "1:45.00"));
		reader.onTick();
		// Reopened: only Overall is built again. Nothing new.
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTick();
		assertEquals(1, sent.size());
		// A tab not seen before.
		view.sections = Arrays.asList(section("Overall", "18:08.40"), section("Moia", "2:00.00"));
		reader.onTick();
		assertEquals(2, sent.size());
		assertEquals(Arrays.asList(record("overall", 1, 1088.4), record("Moia", 1, 120.0)), records(1));
		// A faster time.
		view.sections = Collections.singletonList(section("Overall", "18:00.00"));
		reader.onTick();
		assertEquals(3, sent.size());
		assertEquals(Collections.singletonList(record("overall", 1, 1080.0)), records(2));
		// A slower value (should not happen) is nothing new.
		view.sections = Collections.singletonList(section("Overall", "19:00.00"));
		reader.onTick();
		assertEquals(3, sent.size());
	}

	@Test
	public void remembersWhatWasSentPerAccount()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		reader.onTick();
		view.account = ALT;
		reader.onTick();
		view.account = ME;
		reader.onTick();
		assertEquals(2, sent.size());
	}

	@Test
	public void sendsNothingWithKillCountsOffAndSendsLaterWhenOn()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		kcEnabled = false;
		reader.onTick();
		assertTrue(sent.isEmpty());
		kcEnabled = true;
		reader.onTick();
		assertEquals(1, sent.size());
	}

	@Test
	public void recordsNotAcceptedOutsideASessionAreSentLater()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		inSession = false;
		reader.onTick();
		assertTrue(sent.isEmpty());
		inSession = true;
		reader.onTick();
		assertEquals(1, sent.size());
	}

	@Test
	public void resetClosesTheTableButKeepsWhatWasSent()
	{
		view.sections = Collections.singletonList(section("Overall", "18:08.40"));
		reader.onTableOpened();
		reader.onTick();
		reader.reset();
		reader.onTick();
		reader.onTableOpened();
		reader.onTick();
		assertEquals(1, sent.size());
	}

	private List<Record> records(int index)
	{
		return ((PbRecords) sent.get(index)).getRecords();
	}

	private static final class FakeView implements TfaRecordsView
	{
		List<Section> sections = Collections.emptyList();
		boolean loading;
		long account = ME;

		@Override
		public boolean isLoading()
		{
			return loading;
		}

		@Override
		public List<Section> sections()
		{
			return sections;
		}

		@Override
		public long accountHash()
		{
			return account;
		}
	}
}
