package com.fireside.raid;

import java.util.List;
import lombok.NonNull;
import lombok.Value;

/**
 * What {@link TfaRecordsReader} reads from The Fractured Archive's records
 * table ({@code TfaStats}). {@link ClientTfaRecordsView} is the real one;
 * tests use a fake. Client thread only.
 */
public interface TfaRecordsView
{
	/**
	 * One section of {@code ALL_STATS_TABLES} (one per side tab), as plain
	 * text with tags removed. Cells are one flat list, {@code headers.size()}
	 * per row, in row order.
	 */
	@Value
	class Section
	{
		/** "Overall" or the room's name. */
		@NonNull
		String title;
		/** Column headers, e.g. "Personal", "Global". */
		@NonNull
		List<String> headers;
		/** Row labels, e.g. "Attempts", "3 Player". */
		@NonNull
		List<String> labels;
		@NonNull
		List<String> cells;
	}

	/** Whether a tab is still loading its data (the table's loading spinner is shown). */
	boolean isLoading();

	/** All sections of the table, hidden ones included; empty if the table is not loaded. */
	List<Section> sections();

	/** The logged-in account's hash. */
	long accountHash();
}
