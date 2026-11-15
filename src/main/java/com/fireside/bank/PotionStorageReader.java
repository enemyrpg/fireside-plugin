/*
 * Copyright (c) 2024, Adam <Adam@sigterm.info>
 * Copyright (c) 2026, Fireside contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.fireside.bank;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ScriptID;

/**
 * Reads potion storage the way RuneLite's Bank Tags plugin does
 * ({@code net.runelite.client.plugins.banktags.tabs.PotionStorage#rebuildPotions},
 * BSD-2): walk the game's potion store enums, and ask the game's own
 * {@code POTIONSTORE_DOSES} script how many doses of each are stored. Reads no
 * widgets, so it works whichever bank tab was open. Client thread only, and
 * outside script execution (the caller uses {@code invokeLater}).
 */
final class PotionStorageReader
{
	private static final int MAX_DOSES = 4;

	private final Client client;

	PotionStorageReader(Client client)
	{
		this.client = client;
	}

	List<BankView.RawPotion> read()
	{
		List<BankView.RawPotion> potions = new ArrayList<>();
		for (int listId : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
		{
			EnumComposition list = client.getEnum(listId);
			for (int potionEnumId : list.getIntVals())
			{
				EnumComposition potionEnum = client.getEnum(potionEnumId);
				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
				int doses = client.getIntStack()[0];
				if (doses <= 0)
				{
					continue;
				}
				int[] variants = new int[MAX_DOSES];
				for (int d = 1; d <= MAX_DOSES; d++)
				{
					variants[d - 1] = potionEnum.getIntValue(d);
				}
				potions.add(new BankView.RawPotion(variants, doses));
			}
		}
		return potions;
	}
}
