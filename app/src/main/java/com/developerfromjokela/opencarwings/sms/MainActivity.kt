package com.developerfromjokela.opencarwings.sms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.gustavoavila.websocketclient.WebSocketClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class MainActivity : Activity() {

    companion object {
        val HEX_ARRAY: CharArray = "0123456789ABCDEF".toCharArray()
    }

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var serverUrl: String
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var changeServerUrlButton: Button
    private lateinit var connectStatus: TextView

    private var errorAlert: AlertDialog? = null

    private var deviceId: String = ""
    private var encryptionKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Security.addProvider(BouncyCastleProvider())

        requestSmsPermission()

        serverUrl = PrefsHelper(this).server ?: "biz.viaaq.eu"

        deviceId = getDeviceId(this) ?: "UNKNOWN"

        if (PrefsHelper(this).encryption == null) {
            val random = SecureRandom()
            val bytes = ByteArray(16) // 128 bits are converted to 16 bytes;
            random.nextBytes(bytes)
            val hexKey = bytesToHex(bytes)
            encryptionKey = bytes
            PrefsHelper(this).encryption = hexKey
            findViewById<TextView>(R.id.encryptionKey).text = "Encryption Key: ${hexKey.chunked(4).joinToString(" ")}"
        } else {
            val hexKey = PrefsHelper(this).encryption ?: ""
            encryptionKey = hexKey.decodeHex()
            findViewById<TextView>(R.id.encryptionKey).text = "Encryption Key: ${hexKey.chunked(4).joinToString(" ")}"
        }

        findViewById<TextView>(R.id.deviceId).text = "Device ID: ${deviceId}"

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initializeWakeLock()

        // Initialize UI elements
        connectButton = findViewById(R.id.connect)
        disconnectButton = findViewById(R.id.disconnect)
        changeServerUrlButton = findViewById(R.id.changeServerUrl)
        connectStatus = findViewById(R.id.connectStatus)

        findViewById<TextView>(R.id.serverUrl).text = "Server: $serverUrl"
        updateButtonStates(false)
        setupButtonListeners()
        createWebSocketClient()
    }

    private fun initializeWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MainActivity:WakeLock")
            wakeLock.acquire()
            Log.d("MainActivity", "Wake lock acquired")
        } catch (e: Exception) {
            Log.e("mainActivity", "Failed to acquire wake lock: ${e.message}", e)
        }
    }

    fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        val byteIterator = chunkedSequence(2)
            .map { it.toInt(16).toByte() }
            .iterator()

        return ByteArray(length / 2) { byteIterator.next() }
    }


    private fun getDeviceId(context: Context): String? {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID)
    }

    private fun updateButtonStates(isConnected: Boolean) {
        connectButton.isEnabled = !isConnected
        disconnectButton.isEnabled = isConnected
        changeServerUrlButton.isEnabled = !isConnected
    }


    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun setupButtonListeners() {
        // Connect button
        connectButton.setOnClickListener {
            if (connectStatus.text != "Connected") {
                createWebSocketClient()
                webSocketClient.connect()
                connectStatus.text = "Connecting..."
                updateButtonStates(false)
            }
        }

        // Disconnect button
        disconnectButton.setOnClickListener {
            if (connectStatus.text == "Connected") {
                webSocketClient.close(500, 1000, "user")
                connectStatus.text = "Disconnected"
                updateButtonStates(false)
            }
        }

        // Change server URL button
        changeServerUrlButton.setOnClickListener {
            val editText = EditText(this)
            editText.setText(serverUrl)

            AlertDialog.Builder(this)
                .setTitle("Change Server URL")
                .setView(editText)
                .setPositiveButton("Save") { _, _ ->
                    val newUrl = editText.text.toString().trim()
                    if (newUrl.isNotEmpty()) {
                        serverUrl = newUrl
                        PrefsHelper(this).server = newUrl
                        findViewById<TextView>(R.id.serverUrl).text = "Server: $newUrl"

                        // Reconnect with new URL if previously connected
                        if (connectStatus.text == "Connected") {
                            webSocketClient.close(0, 1000, "user")
                            createWebSocketClient()
                            webSocketClient.connect()
                            connectStatus.text = "Connecting..."
                            updateButtonStates(false)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun createWebSocketClient() {
        val uri: URI?
        try {
            uri = URI("ws://$serverUrl/ws/smsgateway/?device_id="+deviceId)
        } catch (e: URISyntaxException) {
            e.printStackTrace()
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Error").setMessage("Invalid server URL").show()
                connectStatus.text = "Disconnected"
                updateButtonStates(false)
            }
            return
        }

        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen() {
                runOnUiThread {
                    connectStatus.text = "Connected"
                    updateButtonStates(true)
                    if (errorAlert != null && errorAlert?.isShowing == true)
                        errorAlert!!.dismiss()
                    webSocketClient.sendPing(null)
                }
            }

            override fun onTextReceived(message: String?) {
                runOnUiThread {
                    findViewById<TextView>(R.id.lastSms).text = "Last message: $message"
                }
            }

            override fun onBinaryReceived(data: ByteArray?) {
                println("onBinaryReceived")
                data?.let { encryptedData ->
                    try {
                        val decryptedData = decrypt(encryptedData)
                        val decryptedText = String(decryptedData, Charsets.UTF_8)
                        val json = JSONObject(decryptedText)
                        if (json.has("type")) {
                            val type = json.getString("type")
                            if (type == "connect") {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Connection established!", Toast.LENGTH_LONG).show()
                                }
                            } else if (type == "sms") {
                                // Send sms
                                val phoneNum = json.getString("phone")
                                val sms = json.getString("sms")
                                runOnUiThread {
                                    findViewById<TextView>(R.id.lastSms).text = "Last SMS: ${sms} -> ${phoneNum}"
                                    sendSms(phoneNum, sms)
                                }
                            }
                        } else {
                            throw Exception("Invalid payload")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("MainActivity", "Decryption failed", e)
                        runOnUiThread {
                            webSocketClient.close(500, 1000, "user")
                            connectStatus.text = "Disconnected"
                            updateButtonStates(false)
                            if (errorAlert != null && errorAlert?.isShowing == true)
                                errorAlert!!.dismiss()
                            errorAlert = AlertDialog.Builder(this@MainActivity)
                                .setTitle("Encryption error")
                                .setMessage(e.message + "\n Please make sure encryption key is correct in OpenCARWINGS SMS Provider settings!")
                                .show()
                        }
                    }
                }
            }

            override fun onPingReceived(data: ByteArray?) {
                println("onPingReceived")
            }

            override fun onPongReceived(data: ByteArray?) {
                println("onPongReceived")
                runOnUiThread {
                    val currentTime = SimpleDateFormat("dd/M/yyyy hh:mm:ss").format(Date())
                    findViewById<TextView>(R.id.lastPing).text = "Last ping: $currentTime"
                }
            }

            override fun onException(e: Exception) {
                if (e.message == "Unexpected end of stream") {
                    connectStatus.text = "Disconnected, reconnecting.."
                    updateButtonStates(false)
                    return
                }
                runOnUiThread {
                    e.printStackTrace()
                    if (errorAlert != null && errorAlert?.isShowing == true)
                        errorAlert!!.dismiss()
                    errorAlert = AlertDialog.Builder(this@MainActivity)
                        .setTitle("Error")
                        .setMessage(e.message)
                        .show()
                    connectStatus.text = "Disconnected"
                    updateButtonStates(false)
                }
            }

            override fun onCloseReceived(code: Int, reason: String?) {
                runOnUiThread {
                    println(code)
                    if (reason == "user" || code == 1000) {
                        connectStatus.text = "Disconnected"
                        updateButtonStates(false)
                        return@runOnUiThread
                    }
                    connectStatus.text = "Disconnected, reconnecting..."
                    updateButtonStates(false)
                    createWebSocketClient()
                    webSocketClient.connect()
                }
            }
        }

        webSocketClient.addHeader("X-Device-Id", deviceId)
        webSocketClient.setConnectTimeout(10000)
        webSocketClient.setReadTimeout(60000)
        webSocketClient.enableAutomaticReconnection(5000)
    }


    private fun decrypt(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < 32) {
            throw IllegalArgumentException("Encrypted data too short (must be at least 32 bytes for IV + ciphertext)")
        }
        if (encryptedData.size % 16 != 0) {
            throw IllegalArgumentException("Encrypted data length (${encryptedData.size} bytes) must be a multiple of 16")
        }

        val iv = encryptedData.copyOfRange(0, 16)
        val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(encryptionKey, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        return cipher.doFinal(ciphertext)
    }

    override fun onResume() {
        super.onResume()
        try {
            webSocketClient.sendPing(null)
        } catch (ignored: Exception) {ignored.printStackTrace()}
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient.close(0, 1000, "user")
        // Clear screen on flag when activity is destroyed
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            val sms = SmsManager.getDefault()
            val parts: ArrayList<String> = sms.divideMessage(message)

            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()

            sms.sendMultipartTextMessage(
                phoneNumber, null, parts, sentIntents, deliveryIntents
            )
            Log.d("MainActivity", "SMS queued")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "SMS error", e)
            false
        }
    }


    // ---------- RUNTIME PERMISSION ----------
    private fun requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 101)
            }
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.i("MainActivity", "SEND_SMS granted")
        } else {
            Log.w("MainActivity", "SEND_SMS denied")
            Toast.makeText(this@MainActivity, "SMS Denied, cannot send SMS! Please allow in settings.", Toast.LENGTH_LONG).show()
        }
    }

}