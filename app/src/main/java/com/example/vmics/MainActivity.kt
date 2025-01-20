package com.example.vmics

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private var udpPort = 50005
    private val discoveryIdentifier = "VMICS_DISCOVERY"
    private var isStreaming = mutableStateOf(false)
    private var isSpeakerOn = mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<String>()
    private var isReceiving = false

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

        val localIp = getLocalIpAddress() ?: "Unknown"

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
        if (hasPermissions()) {
            CoroutineScope(Dispatchers.IO).launch {
                val success = startUdpReceiving()
                runOnUiThread {
                    if (success) {
                        isSpeakerOn.value = true
                        Toast.makeText(this@MainActivity, "Speaker On", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to start speaker", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
        }
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

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    // Check if the address is not loopback and is an IPv4 address
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }


    private fun startLocalAudioLoopback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission to record audio is required", Toast.LENGTH_LONG).show()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val audioTrack = AudioTrack.Builder()
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
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            // No network detected, start local loopback
            startLocalAudioLoopback()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
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
            var receivedAudio = false

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
                        44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                    ))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                socket = DatagramSocket(udpPort)
                val buffer = ByteArray(1024)

                val startTime = System.currentTimeMillis()
                while (isSpeakerOn.value && isReceiving) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        if (packet.length > 0) {
                            receivedAudio = true
                            audioTrack.write(packet.data, 0, packet.length)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                    if (!receivedAudio && System.currentTimeMillis() - startTime > 5000) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                modifier = Modifier.align(Alignment.CenterVertically)
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
            onClick = { onToggleStreaming() },  // Directly toggle streaming
            modifier = Modifier
                .padding(top = 16.dp)
                .size(150.dp, 60.dp)
        ) {
            Text(text = if (isStreaming) "Stop Mic" else "Start Mic")  // Correct label update
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
