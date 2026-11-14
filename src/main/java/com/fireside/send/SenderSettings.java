package com.fireside.send;

import com.fireside.net.ServerUrl;
import com.fireside.net.VerifyClient;
import javax.annotation.Nullable;
import lombok.Value;

/** The config the sender needs, as an immutable snapshot (read on the client side, used on the executor). */
@Value
public class SenderSettings
{
	public static final SenderSettings OFF = new SenderSettings(false, "", "");

	boolean enabled;
	String serverUrl;
	/** Never logged: {@code toString} leaves it out. */
	@lombok.ToString.Exclude
	String apiKey;

	public SenderSettings(boolean enabled, @Nullable String serverUrl, @Nullable String apiKey)
	{
		this.enabled = enabled;
		this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	/** The parsed URL, or null if it is not set or invalid. */
	@Nullable
	public ServerUrl url()
	{
		return ServerUrl.parse(serverUrl);
	}

	/** True when both the URL and a well-formed key are set. */
	public boolean isConfigured()
	{
		return url() != null && VerifyClient.isValidKey(apiKey);
	}

	/** Same server and key (whether sending is on does not matter). */
	public boolean sameConnection(SenderSettings other)
	{
		return serverUrl.equals(other.serverUrl) && apiKey.equals(other.apiKey);
	}
}
