package com.osrstracker.session;

import com.osrstracker.event.AccountType;
import com.osrstracker.event.SessionHeartbeat;
import com.osrstracker.event.XpSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

/** {@link GameView} over the RuneLite client. Client thread only. */
@Slf4j
public class ClientGameView implements GameView
{
	private final Client client;
	private final WorldService worldService;

	@Inject
	public ClientGameView(Client client, WorldService worldService)
	{
		this.client = client;
		this.worldService = worldService;
	}

	@Override
	public long accountHash()
	{
		return client.getAccountHash();
	}

	@Nullable
	@Override
	public String displayName()
	{
		Player player = client.getLocalPlayer();
		return player == null ? null : player.getName();
	}

	@Override
	public AccountType accountType()
	{
		return accountType(client.getVarbitValue(VarbitID.IRONMAN));
	}

	/** The {@code IRONMAN} varbit (0–6) in contract order. */
	static AccountType accountType(int varbit)
	{
		AccountType[] types = AccountType.values();
		if (varbit < 0 || varbit >= types.length)
		{
			log.debug("Unknown account type varbit value {}; sending NORMAL", varbit);
			return AccountType.NORMAL;
		}
		return types[varbit];
	}

	@Override
	public int world()
	{
		return client.getWorld();
	}

	@Override
	public int idleTicks()
	{
		return Math.min(client.getMouseIdleTicks(), client.getKeyboardIdleTicks());
	}

	@Override
	public SessionHeartbeat heartbeat(boolean idle)
	{
		int world = client.getWorld();
		List<String> worldTypes = new ArrayList<>();
		for (WorldType type : client.getWorldType())
		{
			worldTypes.add(type.name());
		}
		String location = null;
		String activity = null;
		// Cached list; null until RuneLite has loaded it. Never blocks.
		WorldResult worlds = worldService.getWorlds();
		World info = worlds == null ? null : worlds.findWorld(world);
		if (info != null)
		{
			location = info.getRegion() == null ? null : info.getRegion().name();
			activity = info.getActivity();
		}
		return new SessionHeartbeat(regionId(), world, worldTypes, location, activity, idle);
	}

	@Nullable
	@Override
	public Map<String, XpSnapshot.SkillXp> skills()
	{
		return skills(client::getSkillExperience, client::getRealSkillLevel);
	}

	/**
	 * Every {@link Skill} ({@code values()} leaves out the deprecated overall
	 * entry). Null while any real level is 0: every skill starts at level 1,
	 * so 0 means the stats are not loaded yet.
	 */
	@Nullable
	static Map<String, XpSnapshot.SkillXp> skills(ToIntFunction<Skill> xp, ToIntFunction<Skill> realLevel)
	{
		Map<String, XpSnapshot.SkillXp> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			int level = realLevel.applyAsInt(skill);
			if (level < 1)
			{
				return null;
			}
			skills.put(skill.name(), new XpSnapshot.SkillXp(xp.applyAsInt(skill), level));
		}
		return skills;
	}

	/** Instance-aware: inside an instance, the region of the template the instance was copied from. */
	private int regionId()
	{
		Player player = client.getLocalPlayer();
		LocalPoint local = player == null ? null : player.getLocalLocation();
		if (local == null)
		{
			return 0;
		}
		WorldPoint point = WorldPoint.fromLocalInstance(client, local);
		return point == null ? 0 : point.getRegionID();
	}
}
