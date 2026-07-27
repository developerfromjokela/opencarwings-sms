package com.developerfromjokela.opencarwings.sms.gui;

import com.developerfromjokela.opencarwings.sms.utils.HexUtils;
import com.developerfromjokela.opencarwings.sms.ws.DeviceIdentifiers;
import com.fazecast.jSerialComm.SerialPort;
import com.developerfromjokela.opencarwings.sms.ConnectionConfig;
import com.developerfromjokela.opencarwings.sms.modem.Modem;
import com.developerfromjokela.opencarwings.sms.ws.SmsWebSocketClient;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.prefs.Preferences;

public class GuiApp extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(GuiApp.class);

    private final JComboBox<String> portCombo = new JComboBox<>();
    private final JSpinner baudSpinner = new JSpinner(new SpinnerNumberModel(115200, 300, 4_000_000, 100));
    private final JSpinner commandTimeoutSpinner = new JSpinner(new SpinnerNumberModel(5000, 100, 120_000, 100));
    private final JSpinner cmgsTimeoutSpinner = new JSpinner(new SpinnerNumberModel(20000, 100, 300_000, 100));
    private final JTextField wsUrlField = new JTextField("wss://opencarwings.viaaq.eu/ws/smsgateway/", 24);
    private final JTextField deviceId = new JTextField("", 24);
    private final JTextField encryptionKey = new JTextField("", 24);
    private final JCheckBox autoReconnectCheckbox = new JCheckBox("Auto-reconnect WebSocket", true);
    private final JSpinner pingIntervalSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 600, 5));
    private final JSpinner reconnectDelaySpinner = new JSpinner(new SpinnerNumberModel(5000, 500, 120_000, 500));
    private final JSpinner maxReconnectDelaySpinner = new JSpinner(new SpinnerNumberModel(60000, 1000, 600_000, 1000));
    private final JCheckBox autoConnectOnLaunchCheckbox = new JCheckBox("Autoconnect on launch");
    private final JCheckBox pduSpaceCommandCheckbox = new JCheckBox("PDU Space Command before send");
    private final JButton refreshPortsButton = new JButton("Refresh");
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Disconnected");

    private Modem modem;
    private SmsWebSocketClient wsClient;

    public GuiApp() {
        super("SMS Gateway for OpenCARWINGS");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                dispose();
                System.exit(0);
            }
        });

        buildUi();
        loadPrefs();
        refreshPorts();
        updateButtons();

        if (autoConnectOnLaunchCheckbox.isSelected()) {
            SwingUtilities.invokeLater(this::connect);
        }
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("Serial port:"), gbc);
        JPanel portRow = new JPanel(new BorderLayout(4, 0));
        portRow.add(portCombo, BorderLayout.CENTER);
        portRow.add(refreshPortsButton, BorderLayout.EAST);
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(portRow, gbc);
        gbc.gridwidth = 1;
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("Baud rate:"), gbc);
        gbc.gridx = 1; gbc.gridy = row; form.add(baudSpinner, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("Command timeout (ms):"), gbc);
        gbc.gridx = 1; gbc.gridy = row; form.add(commandTimeoutSpinner, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("CMGS timeout (ms):"), gbc);
        gbc.gridx = 1; gbc.gridy = row; form.add(cmgsTimeoutSpinner, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("WebSocket URL:"), gbc);
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(wsUrlField, gbc);
        gbc.gridwidth = 1;
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("Device ID:"), gbc);
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(deviceId, gbc);
        gbc.gridwidth = 1;
        deviceId.setEditable(false);
        row++;

        gbc.gridx = 0; gbc.gridy = row; form.add(new JLabel("Encryption Key:"), gbc);
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(encryptionKey, gbc);
        gbc.gridwidth = 1;
        encryptionKey.setEditable(false);
        row++;

        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(autoReconnectCheckbox, gbc);
        row++;

        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(autoConnectOnLaunchCheckbox, gbc);
        row++;

        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; form.add(pduSpaceCommandCheckbox, gbc);
        row++;

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonRow.add(connectButton);
        buttonRow.add(disconnectButton);
        buttonRow.add(statusLabel);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3; form.add(buttonRow, gbc);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(560, 260));

        add(form, BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);

        refreshPortsButton.addActionListener(e -> refreshPorts());
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());

        pack();
        setLocationRelativeTo(null);
    }

    private void refreshPorts() {
        String selected = (String) portCombo.getSelectedItem();
        portCombo.removeAllItems();
        for (SerialPort p : SerialPort.getCommPorts()) {
            portCombo.addItem(p.getSystemPortName());
        }
        if (selected != null) {
            portCombo.setSelectedItem(selected);
        }
    }

    private void loadPrefs() {
        String savedPort = PREFS.get("serialPort", null);
        if (savedPort != null) {
            portCombo.setSelectedItem(savedPort);
        }
        baudSpinner.setValue(PREFS.getInt("baudRate", 115200));
        commandTimeoutSpinner.setValue(PREFS.getInt("commandTimeoutMs", 5000));
        cmgsTimeoutSpinner.setValue(PREFS.getInt("cmgsTimeoutMs", 20000));
        wsUrlField.setText(PREFS.get("wsUrl", "wss://opencarwings.viaaq.eu/ws/smsgateway/"));
        autoReconnectCheckbox.setSelected(PREFS.getBoolean("autoReconnect", true));
        pduSpaceCommandCheckbox.setSelected(PREFS.getBoolean("pduSpaceCommand", false));
        pingIntervalSpinner.setValue(PREFS.getInt("pingIntervalSeconds", 30));
        reconnectDelaySpinner.setValue(PREFS.getInt("reconnectDelayMs", 5000));
        maxReconnectDelaySpinner.setValue(PREFS.getInt("maxReconnectDelayMs", 60000));
        autoConnectOnLaunchCheckbox.setSelected(PREFS.getBoolean("autoConnectOnLaunch", false));
        deviceId.setText(DeviceIdentifiers.getDeviceId());
        encryptionKey.setText(HexUtils.bytesToHex(DeviceIdentifiers.getEncryptionKey(), true).toUpperCase());
    }

    private void savePrefs(ConnectionConfig config) {
        PREFS.put("serialPort", config.serialPort);
        PREFS.putInt("baudRate", config.baudRate);
        PREFS.putInt("commandTimeoutMs", config.commandTimeoutMs);
        PREFS.putInt("cmgsTimeoutMs", config.cmgsTimeoutMs);
        PREFS.put("wsUrl", config.wsUrl);
        PREFS.putBoolean("autoReconnect", config.autoReconnect);
        PREFS.putInt("pingIntervalSeconds", config.pingIntervalSeconds);
        PREFS.putInt("reconnectDelayMs", config.reconnectDelayMs);
        PREFS.putInt("maxReconnectDelayMs", config.maxReconnectDelayMs);
        PREFS.putBoolean("autoConnectOnLaunch", autoConnectOnLaunchCheckbox.isSelected());
        PREFS.putBoolean("pduSpaceCommand", pduSpaceCommandCheckbox.isSelected());
    }

    private ConnectionConfig readConfigFromForm() {
        ConnectionConfig config = new ConnectionConfig();
        Object port = portCombo.getSelectedItem();
        config.serialPort = port != null ? port.toString() : "";
        config.baudRate = (Integer) baudSpinner.getValue();
        config.commandTimeoutMs = (Integer) commandTimeoutSpinner.getValue();
        config.cmgsTimeoutMs = (Integer) cmgsTimeoutSpinner.getValue();
        config.wsUrl = wsUrlField.getText().trim();
        config.autoReconnect = autoReconnectCheckbox.isSelected();
        config.pduSpaceCommand = pduSpaceCommandCheckbox.isSelected();
        return config;
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    private void updateButtons() {
        boolean connected = modem != null;
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
    }

    private void connect() {
        ConnectionConfig config = readConfigFromForm();
        if (config.serialPort == null || config.serialPort.isEmpty()) {
            log("[gui] no serial port selected");
            return;
        }

        connectButton.setEnabled(false);
        setStatus("Connecting...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    modem = new Modem(config.serialPort, config.baudRate, config.commandTimeoutMs, config.cmgsTimeoutMs, config.pduSpaceCommand);
                    log("[gui] modem opened on " + config.serialPort);

                    wsClient = new SmsWebSocketClient(
                            URI.create(config.wsUrl),
                            modem,
                            config.autoReconnect,
                            config.reconnectDelayMs,
                            config.maxReconnectDelayMs,
                            config.pingIntervalSeconds,
                            GuiApp.this::log
                    );
                    wsClient.connect();
                    savePrefs(config);
                } catch (Exception e) {
                    log("[gui] connection failed: " + e.getMessage());
                    modem = null;
                    wsClient = null;
                }
                return null;
            }

            @Override
            protected void done() {
                setStatus(modem != null ? "Connected (" + config.serialPort + ")" : "Disconnected");
                updateButtons();
            }
        }.execute();
    }

    private void disconnect() {
        if (wsClient != null) {
            wsClient.shutdown();
            wsClient = null;
        }
        if (modem != null) {
            modem.close();
            modem = null;
        }
        log("[gui] disconnected");
        setStatus("Disconnected");
        updateButtons();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new GuiApp().setVisible(true);
        });
    }
}
