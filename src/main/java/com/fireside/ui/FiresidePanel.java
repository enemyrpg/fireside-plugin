package com.fireside.ui;

import com.fireside.Branding;
import com.fireside.Contract;
import com.fireside.net.VerifyResponse;
import com.fireside.send.SenderStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: connection status, versions, "Test connection" and
 * "Open dashboard", and the event queue with "Send now" and "Open folder".
 * <p>
 * All methods must be called on the Swing event dispatch thread.
 */
public class FiresidePanel extends PluginPanel
{
	private static final int OUTER_PADDING = 10;
	private static final int SECTION_PADDING = 8;
	/** Text width inside a section, used for wrapping before the first layout. */
	private static final int TEXT_WIDTH = PANEL_WIDTH - 2 * OUTER_PADDING - 2 * SECTION_PADDING;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private static final Color OK_COLOR = new Color(0x5C, 0xC4, 0x5C);
	private static final Color WARNING_COLOR = new Color(0xF2, 0xB1, 0x34);
	private static final Color ERROR_COLOR = new Color(0xE0, 0x5A, 0x4F);

	private final JLabel statusTitle = new JLabel();
	private final WrappedText statusMessage = new WrappedText(TEXT_WIDTH);
	private final JPanel warningsPanel = new JPanel();

	private final JLabel serverValue = valueLabel();
	private final JLabel sendingValue = valueLabel();
	private final JLabel keyValue = valueLabel();
	private final JLabel serverVersionValue = valueLabel();
	private final JLabel serverContractValue = valueLabel();
	private final JLabel lastTestValue = valueLabel();

	private final JButton testButton = new JButton("Test connection");
	private final JButton dashboardButton = new JButton("Open dashboard");

	private final JLabel queueTitle = new JLabel();
	private final WrappedText queueMessage = new WrappedText(TEXT_WIDTH);
	private final JLabel waitingValue = valueLabel();
	private final JLabel lastSentValue = valueLabel();
	private final JLabel rejectedValue = valueLabel();
	private final JPanel rejectedRow;
	private final JPanel queueWarningsPanel = new JPanel();
	private final JButton sendNowButton = new JButton("Send now");
	private final JButton openFolderButton = new JButton("Open folder");

	public FiresidePanel(Runnable onTestConnection, Runnable onOpenDashboard, Runnable onSendNow,
		Runnable onOpenFolder)
	{
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(OUTER_PADDING, OUTER_PADDING, OUTER_PADDING, OUTER_PADDING));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		JLabel title = new JLabel(Branding.DISPLAY_NAME);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		content.add(left(title));
		content.add(Box.createVerticalStrut(8));

		// Status
		JPanel status = section();
		statusTitle.setFont(FontManager.getRunescapeBoldFont());
		status.add(left(statusTitle));
		status.add(Box.createVerticalStrut(4));
		statusMessage.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.add(left(statusMessage));
		warningsPanel.setLayout(new BoxLayout(warningsPanel, BoxLayout.Y_AXIS));
		warningsPanel.setOpaque(false);
		status.add(left(warningsPanel));
		content.add(left(status));
		content.add(Box.createVerticalStrut(8));

		// Buttons
		JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
		buttons.setOpaque(false);
		testButton.setFocusPainted(false);
		testButton.addActionListener(e -> onTestConnection.run());
		dashboardButton.setFocusPainted(false);
		dashboardButton.addActionListener(e -> onOpenDashboard.run());
		buttons.add(testButton);
		buttons.add(dashboardButton);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
		content.add(left(buttons));
		content.add(Box.createVerticalStrut(8));

		// Details
		JPanel details = section();
		details.add(row("Server", serverValue));
		details.add(row("Sending", sendingValue));
		details.add(row("API key", keyValue));
		details.add(row("Server version", serverVersionValue));
		details.add(row("Server contract", serverContractValue));
		details.add(row("Plugin needs", valueLabel(Contract.REQUIRED_VERSION)));
		details.add(row("Last test", lastTestValue));
		content.add(left(details));
		content.add(Box.createVerticalStrut(8));

