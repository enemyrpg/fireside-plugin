package com.fireside.bank;

import javax.annotation.Nullable;

/**
 * Where the hash of the last queued snapshot is kept per account, so an
 * unchanged bank is not sent again after a restart or a new login.
 * {@link RsProfileHashStore} is the real one (RuneLite's per-account RS profile
 * config); tests use a map.
 */
public interface HashStore
{
	/** The current account's RS profile key, or null if there is none yet. */
	@Nullable
	String currentProfile();

	@Nullable
	String get(String profile, String key);

	void set(String profile, String key, String hash);

	/** Removes {@code key} for every profile (every account). */
	void clearAll(String key);
}
