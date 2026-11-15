package com.fireside.bank;

import com.fireside.event.BankSnapshot;
import com.fireside.event.PotionStorageSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * {@code contentHash} for the snapshots: {@code sha256:} and 64 lowercase hex
 * characters over a versioned canonical text of the content in container
 * order. It covers what is in the bank, never names or prices, so a price
 * change alone never causes a new snapshot. Bank fillers are items, so moving
 * one is a change. Bumping {@link #VERSION} resends every bank once.
 */
public final class ContentHash
{
	static final String VERSION = "v1";

	private ContentHash()
	{
	}

	/** Tab sizes, then bank (id, quantity, placeholder), inventory (id, quantity) and equipment (slot, id, quantity). */
	public static String bank(List<Integer> tabSizes, List<BankSnapshot.Item> items,
		List<BankSnapshot.InventoryItem> inventory, List<BankSnapshot.WornItem> equipment)
	{
		StringBuilder text = new StringBuilder(32 + items.size() * 16);
		text.append(VERSION).append("|tabs:");
		for (int i = 0; i < tabSizes.size(); i++)
		{
			text.append(i > 0 ? "," : "").append(tabSizes.get(i));
		}
		text.append("|bank:");
		for (BankSnapshot.Item item : items)
		{
			text.append(item.getItemId()).append(':').append(item.getQuantity()).append(':')
				.append(item.isPlaceholder() ? 'p' : 'i').append(';');
		}
		text.append("|inv:");
		for (BankSnapshot.InventoryItem item : inventory)
		{
			text.append(item.getItemId()).append(':').append(item.getQuantity()).append(';');
		}
		text.append("|worn:");
		for (BankSnapshot.WornItem item : equipment)
		{
			text.append(item.getSlot()).append(':').append(item.getItemId()).append(':')
				.append(item.getQuantity()).append(';');
		}
		return sha256(text.toString());
	}

	/** Potion item IDs and doses, in storage order. */
	public static String potions(List<PotionStorageSnapshot.Potion> potions)
	{
		StringBuilder text = new StringBuilder(VERSION).append("|potions:");
		for (PotionStorageSnapshot.Potion potion : potions)
		{
			text.append(potion.getItemId()).append(':').append(potion.getDoses()).append(';');
		}
		return sha256(text.toString());
	}

	/** {@code sha256:} and the lowercase hex digest of {@code text} (UTF-8). */
	public static String sha256(String text)
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			// Every Java runtime has SHA-256.
			throw new IllegalStateException(e);
		}
		byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder("sha256:");
		for (byte b : bytes)
		{
			hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return hex.toString();
	}
}
