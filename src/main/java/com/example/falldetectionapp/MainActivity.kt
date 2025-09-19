package com.example.falldetectionapp


import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

// Data class to hold contact information
data class Contact(val name: String, val number: String) {
    // This controls how the contact is displayed in the ListView
    override fun toString(): String {
        return "$name\n$number"
    }
}

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnCancelAlarm: Button
    private lateinit var btnAddContact: Button
    private lateinit var listViewContacts: ListView
    private lateinit var cardStatus: CardView

    // Bluetooth
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // State & Handlers
    private val handler = Handler(Looper.getMainLooper())
    private var isAlarmActive = false
    private var smsSentCount = 0

    // Contacts
    private lateinit var sharedPreferences: SharedPreferences
    private val emergencyContacts = mutableListOf<Contact>()
    private lateinit var contactsAdapter: ArrayAdapter<Contact>

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // SMS Delivery Report
    private var smsSentReceiver: BroadcastReceiver? = null
    private var smsDeliveredReceiver: BroadcastReceiver? = null


    companion object {
        private const val DEVICE_NAME = "ESP32-FallBand"
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREFS_NAME = "FallDetectionPrefs"
        private const val CONTACTS_KEY = "EmergencyContacts"
        private const val PERMISSION_REQUEST_CODE = 101
        private const val SMS_SENT_ACTION = "com.example.falldetectionapp.SMS_SENT"
        private const val SMS_DELIVERED_ACTION = "com.example.falldetectionapp.SMS_DELIVERED"
        private const val TAG = "FallDetectionApp"
    }

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { contactUri ->
                val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
                val cursor = contentResolver.query(contactUri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                        if (nameIndex >= 0 && numberIndex >= 0) {
                            val name = it.getString(nameIndex)
                            // Sanitize the number to remove spaces, dashes, etc.
                            val number = it.getString(numberIndex).replace(Regex("[^0-9+]"), "")
                            if (name.isNotBlank() && number.isNotBlank()) {
                                addContact(Contact(name, number))
                            } else {
                                showToast("Could not retrieve contact details.")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        initializeBluetooth()
        initializeContacts()
        initializeLocation()
        setupSmsReceivers()

        btnConnect.setOnClickListener { checkPermissionsAndConnect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnCancelAlarm.setOnClickListener { sendCancelCommand() }
        btnAddContact.setOnClickListener { pickContact() }

        updateUI(ConnectionState.DISCONNECTED)
    }

    private fun initializeUI() {
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvStatus = findViewById(R.id.tv_status)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnCancelAlarm = findViewById(R.id.btn_cancel_alarm)
        btnAddContact = findViewById(R.id.btn_add_contact)
        listViewContacts = findViewById(R.id.list_view_contacts)
        cardStatus = findViewById(R.id.card_status)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun initializeContacts() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        contactsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<Contact>())
        listViewContacts.adapter = contactsAdapter
        listViewContacts.setOnItemLongClickListener { _, _, position, _ ->
            val selectedContact = contactsAdapter.getItem(position)
            if (selectedContact != null) {
                showDeleteContactDialog(selectedContact)
            }
            true
        }
        loadContacts()
    }

    private fun initializeLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    // --- Bluetooth Logic ---
    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        if (!bluetoothAdapter.isEnabled) {
            showToast("Please enable Bluetooth")
            return
        }
        updateUI(ConnectionState.CONNECTING)
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val device = pairedDevices?.find { it.name == DEVICE_NAME }

        if (device == null) {
            showToast("$DEVICE_NAME not found in paired devices.")
            updateUI(ConnectionState.DISCONNECTED)
            return
        }

        Thread {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream
                handler.post {
                    updateUI(ConnectionState.CONNECTED)
                    showToast("Connected to $DEVICE_NAME")
                }
                startListening()
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                handler.post {
                    showToast("Connection failed")
                    updateUI(ConnectionState.DISCONNECTED)
                }
            }
        }.start()
    }

    private fun startListening() {
        Thread {
            val buffer = ByteArray(1024)
            var bytes: Int
            while (bluetoothSocket?.isConnected == true) {
                try {
                    bytes = inputStream?.read(buffer) ?: break
                    val readMessage = String(buffer, 0, bytes)
                    // Process messages line by line to handle concatenated messages
                    readMessage.split("\n").forEach { line ->
                        if (line.isNotBlank()) {
                            handler.post { processMessage(line) }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from input stream", e)
                    handler.post { disconnect() }
                    break
                }
            }
        }.start()
    }

    private fun processMessage(message: String) {
        val trimmedMessage = message.trim()
        Log.d(TAG, "Received: $trimmedMessage")

        if (trimmedMessage.contains("FALL DETECTED")) {
            if (!isAlarmActive) { // Prevent re-triggering if already active
                isAlarmActive = true
                smsSentCount = 0
                updateUIForAlarm()
                tvStatus.text = "Status: Fall Detected! Awaiting cancellation..."

                // 10-second delay before sending the first SMS
                handler.postDelayed({
                    if (isAlarmActive && smsSentCount == 0) {
                        val initialMessage = "URGENT: A potential fall has been detected for the wearer."
                        requestLocationAndSendSms(initialMessage)
                    }
                }, 10000)
            }
        } else if (trimmedMessage.contains("CANCELLED")) {
            isAlarmActive = false
            handler.removeCallbacksAndMessages(null) // Cancel any pending SMS
            updateUIForAlarm()
            tvStatus.text = "Status: Alarm Cancelled by User."
        } else if (trimmedMessage.contains("POSSIBLE FREE FALL")) {
            tvStatus.text = "Status: Possible fall detected, monitoring impact..."
        }
    }

    private fun sendCancelCommand() {
        if (outputStream != null) {
            Thread {
                try {
                    outputStream?.write("CANCEL_ALARM\n".toByteArray())
                    handler.post { showToast("Cancel command sent.") }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send cancel command", e)
                    handler.post { showToast("Failed to send cancel command.") }
                }
            }.start()
        } else {
            showToast("Not connected.")
        }
    }

    private fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing Bluetooth socket", e)
        } finally {
            isAlarmActive = false
            updateUI(ConnectionState.DISCONNECTED)
            showToast("Disconnected")
        }
    }

    // --- UI and State Management ---
    private enum class ConnectionState { CONNECTED, DISCONNECTED, CONNECTING }

    private fun updateUI(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> {
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                tvConnectionStatus.text = "Connected to $DEVICE_NAME"
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                tvStatus.text = "Status: Monitoring..."
            }
            ConnectionState.DISCONNECTED -> {
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                tvConnectionStatus.text = "Disconnected"
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                tvStatus.text = "Status: -"
                isAlarmActive = false
                updateUIForAlarm()
            }
            ConnectionState.CONNECTING -> {
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = false
                tvConnectionStatus.text = "Connecting..."
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
            }
        }
    }

    private fun updateUIForAlarm() {
        btnCancelAlarm.visibility = if (isAlarmActive) View.VISIBLE else View.GONE
        if (isAlarmActive) {
            cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.red_light))
        } else {
            cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    // --- Contact Management ---
    private fun pickContact() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_CODE)
        } else {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        }
    }

    private fun showDeleteContactDialog(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("Delete Contact")
            .setMessage("Are you sure you want to delete ${contact.name}?")
            .setPositiveButton("Delete") { _, _ -> deleteContact(contact) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addContact(contact: Contact) {
        if (emergencyContacts.none { it.number == contact.number }) {
            emergencyContacts.add(contact)
            saveAndRefresh()
            showToast("Contact added: ${contact.name}")
        } else {
            showToast("A contact with this number already exists.")
        }
    }

    private fun deleteContact(contact: Contact) {
        emergencyContacts.remove(contact)
        saveAndRefresh()
        showToast("Contact deleted")
    }

    private fun saveAndRefresh() {
        val jsonArray = JSONArray()
        emergencyContacts.forEach {
            val jsonObject = JSONObject()
            jsonObject.put("name", it.name)
            jsonObject.put("number", it.number)
            jsonArray.put(jsonObject)
        }
        sharedPreferences.edit().putString(CONTACTS_KEY, jsonArray.toString()).apply()
        refreshUiList()
    }

    private fun loadContacts() {
        val jsonString = sharedPreferences.getString(CONTACTS_KEY, null)
        emergencyContacts.clear()
        if (!jsonString.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val name = jsonObject.getString("name")
                    val number = jsonObject.getString("number")
                    emergencyContacts.add(Contact(name, number))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved contacts", e)
                showToast("Error parsing saved contacts.")
            }
        }
        refreshUiList()
    }

    private fun refreshUiList() {
        contactsAdapter.clear()
        contactsAdapter.addAll(emergencyContacts)
        contactsAdapter.notifyDataSetChanged()
    }

    // --- SMS and Location Logic ---
    private fun setupSmsReceivers() {
        // Receiver for when the SMS is SENT
        smsSentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK -> Log.i(TAG, "SMS sent successfully.")
                    else -> Log.e(TAG, "SMS send failed with code: $resultCode")
                }
            }
        }

        // Receiver for when the SMS is DELIVERED
        smsDeliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK -> showToast("SMS delivered.")
                    Activity.RESULT_CANCELED -> showToast("SMS not delivered.")
                }
            }
        }

        // Register receivers correctly based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION), RECEIVER_NOT_EXPORTED)
            registerReceiver(smsDeliveredReceiver, IntentFilter(SMS_DELIVERED_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION))
            registerReceiver(smsDeliveredReceiver, IntentFilter(SMS_DELIVERED_ACTION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationAndSendSms(baseMessage: String) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                var finalMessage = baseMessage
                if (location != null) {
                    val lat = location.latitude
                    val long = location.longitude
                    finalMessage += "\n\nLast known location:\nLat: $lat\nLong: $long"
                    finalMessage += "\n\nView on map: https://maps.google.com/?q=$lat,$long"
                } else {
                    finalMessage += "\n\nLocation data was not available."
                }
                sendEmergencySms(finalMessage)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location", e)
                val finalMessage = "$baseMessage\n\nCould not retrieve location."
                sendEmergencySms(finalMessage)
            }
    }

    private fun sendEmergencySms(message: String) {
        if (emergencyContacts.isEmpty()) {
            showToast("No emergency contacts to notify!")
            return
        }

        try {
            val smsManager = getSystemService(SmsManager::class.java)
            // FIX: Use multipart sending for reliability with long messages
            val parts = smsManager.divideMessage(message)

            var messagesSent = 0
            emergencyContacts.forEach { contact ->
                try {
                    Log.i(TAG, "Sending multipart SMS to: ${contact.name} (${contact.number})")
                    showToast("Sending alert to: ${contact.name}")

                    val sentIntents = ArrayList<PendingIntent>()
                    for (i in parts.indices) {
                        val sentIntent = PendingIntent.getBroadcast(this, i, Intent(SMS_SENT_ACTION), PendingIntent.FLAG_IMMUTABLE)
                        sentIntents.add(sentIntent)
                    }

                    val deliveredIntents = ArrayList<PendingIntent>()
                    for (i in parts.indices) {
                        val deliveredIntent = PendingIntent.getBroadcast(this, i, Intent(SMS_DELIVERED_ACTION), PendingIntent.FLAG_IMMUTABLE)
                        deliveredIntents.add(deliveredIntent)
                    }

                    smsManager.sendMultipartTextMessage(contact.number, null, parts, sentIntents, deliveredIntents)
                    messagesSent++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send SMS to ${contact.name}", e)
                    showToast("Failed to send to ${contact.name}. Check number format.")
                }
            }

            if (messagesSent > 0) {
                smsSentCount++
                if (isAlarmActive && smsSentCount == 1) {
                    handler.postDelayed({
                        if (isAlarmActive && smsSentCount == 1) {
                            val followUpMessage = "FOLLOW-UP: This is a second alert. A fall was detected 1 minute ago. Please respond."
                            requestLocationAndSendSms(followUpMessage)
                        }
                    }, 60000) // 60 seconds
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS Manager failed. Check app permissions.", e)
            showToast("SMS Manager failed. Check app permissions in phone settings.")
        }
    }

    // --- Permissions ---
    private fun checkPermissionsAndConnect() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            connectToDevice()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showToast("All permissions granted. Ready to connect.")
            } else {
                showToast("Some permissions were denied. The app may not function correctly.")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receivers to avoid memory leaks
        smsSentReceiver?.let { unregisterReceiver(it) }
        smsDeliveredReceiver?.let { unregisterReceiver(it) }
        handler.removeCallbacksAndMessages(null)
        disconnect()
    }
}

