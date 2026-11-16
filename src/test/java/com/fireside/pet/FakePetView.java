package com.fireside.pet;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link PetView} the tests fill in. */
final class FakePetView implements PetView
{
	static final int VORKI = 21992;
	static final int BEAVER = 13322;
	static final int HERON = 13320;

	boolean standardWorld = true;
	final Map<Integer, String> pets = new LinkedHashMap<>();
	final Map<Integer, Integer> inventory = new HashMap<>();
	String followerName;

	FakePetView()
	{
		pets.put(VORKI, "Vorki");
		pets.put(BEAVER, "Beaver");
		pets.put(HERON, "Heron");
	}

	@Override
	public boolean standardWorld()
	{
		return standardWorld;
	}

	@Override
	public Map<Integer, String> pets()
	{
		return pets;
	}

	@Override
	public Map<Integer, Integer> inventory()
	{
		return new HashMap<>(inventory);
	}

	@Override
	public String followerName()
	{
		return followerName;
	}
}
