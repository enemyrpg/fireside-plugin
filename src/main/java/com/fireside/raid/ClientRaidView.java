package com.fireside.raid;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/** {@link RaidView} backed by the client's varbits, as RuneLite's Chat Commands plugin reads them. Client thread only. */
@Singleton
public class ClientRaidView implements RaidView
{
	private static final int[] TOB_SLOTS = {
		VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2, VarbitID.TOB_CLIENT_P3,
		VarbitID.TOB_CLIENT_P4,
	};
	private static final int[] TOA_SLOTS = {
		VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
		VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7,
	};

	private final Client client;

	@Inject
	ClientRaidView(Client client)
	{
		this.client = client;
	}

	@Override
	public int coxPartySize()
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
	}

	@Override
	public int tobPartySize()
	{
		return filledSlots(TOB_SLOTS);
	}

	@Override
	public int toaPartySize()
	{
		return filledSlots(TOA_SLOTS);
	}

	@Override
	public boolean isLocalPlayer(String name)
	{
		Player player = client.getLocalPlayer();
		String me = player != null ? player.getName() : null;
		// standardize: no tags, Jagex spaces (NBSP) as spaces, lower case.
		return me != null && !me.isEmpty() && Text.standardize(me).equals(Text.standardize(name));
	}

	private int filledSlots(int[] varbits)
	{
		int size = 0;
		for (int varbit : varbits)
		{
			size += Math.min(client.getVarbitValue(varbit), 1);
		}
		return size;
	}
}
