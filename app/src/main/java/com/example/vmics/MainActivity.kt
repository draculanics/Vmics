package com.example.vmics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.vmics.ui.theme.VmicsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin


class MainActivity : ComponentActivity() {

    private var udpPort = 50005
    private val discoveryIdentifier = "VMICS_DISCOVERY"
    private var isStreaming = mutableStateOf(false)
    private var isSpeakerOn = mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<String>()
    private var isReceiving = false
    private val targetAddress: InetAddress = InetAddress.getByName("255.255.255.255") // Replace with actual target address
    private val beep = generateBeepSound()


    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Permissions not granted. The app may not function correctly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onResume() {
        super.onResume()
        if (!hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val localIp = getLocalIpAddress()

        if (!hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET
                )
            )
        }

        startDiscoveryResponder()
        discoverDevices()
        

        setContent {

            VmicsTheme {
                MicSpeakerControls(

                    isStreaming = isStreaming.value,
                    isSpeakerOn = isSpeakerOn.value,
                    discoveredDevices = discoveredDevices,
                    localIp = localIp,
                    udpPort = udpPort,
                    onToggleStreaming = { toggleStreaming() },
                    onToggleSpeaker = { toggleSpeaker() },
                    onPortChanged = { _ -> restartUdpStreaming() },
                    onDiscoverDevices = { discoverDevices() }
                )
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.INTERNET
        ).all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleStreaming() {
        if (hasPermissions()) {
            if (!isStreaming.value) {
                isStreaming.value = true
                // Send the beep sound over UDP
                sendBeepSound(targetAddress, udpPort, beep)
                startUdpStreaming() // Start only when user presses the mic button
            } else {
                isStreaming.value = false
                // No need to start UDP streaming when turning off
            }
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
        }
    }




    private fun toggleSpeaker() {
        // Check if permissions are granted to access audio features
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
            return
        }

        // Get the AudioManager system service to control audio settings
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // If the speaker is not currently on, turn it on
        if (!isSpeakerOn.value) {
            CoroutineScope(Dispatchers.IO).launch {
                // Start receiving audio over UDP (this will handle the streaming process)
                val success = startUdpReceiving()  // This should handle UDP audio receiving

                // Update UI on the main thread
                runOnUiThread {
                    if (success) {
                        // For Android 12+ (API level 31+), use setCommunicationDevice()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Set the communication device to the built-in speaker
                            val device = audioManager.availableCommunicationDevices
                                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            device?.let {
                                audioManager.setCommunicationDevice(it)
                            }
                        } else {
                            // For older Android versions, use the deprecated method to enable speakerphone
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = true
                        }

                        // Mark the speaker as being on and update UI
                        isSpeakerOn.value = true
                        Toast.makeText(this@MainActivity, "Speaker On", Toast.LENGTH_SHORT).show()
                    } else {
                        // If starting UDP receiving fails, show a failure message
                        Toast.makeText(this@MainActivity, "Failed to start speaker", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            // If the speaker is already on, turn it off
            stopUdpReceiving()  // Stop audio streaming

            // For Android 12+, reset the communication device to the default
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                // For older Android versions, disable speakerphone
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }

            // Mark the speaker as being off and update UI
            isSpeakerOn.value = false
            Toast.makeText(this@MainActivity, "Speaker Off", Toast.LENGTH_SHORT).show()
        }
    }



    // New function to properly stop receiving audio
    private fun stopUdpReceiving() {
        isSpeakerOn.value = false
        isReceiving = false // Ensures next start works
        Toast.makeText(this@MainActivity, "Speaker Off", Toast.LENGTH_SHORT).show()
    }

    private fun generateBeepSound(): ShortArray {
        val sampleRate = 44100
        val durationMs = 200  // Beep duration in milliseconds
        val numSamples = (durationMs / 1000.0 * sampleRate).toInt()
        val generatedSound = ShortArray(numSamples)

        val frequency = 1000.0 // Hz (beep frequency)
        for (i in generatedSound.indices) {
            val angle = 2.0 * Math.PI * i * frequency / sampleRate
            generatedSound[i] = (Short.MAX_VALUE * sin(angle)).toInt().toShort()
        }
        return generatedSound
    }

    private fun sendBeepSound(targetAddress: InetAddress, udpPort: Int, beep: ShortArray) {
        // Convert ShortArray to ByteArray
        val byteArray = ByteArray(beep.size * 2)  // 2 bytes per Short
        for (i in beep.indices) {
            byteArray[i * 2] = (beep[i].toInt() shr 8).toByte()   // High byte
            byteArray[i * 2 + 1] = (beep[i].toInt() and 0xFF).toByte()  // Low byte
        }

        // Create the DatagramPacket with the converted ByteArray
        val packet = DatagramPacket(byteArray, byteArray.size, targetAddress, udpPort)

        // Send the packet via UDP
        val socket = DatagramSocket()
        socket.send(packet)
        socket.close()
    }

    private fun startListeningForBeep() {
        // Start listening for UDP packets (containing the beep sound)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(udpPort)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isReceiving && isSpeakerOn.value) {
                    socket.receive(packet)

                    // Convert byte data to ShortArray
                    val shortArray = byteArrayToShortArray(packet.data)

                    // Check if it's a beep (you could implement a more sophisticated check)
                    if (isBeep(shortArray)) {
                        playBeepSound(shortArray)
                    }
                }
            } catch (e: Exception) {
                Log.e("BeepListener", "Error: ${e.message}")
            }
        }
    }

    private fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
    }

    private fun isBeep(shortArray: ShortArray): Boolean {
        // Implement a method to check if the received sound is a beep (e.g., check frequency or pattern)
        // For simplicity, you might check if the amplitude exceeds a threshold or other pattern characteristics.
        return shortArray.isNotEmpty() // Replace with actual logic
    }

    private fun playBeepSound(beep: ShortArray) {
        val bufferSize = beep.size * 2 // Each Short takes 2 bytes

        // Use AudioTrack or any suitable method to play the beep sound
        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize) // 2 bytes per Short
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()
        audioTrack.write(beep, 0, beep.size)
        audioTrack.stop()
        audioTrack.release()
    }


    private fun startDiscoveryResponder() {
        CoroutineScope(Dispatchers.IO).launch {

            try {
                val socket = DatagramSocket(udpPort)
                val buffer = ByteArray(1024)

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)

                    if (message == discoveryIdentifier) {
                        val responsePacket = DatagramPacket(
                            discoveryIdentifier.toByteArray(),
                            discoveryIdentifier.length,
                            packet.address,
                            packet.port
                        )
                        socket.send(responsePacket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun discoverDevices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val message = discoveryIdentifier.toByteArray()

                val packet = DatagramPacket(message, message.size, broadcastAddress, udpPort)
                socket.send(packet)

                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)

                socket.soTimeout = 2000 // Timeout for responses
                while (true) {
                    try {
                        socket.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length)

                        if (response == discoveryIdentifier) {
                            val deviceIp = responsePacket.address.hostAddress
                            if (!deviceIp?.let { discoveredDevices.contains(it) }!!) {
                                discoveredDevices.add(deviceIp)
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "No Network"
        } catch (e: Exception) {
            "No Network"
        }
    }




    private fun startLocalAudioLoopback() {
        if (!hasPermissions()) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission to record audio is required", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) return@launch

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )

                val audioTrack = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioRecord.startRecording()
                audioTrack.play()

                val buffer = ByteArray(bufferSize)
                while (isStreaming.value) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read)
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                audioTrack.stop()
                audioTrack.release()

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Loopback error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }




    private fun isNetworkAvailable(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            interfaces?.toList()?.any { it.isUp && !it.isLoopback } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun startUdpStreaming() {
        if (!hasPermissions()) return

        if (!isNetworkAvailable()) {
            runOnUiThread {
                Toast.makeText(this, "No network found, using to local loopback.", Toast.LENGTH_SHORT).show()
            }
            // No network detected, start local loopback
            startLocalAudioLoopback()
            return
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            2048 // Ensure buffer is at least 2048 bytes
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission to record audio is required", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {

            try{
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val socket = DatagramSocket()
                socket.broadcast = true
                val targetAddress = InetAddress.getByName("255.255.255.255")

                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)

                // Start listening for beep when mic is turned on
                startListeningForBeep()

                while (isStreaming.value) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val packet = DatagramPacket(buffer, read, targetAddress, udpPort)
                        socket.send(packet)
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                socket.close()
            } catch (e: SecurityException) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Permission denied: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        if (isSpeakerOn.value) {
            CoroutineScope(Dispatchers.IO).launch { startUdpReceiving() }
        }
    }

    private fun restartUdpStreaming() {
        isStreaming.value = false
        startUdpStreaming()
    }

    private fun startUdpReceiving(): Boolean {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
            return false
        }

        if (isReceiving) {
            Toast.makeText(this, "Already receiving audio", Toast.LENGTH_SHORT).show()
            return false
        }

        isReceiving = true

        CoroutineScope(Dispatchers.IO).launch {
            var audioTrack: AudioTrack? = null
            var socket: DatagramSocket? = null

            try {
                val bufferSize = AudioTrack.getMinBufferSize(
                    44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                socket = DatagramSocket(udpPort)
                val buffer = ByteArray(bufferSize)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isReceiving && isSpeakerOn.value) {
                    socket.receive(packet)

                    // Convert received ByteArray to ShortArray
                    val shortArray = byteArrayToShortArray(packet.data)

                    // Play the beep sound if it's a valid beep
                    if (isBeep(shortArray)) {
                        CoroutineScope(Dispatchers.Main).launch { playBeepSound(shortArray) }
                    }

                    // Play received audio data
                    audioTrack.write(shortArray, 0, shortArray.size)
                    // Optional: Add a delay to reduce CPU usage if needed
                    yield()

                }
            } catch (e: Exception) {
                Log.e("UDPReceiver", "Error: ${e.message}")
            } finally {
                isReceiving = false
                audioTrack?.run {
                    stop()
                    release()
                }
                socket?.close()
            }
        }

        return true
    }




}

@Composable
fun MicSpeakerControls(

    isStreaming: Boolean,
    isSpeakerOn: Boolean,
    discoveredDevices: List<String>,
    localIp: String,
    udpPort: Int,
    onToggleStreaming: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDiscoverDevices: () -> Unit,
    onPortChanged: (Int) -> Unit // Added this parameter


) {
    val udpPortText = remember { mutableStateOf(udpPort.toString()) }
    val isValidPort = udpPortText.value.toIntOrNull() != null



    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display local IP
        Text(text = "IP: $localIp", modifier = Modifier.padding(8.dp))

        // Display discovered devices
        Text(text = "Other Devices:", modifier = Modifier.padding(8.dp))
        discoveredDevices.forEach { ip ->
            Text(text = ip, modifier = Modifier.padding(start = 16.dp))
        }

        // UDP Port display and modify
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Port: ",
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            TextField(
                value = udpPortText.value,
                onValueChange = {
                    udpPortText.value = it
                },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .width(100.dp)
            )
            Button(
                onClick = {
                    val newPort = udpPortText.value.toIntOrNull() ?: udpPort
                    onPortChanged(newPort)
                },
                modifier = Modifier.align(Alignment.CenterVertically) ,
                enabled = isValidPort
            ) {
                Text(text = "Change Port")
            }
        }

        // Discover devices button
        Button(
            onClick = onDiscoverDevices,
            modifier = Modifier
                .padding(top = 16.dp)
                .size(150.dp, 60.dp)
        ) {
            Text(text = "Discover Devices")
        }


        // Mic toggle button
        Button(
            onClick = {
                try {
                    onToggleStreaming()  // Call function inside try-catch
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("MicToggle", "Error: ${e.message}")
                }
            },
            modifier = Modifier
                .padding(top = 16.dp)
                .size(150.dp, 60.dp)
        ) {
            Text(text = if (isStreaming) "Stop Mic" else "Start Mic")
        }



        // Speaker toggle button
        Button(
            onClick = onToggleSpeaker,
            modifier = Modifier
                .padding(top = 16.dp)
                .size(150.dp, 60.dp)
        ) {
            Text(text = if (isSpeakerOn) "Stop Speaker" else "Start Speaker")
        }

        // UDP streaming switch
        Switch(
            checked = isStreaming,
            onCheckedChange = { onToggleStreaming() },
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(text = "UDP Streaming ${if (isStreaming) "Enabled" else "Disabled"}")
    }
}
