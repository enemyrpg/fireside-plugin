package com.osrstracker.event;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Serializes events to one line of compact JSON, as the contract wants them.
 * <p>
 * Built on the injected {@link Gson} ({@code newBuilder()}), without
 * {@code serializeNulls}: optional fields that are null are left out. The
 * contract has three fields that must be sent as {@code null} instead
 * ({@code source.name} for {@code PLAYER} loot, and {@code worldLocation} and
 * {@code worldActivity} in heartbeats). Those are written by the adapters
 * below. The pinned JSON Schema tests check every event type.
 */
@Singleton
public class EventJson
{
	private final Gson gson;

	@Inject
	public EventJson(Gson gson)
	{
		this.gson = gson.newBuilder()
			.disableHtmlEscaping()
			.registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
			.registerTypeAdapter(LootSource.class, new LootSourceAdapter().nullSafe())
			.registerTypeAdapter(SessionHeartbeat.class, new HeartbeatAdapter().nullSafe())
			.create();
	}

	/** One event as compact JSON on a single line (no line breaks). */
	public String toJson(Event event)
	{
		StringWriter out = new StringWriter();
		try (JsonWriter writer = gson.newJsonWriter(out))
		{
			writer.beginObject();
			writer.name("eventId").value(event.getEventId());
			writer.name("schemaVersion").value(Event.SCHEMA_VERSION);
			writer.name("type").value(event.getType().wireName());
			writer.name("accountHash").value(Long.toString(event.getAccountHash()));
			writer.name("occurredAt").value(Timestamps.format(event.getOccurredAt()));
			writer.name("sessionId").value(event.getSessionId());
			writer.name("payload");
			EventPayload payload = event.getPayload();
			gson.toJson(payload, payload.getClass(), writer);
			writer.endObject();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return out.toString();
	}

	/** Writes {@code "name": null} even though the writer leaves nulls out. */
	private static void writeNull(JsonWriter out, String name) throws IOException
	{
		boolean serializeNulls = out.getSerializeNulls();
		out.setSerializeNulls(true);
		out.name(name).nullValue();
		out.setSerializeNulls(serializeNulls);
	}

	private static void writeNullable(JsonWriter out, String name, @Nullable String value) throws IOException
	{
		if (value == null)
		{
			writeNull(out, name);
		}
		else
		{
			out.name(name).value(value);
		}
	}

	private static final class InstantAdapter extends TypeAdapter<Instant>
	{
		@Override
		public void write(JsonWriter out, Instant value) throws IOException
		{
			out.value(Timestamps.format(value));
		}

		@Override
		public Instant read(JsonReader in)
		{
			throw new UnsupportedOperationException("events are only written");
		}
	}

	private static final class LootSourceAdapter extends TypeAdapter<LootSource>
	{
		@Override
		public void write(JsonWriter out, LootSource source) throws IOException
		{
			out.beginObject();
			out.name("type").value(source.getType().name());
			if (source.getType() == LootSource.Type.PLAYER)
			{
				// Never a name for other players, and no npcId or combatLevel.
				writeNull(out, "name");
			}
			else
			{
				out.name("name").value(source.getName());
				if (source.getType() == LootSource.Type.NPC)
				{
					if (source.getNpcId() != null)
					{
						out.name("npcId").value(source.getNpcId());
					}
					if (source.getCombatLevel() != null)
					{
						out.name("combatLevel").value(source.getCombatLevel());
					}
				}
			}
			out.endObject();
		}

		@Override
		public LootSource read(JsonReader in)
		{
			throw new UnsupportedOperationException("events are only written");
		}
	}

	private static final class HeartbeatAdapter extends TypeAdapter<SessionHeartbeat>
	{
		@Override
		public void write(JsonWriter out, SessionHeartbeat heartbeat) throws IOException
		{
			out.beginObject();
			out.name("regionId").value(heartbeat.getRegionId());
			out.name("world").value(heartbeat.getWorld());
			out.name("worldTypes").beginArray();
			for (String worldType : heartbeat.getWorldTypes())
			{
				out.value(worldType);
			}
			out.endArray();
			writeNullable(out, "worldLocation", heartbeat.getWorldLocation());
			writeNullable(out, "worldActivity", heartbeat.getWorldActivity());
			out.name("idle").value(heartbeat.isIdle());
			out.endObject();
		}

		@Override
		public SessionHeartbeat read(JsonReader in)
		{
			throw new UnsupportedOperationException("events are only written");
		}
	}
}
