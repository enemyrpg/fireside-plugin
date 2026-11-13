package com.osrstracker.ui;

import com.osrstracker.Contract;
import com.osrstracker.queue.EventQueue;
import com.osrstracker.send.EventSender;
import com.osrstracker.send.SenderStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * What the panel's queue section shows, computed from the sender's status.
 * Pure, so it can be tested without Swing.
 */
@Value
public class QueueView
{
	/** Above this many waiting events the panel warns (03, "Plugin: kø og sending"). */
	public static final int LARGE_QUEUE = 10_000;

	static final String RESUME = " Fix it in the plugin settings, or press \"Test connection\" once it works: "
		+ "sending resumes then. Queued events are kept.";

	StatusView.Level level;
	String title;
	String message;
	String waiting;
	String lastSent;
	/** Null when no event has been rejected (the row is hidden). */
	@Nullable
	String rejected;
	List<String> warnings;
	boolean sendNowEnabled;
	/** Why "Send now" is disabled, or what it does. */
	String sendNowTooltip;

	public static QueueView compute(SenderStatus status, ZoneId zone)
	{
		DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
		StatusView.Level level;
		String title;
		String message;
		boolean sendNow = true;
		String tooltip = "Send queued events to the server now";

		switch (status.getState())
		{
			case STARTING:
				level = StatusView.Level.NEUTRAL;
				title = "Starting…";
				message = "Opening the event queue.";
				sendNow = false;
				tooltip = "The queue is not open yet";
				break;
			case OFF:
				level = StatusView.Level.NEUTRAL;
				title = "Sending is off";
				message = "Turn on \"Send data\" in the plugin settings to send events to your server.";
				sendNow = false;
				tooltip = "Sending is off";
				break;
			case NOT_CONFIGURED:
				level = StatusView.Level.NEUTRAL;
				title = "Not configured";
				message = "Set the server URL and API key in the plugin settings. Nothing is sent until then.";
				sendNow = false;
				tooltip = "Set the server URL and API key first";
				break;
			case CHECKING:
				level = StatusView.Level.BUSY;
				title = "Checking the server…";
				message = "Checking the server's contract version before sending.";
				sendNow = false;
				tooltip = "Already sending";
				break;
			case SENDING:
				level = StatusView.Level.BUSY;
				title = "Sending…";
				message = "Sending queued events.";
				sendNow = false;
				tooltip = "Already sending";
				break;
			case RETRYING:
				level = StatusView.Level.WARNING;
				title = status.getRetryAt() != null ? "Retrying at " + time.format(status.getRetryAt()) : "Retrying";
				message = (status.getDetail() != null ? status.getDetail() + " " : "")
					+ "Queued events are kept and sent when the server answers.";
				tooltip = "Try again now instead of waiting";
				break;
			case STOPPED:
				level = StatusView.Level.ERROR;
				title = "Sending stopped";
				message = stopMessage(status);
				sendNow = false;
				tooltip = "Sending is stopped. Fix the server URL or API key, or press \"Test connection\".";
				break;
			case HELD:
				level = StatusView.Level.WARNING;
				title = "Waiting for a server update";
				message = "The server is too old (contract " + status.getServerContract() + ", this plugin needs "
					+ Contract.REQUIRED_VERSION + "). Update the server. Queued events are kept and sent after "
					+ "that; the plugin checks again every " + EventSender.HELD_RECHECK.toMinutes() + " minutes.";
				tooltip = "Check the server's version again and send if it is new enough";
				break;
			case IDLE:
			default:
				if (status.getQueueSize() == 0)
				{
					level = StatusView.Level.OK;
					title = "Up to date";
					message = "Everything has been sent.";
				}
				else
				{
					level = StatusView.Level.NEUTRAL;
					title = "Waiting to send";
					message = "Events are sent every " + EventSender.SEND_INTERVAL.getSeconds() + " seconds, at "
						+ EventSender.SEND_THRESHOLD + " events and at logout.";
				}
				break;
		}

		List<String> warnings = new ArrayList<>();
		if (status.getStorageError() != null)
		{
			warnings.add(status.getStorageError() + " New events can't be saved until this is fixed.");
		}
		if (status.getQueueSize() > LARGE_QUEUE)
		{
			warnings.add("More than " + String.format("%,d", LARGE_QUEUE) + " events are waiting to be sent. "
				+ "They are kept; check that the server is running and reachable.");
		}

		String rejected = null;
		if (status.getRejectedCount() > 0)
		{
			rejected = String.format("%,d", status.getRejectedCount());
			warnings.add(rejected + (status.getRejectedCount() == 1 ? " event was" : " events were")
				+ " rejected by the server. They are saved with the server's error in "
				+ EventQueue.REJECTED_FILE + " (\"Open folder\").");
		}

		return new QueueView(level, title, message, String.format("%,d", status.getQueueSize()),
			lastSent(status, time), rejected, Collections.unmodifiableList(warnings), sendNow, tooltip);
	}

	private static String stopMessage(SenderStatus status)
	{
		SenderStatus.StopReason reason = status.getStopReason();
		if (reason == SenderStatus.StopReason.UNAUTHORIZED)
		{
			return "Invalid API key: the server rejected it (invalid or revoked)." + RESUME;
		}
		if (reason == SenderStatus.StopReason.NOT_FOUND)
		{
			return "Wrong server URL: the server answered 404 Not Found. Use the server's base URL, "
				+ "e.g. http://127.0.0.1:3000, without /api/v1." + RESUME;
		}
		return (status.getDetail() != null ? status.getDetail() : "The server redirected the request.") + RESUME;
	}

	private static String lastSent(SenderStatus status, DateTimeFormatter time)
	{
		Instant at = status.getLastSentAt();
		if (at == null)
		{
			return "–";
		}
		return time.format(at) + " (" + status.getLastSentCount() + ")";
	}
}
