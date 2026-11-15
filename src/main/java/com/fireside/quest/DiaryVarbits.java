package com.fireside.quest;

import com.fireside.event.DiariesSnapshot.Tier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.runelite.api.gameval.VarbitID;

/**
 * The varbit behind every achievement diary region and tier, in the order
 * they are sent.
 * <p>
 * "Completed" means every task of the tier is done, whether or not the reward
 * has been claimed (the claim is a separate {@code *_REWARD} varbit, which is
 * not read). The {@code *_DIARY_*_COMPLETE} varbits are 1 then. Karamja's
 * easy, medium and hard tiers are older and use {@code ATJUN_*_DONE}, where 1
 * means started and 2 means every task is done.
 */
public final class DiaryVarbits
{
	@Value
	public static class Entry
	{
		String region;
		Tier tier;
		int varbit;
		/** The tier is completed when the varbit is at least this. */
		int completedFrom;

		public boolean completed(int value)
		{
			return value >= completedFrom;
		}
	}

	public static final List<Entry> ALL;

	static
	{
		List<Entry> all = new ArrayList<>();
		region(all, "ARDOUGNE", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE);
		region(all, "DESERT", VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE);
		region(all, "FALADOR", VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE);
		region(all, "FREMENNIK", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE);
		region(all, "KANDARIN", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE);
		all.add(new Entry("KARAMJA", Tier.EASY, VarbitID.ATJUN_EASY_DONE, 2));
		all.add(new Entry("KARAMJA", Tier.MEDIUM, VarbitID.ATJUN_MED_DONE, 2));
		all.add(new Entry("KARAMJA", Tier.HARD, VarbitID.ATJUN_HARD_DONE, 2));
		all.add(new Entry("KARAMJA", Tier.ELITE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1));
		region(all, "KOUREND", VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE);
		region(all, "LUMBRIDGE", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE);
		region(all, "MORYTANIA", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE);
		region(all, "VARROCK", VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE);
		region(all, "WESTERN", VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE);
		region(all, "WILDERNESS", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE);
		ALL = Collections.unmodifiableList(all);
	}

	private DiaryVarbits()
	{
	}

	private static void region(List<Entry> all, String region, int easy, int medium, int hard, int elite)
	{
		all.add(new Entry(region, Tier.EASY, easy, 1));
		all.add(new Entry(region, Tier.MEDIUM, medium, 1));
		all.add(new Entry(region, Tier.HARD, hard, 1));
		all.add(new Entry(region, Tier.ELITE, elite, 1));
	}
}
