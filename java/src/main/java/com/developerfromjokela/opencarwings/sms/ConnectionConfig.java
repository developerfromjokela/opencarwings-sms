package com.developerfromjokela.opencarwings.sms;


public class ConnectionConfig {
    public String serialPort = "/dev/ttyUSB0";
    public int baudRate = 115200;
    public int commandTimeoutMs = 5000;
    public int cmgsTimeoutMs = 20000;
    public String wsUrl = "wss://opencarwings.viaaq.eu/ws/smsgateway/";
    public boolean autoReconnect = true;
    public int reconnectDelayMs = 5000;
    public int maxReconnectDelayMs = 60000;

    public int pingIntervalSeconds = 30;

    public ConnectionConfig copy() {
        ConnectionConfig c = new ConnectionConfig();
        c.serialPort = this.serialPort;
        c.baudRate = this.baudRate;
        c.commandTimeoutMs = this.commandTimeoutMs;
        c.cmgsTimeoutMs = this.cmgsTimeoutMs;
        c.wsUrl = this.wsUrl;
        c.autoReconnect = this.autoReconnect;
        c.reconnectDelayMs = this.reconnectDelayMs;
        c.maxReconnectDelayMs = this.maxReconnectDelayMs;
        c.pingIntervalSeconds = this.pingIntervalSeconds;
        return c;
    }
}
