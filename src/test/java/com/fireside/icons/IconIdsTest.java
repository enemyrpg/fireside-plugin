package com.fireside.icons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.Kc;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.RaidSpecialLoot;
import com.fireside.event.UuidV7Generator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** IDs are taken exactly as they are in the event: never canonicalized. */
public class IconIdsTest
{
	private final UuidV7Generator ids = new UuidV7Generator();

	private Event event(EventPayload payload)
	{
		return Event.create(ids, 42L, Instant.parse("2026-11-15T12:00:00.000Z"), ids.next(), payload);
	}

	private static Loot loot(LootItem... items)
	{
		return new Loot(LootSource.npc("Vorkath", 8061, 732), Arrays.asList(items), null);
	}

	@Test
	public void notedItemsKeepTheirNotedId()
	{
		// 4152 is a noted Abyssal whip (4151). The dashboard asks for 4152, so that is the key.
		Event e = event(loot(new LootItem(4152, "Abyssal whip", 3, 1_500_000, 72_000)));
		assertEquals(Collections.singletonList(4152), new ArrayList<>(IconIds.of(e)));
	}

	@Test
	public void coinsAreOneIdWhateverTheStack()
	{
		Event small = event(loot(new LootItem(995, "Coins", 1, 1, 0)));
		Event big = event(loot(new LootItem(995, "Coins", 250_000, 1, 0)));
		assertEquals(Collections.singletonList(995), new ArrayList<>(IconIds.of(small)));
		assertEquals(Collections.singletonList(995), new ArrayList<>(IconIds.of(big)));
	}

	@Test
	public void allLootItemsInOrderWithoutDuplicates()
	{
		Event e = event(loot(
			new LootItem(11286, "Draconic visage", 1, 2_850_000, 60_000),
			new LootItem(536, "Dragon bones", 2, 2100, 0),
			new LootItem(537, "Dragon bones", 5, 2100, 0),
			new LootItem(536, "Dragon bones", 1, 2100, 0)));
		assertEquals(Arrays.asList(11286, 536, 537), new ArrayList<>(IconIds.of(e)));
	}

	@Test
	public void raidSpecialLoot()
	{
		Event e = event(RaidSpecialLoot.builder().raid("Chambers of Xeric")
			.item(new RaidSpecialLoot.Item(21034, "Dexterous prayer scroll", 1, 30_000_000))
			.recipient(RaidSpecialLoot.Recipient.TEAMMATE).build());
		assertEquals(Collections.singletonList(21034), new ArrayList<>(IconIds.of(e)));
	}

	@Test
	public void eventsWithoutItemsGiveNothing()
	{
		assertTrue(IconIds.of(event(Kc.builder().boss("Zulrah").killCount(1).build())).isEmpty());
	}
}
