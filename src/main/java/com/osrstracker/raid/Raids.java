package com.osrstracker.raid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * Raids, their Loot Tracker chest names and their purples. The purple item IDs
 * must match {@code RAIDS} in {@code osrs-tracker/shared/src/game/raids.ts}: the
 * server pairs your own purples with the chest's items by ID.
 */
public final class Raids
{
	public enum Group
	{
		// Loot Tracker's chest names (LootTrackerPlugin), and the start of every KC name of the raid.
		CHAMBERS_OF_XERIC("Chambers of Xeric", "Chambers of Xeric"),
		THEATRE_OF_BLOOD("Theatre of Blood", "Theatre of Blood"),
		TOMBS_OF_AMASCUT("Tombs of Amascut", "Tombs of Amascut"),
		// The KC message says "Fractured Archive" (no "The"), and so does the Loot Tracker.
		FRACTURED_ARCHIVE("Fractured Archive", "Fractured Archive");

		private final String chestName;
		private final String kcPrefix;

		Group(String chestName, String kcPrefix)
		{
			this.chestName = chestName;
			this.kcPrefix = kcPrefix;
		}
	}

	@Value
	public static class Purple
	{
		int itemId;
		/** Name as on the OSRS Wiki and in raids.ts. */
		String name;
		Group group;
	}

	private static final List<Purple> PURPLES = new ArrayList<>();
	/** Lower-case chat name to purple. */
	private static final Map<String, Purple> BY_CHAT_NAME = new HashMap<>();

	static
	{
		Group cox = Group.CHAMBERS_OF_XERIC;
		add(cox, ItemID.RAIDS_PRAYERSCROLL, "Dexterous prayer scroll");
		add(cox, ItemID.RAIDS_PRAYERSCROLL_AUGURY, "Arcane prayer scroll");
		add(cox, ItemID.TWISTED_BUCKLER, "Twisted buckler");
		add(cox, ItemID.DRAGONHUNTER_XBOW, "Dragon hunter crossbow");
		add(cox, ItemID.DINHS_BULWARK, "Dinh's bulwark");
		add(cox, ItemID.ANCESTRAL_HAT, "Ancestral hat");
		add(cox, ItemID.ANCESTRAL_ROBE_TOP, "Ancestral robe top");
		add(cox, ItemID.ANCESTRAL_ROBE_BOTTOM, "Ancestral robe bottom");
		add(cox, ItemID.DRAGON_CLAWS, "Dragon claws");
		add(cox, ItemID.ELDER_MAUL, "Elder maul");
		add(cox, ItemID.KODAI_INSIGNIA, "Kodai insignia");
		add(cox, ItemID.TWISTED_BOW, "Twisted bow");

		Group tob = Group.THEATRE_OF_BLOOD;
		add(tob, ItemID.INFERNAL_DEFENDER_HILT, "Avernic defender hilt");
		add(tob, ItemID.GHRAZI_RAPIER, "Ghrazi rapier");
		add(tob, ItemID.SANGUINESTI_STAFF_UNCHARGED, "Sanguinesti staff (uncharged)", "Sanguinesti staff");
		add(tob, ItemID.JUSTICIAR_FACEGUARD, "Justiciar faceguard");
		add(tob, ItemID.JUSTICIAR_CHESTGUARD, "Justiciar chestguard");
		add(tob, ItemID.JUSTICIAR_LEG_GUARDS, "Justiciar legguards");
		add(tob, ItemID.SCYTHE_OF_VITUR_UNCHARGED, "Scythe of vitur (uncharged)", "Scythe of vitur");

		Group toa = Group.TOMBS_OF_AMASCUT;
		add(toa, ItemID.OSMUMTENS_FANG, "Osmumten's fang");
		add(toa, ItemID.LIGHTBEARER, "Lightbearer");
		add(toa, ItemID.ELIDINIS_WARD, "Elidinis' ward");
		add(toa, ItemID.MASORI_MASK, "Masori mask");
		add(toa, ItemID.MASORI_BODY, "Masori body");
		add(toa, ItemID.MASORI_CHAPS, "Masori chaps");
		add(toa, ItemID.TUMEKENS_SHADOW_UNCHARGED, "Tumeken's shadow (uncharged)", "Tumeken's shadow");

		// To verify in game: released 20 October 2026; names from raids.ts.
		Group tfa = Group.FRACTURED_ARCHIVE;
		add(tfa, ItemID.OBLIGATOR_UNCHARGED, "Obligator (uncharged)", "Obligator");
		add(tfa, ItemID.RONDACHE, "Rondache");
		add(tfa, ItemID.ZORYAS_TOME_UNCHARGED, "Zorya's tome (uncharged)", "Zorya's tome");
		add(tfa, ItemID.TFA_ZEAL_REWARD, "Horn of Bran");
		add(tfa, ItemID.ASCENSION_CROSSBOWS, "Ascension crossbows");
	}

	private Raids()
	{
	}

	private static void add(Group group, int itemId, String name, String... otherChatNames)
	{
		Purple purple = new Purple(itemId, name, group);
		PURPLES.add(purple);
		BY_CHAT_NAME.put(key(name), purple);
		Arrays.stream(otherChatNames).forEach(other -> BY_CHAT_NAME.put(key(other), purple));
	}

	public static List<Purple> purples()
	{
		return Collections.unmodifiableList(PURPLES);
	}

	/** The purple with this chat name (any case), or null for anything else (pets, normal loot). */
	@Nullable
	public static Purple purple(String chatName)
	{
		return BY_CHAT_NAME.get(key(chatName));
	}

	/** The raid a kill count name (as chat shows it) belongs to, or null for other bosses. */
	@Nullable
	public static Group groupOfKc(String boss)
	{
		String name = key(boss);
		if (name.startsWith("the "))
		{
			name = name.substring(4);
		}
		for (Group group : Group.values())
		{
			String prefix = key(group.kcPrefix);
			if (name.equals(prefix) || name.startsWith(prefix + " ") || name.startsWith(prefix + ":"))
			{
				return group;
			}
		}
		return null;
	}

	/** The raid whose chest the Loot Tracker names this way, or null. */
	@Nullable
	public static Group groupOfChest(String lootName)
	{
		String name = key(lootName);
		for (Group group : Group.values())
		{
			if (name.equals(key(group.chestName)))
			{
				return group;
			}
		}
		return null;
	}

	private static String key(String name)
	{
		return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}
}