		// Queue
		JPanel queue = section();
		JLabel queueHeading = new JLabel("Queue");
		queueHeading.setFont(FontManager.getRunescapeBoldFont());
		queueHeading.setForeground(Color.WHITE);
		queue.add(left(queueHeading));
		queue.add(Box.createVerticalStrut(4));
		queueTitle.setFont(FontManager.getRunescapeBoldFont());
		queue.add(left(queueTitle));
		queue.add(Box.createVerticalStrut(4));
		queueMessage.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		queue.add(left(queueMessage));
		queue.add(Box.createVerticalStrut(4));
		queue.add(row("Waiting", waitingValue));
		queue.add(row("Last sent", lastSentValue));
		rejectedValue.setForeground(WARNING_COLOR);
		rejectedRow = row("Rejected", rejectedValue);
		queue.add(rejectedRow);
		queueWarningsPanel.setLayout(new BoxLayout(queueWarningsPanel, BoxLayout.Y_AXIS));
		queueWarningsPanel.setOpaque(false);
		queue.add(left(queueWarningsPanel));
		queue.add(Box.createVerticalStrut(8));
		JPanel queueButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		queueButtons.setOpaque(false);
		sendNowButton.setFocusPainted(false);
		sendNowButton.addActionListener(e -> onSendNow.run());
		openFolderButton.setFocusPainted(false);
		openFolderButton.setToolTipText("Open the plugin's data folder (queue and error log)");
		openFolderButton.addActionListener(e -> onOpenFolder.run());
		queueButtons.add(sendNowButton);
		queueButtons.add(openFolderButton);
		queueButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, queueButtons.getPreferredSize().height));
		queue.add(left(queueButtons));
		content.add(left(queue));

		add(content, BorderLayout.NORTH);
		renderQueue(QueueView.compute(SenderStatus.STARTING_STATUS, ZoneId.systemDefault()));
	}

	/**
	 * Updates everything shown in the panel.
	 *
	 * @param serverUrl          the configured URL as typed, or null if not set
	 * @param response           details from the last successful test, if any
	 * @param testedAt           when the last test finished, if any
	 * @param dashboardAvailable true when the URL is valid
	 */
	public void render(StatusView view, @Nullable String serverUrl, boolean sendingEnabled,
		@Nullable VerifyResponse response, @Nullable Instant testedAt, boolean testing, boolean dashboardAvailable)
	{
		statusTitle.setText(view.getTitle());
		statusTitle.setForeground(color(view.getLevel()));
		statusMessage.setText(view.getMessage());

		showWarnings(warningsPanel, view.getWarnings());

		setTruncated(serverValue, serverUrl == null || serverUrl.trim().isEmpty() ? "Not set" : serverUrl.trim());
		sendingValue.setText(sendingEnabled ? "On" : "Off");
		setTruncated(keyValue, response != null ? response.getKeyName() : "–");
		serverVersionValue.setText(response != null ? response.getServerVersion() : "–");
		serverContractValue.setText(response != null ? response.getContractVersion().toString() : "–");
		lastTestValue.setText(testedAt != null ? TIME.format(testedAt) : "–");

		testButton.setEnabled(!testing);
		testButton.setText(testing ? "Testing…" : "Test connection");
		dashboardButton.setEnabled(dashboardAvailable);

		revalidate();
		repaint();
	}

	/** Updates the queue section. */
	public void renderQueue(QueueView view)
	{
		queueTitle.setText(view.getTitle());
		queueTitle.setForeground(color(view.getLevel()));
		queueMessage.setText(view.getMessage());
		waitingValue.setText(view.getWaiting());
		lastSentValue.setText(view.getLastSent());
		rejectedRow.setVisible(view.getRejected() != null);
		rejectedValue.setText(view.getRejected() != null ? view.getRejected() : "");
		showWarnings(queueWarningsPanel, view.getWarnings());
		sendNowButton.setEnabled(view.isSendNowEnabled());
		sendNowButton.setToolTipText(view.getSendNowTooltip());

		revalidate();
		repaint();
	}

	private static void showWarnings(JPanel panel, List<String> warnings)
	{
		panel.removeAll();
		for (String warning : warnings)
		{
			panel.add(Box.createVerticalStrut(6));
			WrappedText text = new WrappedText(TEXT_WIDTH);
			text.setText("⚠ " + warning);
			text.setForeground(WARNING_COLOR);
			panel.add(left(text));
		}
	}

	/** Plain text (cut with "…" if too long) with the full text as tooltip. */
	private static void setTruncated(JLabel label, String text)
	{
		label.setText(text);
		label.setToolTipText(text);
	}

	private static Color color(StatusView.Level level)
	{
		switch (level)
		{
			case OK:
				return OK_COLOR;
			case WARNING:
				return WARNING_COLOR;
			case ERROR:
				return ERROR_COLOR;
			default:
				return Color.WHITE;
		}
	}

	private static JPanel section()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(SECTION_PADDING, SECTION_PADDING, SECTION_PADDING, SECTION_PADDING));
		return panel;
	}

	private static JPanel row(String name, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel label = new JLabel(name);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setVerticalAlignment(JLabel.TOP);
		row.add(label, BorderLayout.WEST);
		value.setHorizontalAlignment(JLabel.RIGHT);
		value.setVerticalAlignment(JLabel.TOP);
		row.add(value, BorderLayout.CENTER);
		return left(row);
	}

	private static JLabel valueLabel()
	{
		return valueLabel("–");
	}

	private static JLabel valueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		return label;
	}

	private static <T extends Component> T left(T component)
	{
		if (component instanceof JComponent)
		{
			((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		return component;
	}
}
