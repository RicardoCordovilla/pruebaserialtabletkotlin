package com.example.pruebaserial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReceivedData: TextView
    private lateinit var etSendData: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSend: Button
    private lateinit var btnSend1: Button
    private lateinit var btnSend2: Button
    private lateinit var btnSend3: Button

    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isConnected = false
    private var receivedDataBuilder = StringBuilder()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.pruebaserial.USB_PERMISSION"
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    } else {
                        showStatus("Permiso USB denegado", false)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Registrar receiver para permisos USB
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        // Buscar dispositivos al iniciar
        findUsbDevices()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvReceivedData = findViewById(R.id.tvReceivedData)
        etSendData = findViewById(R.id.etSendData)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSend = findViewById(R.id.btnSend)
        btnSend1 = findViewById(R.id.btnSend1)
        btnSend2 = findViewById(R.id.btnSend2)
        btnSend3 = findViewById(R.id.btnSend3)

        updateButtonStates()
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener { findUsbDevices() }
        btnDisconnect.setOnClickListener { disconnectSerial() }
        btnSend.setOnClickListener { sendData(etSendData.text.toString()) }

        // Botones de comandos predefinidos
        btnSend1.setOnClickListener { sendData("CMD1") }
        btnSend2.setOnClickListener { sendData("CMD2") }
        btnSend3.setOnClickListener { sendData("CMD3") }
    }

    private fun findUsbDevices() {
        val deviceList = usbManager?.deviceList

        if (deviceList.isNullOrEmpty()) {
            showStatus("No se encontraron dispositivos USB", false)
            return
        }

        // Buscar dispositivo con interfaz CDC (Communication Device Class) o similar
        for (device in deviceList.values) {
            // Buscar interfaces de comunicación serial
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                    usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM) {

                    if (usbManager?.hasPermission(device) == true) {
                        connectToDevice(device)
                        return
                    } else {
                        requestUsbPermission(device)
                        return
                    }
                }
            }
        }

        // Si no encuentra CDC, intentar con el primer dispositivo disponible
        val firstDevice = deviceList.values.firstOrNull()
        firstDevice?.let { device ->
            if (usbManager?.hasPermission(device) == true) {
                connectToDevice(device)
            } else {
                requestUsbPermission(device)
            }
        } ?: showStatus("No se encontraron dispositivos compatibles", false)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager?.requestPermission(device, permissionIntent)
    }

    private fun connectToDevice(device: UsbDevice) {
        usbConnection = usbManager?.openDevice(device)
        if (usbConnection == null) {
            showStatus("No se pudo abrir la conexión USB", false)
            return
        }

        // Buscar la primera interfaz disponible
        if (device.interfaceCount == 0) {
            showStatus("El dispositivo no tiene interfaces disponibles", false)
            return
        }

        usbInterface = device.getInterface(0)
        if (usbConnection?.claimInterface(usbInterface, true) != true) {
            showStatus("No se pudo reclamar la interfaz USB", false)
            return
        }

        // Buscar endpoints de entrada y salida
        for (i in 0 until usbInterface!!.endpointCount) {
            val endpoint = usbInterface!!.getEndpoint(i)
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> endpointIn = endpoint
                UsbConstants.USB_DIR_OUT -> endpointOut = endpoint
            }
        }

        if (endpointIn == null || endpointOut == null) {
            showStatus("No se encontraron endpoints de comunicación", false)
            return
        }

        usbDevice = device
        isConnected = true
        showStatus("Conectado a ${device.deviceName}", true)

        // Iniciar lectura de datos
        startReadingData()
    }

    private fun startReadingData() {
        if (!isConnected || endpointIn == null) return

        executor.execute {
            val buffer = ByteArray(64) // Buffer típico para comunicación serial USB
            while (isConnected && usbConnection != null) {
                try {
                    val bytesRead = usbConnection!!.bulkTransfer(endpointIn, buffer, buffer.size, 1000)
                    if (bytesRead > 0) {
                        val receivedData = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        mainHandler.post {
                            appendReceivedData(receivedData)
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        showStatus("Error leyendo datos: ${e.message}", false)
                    }
                    break
                }
            }
        }
    }

    private fun sendData(data: String) {
        if (!isConnected || endpointOut == null || usbConnection == null) {
            Toast.makeText(this, "No hay conexión serial", Toast.LENGTH_SHORT).show()
            return
        }

        if (data.isEmpty()) {
            Toast.makeText(this, "Ingrese datos para enviar", Toast.LENGTH_SHORT).show()
            return
        }

        executor.execute {
            try {
                val dataToSend = "$data\r\n".toByteArray(Charsets.UTF_8)
                val result = usbConnection!!.bulkTransfer(endpointOut, dataToSend, dataToSend.size, 1000)

                mainHandler.post {
                    if (result >= 0) {
                        Toast.makeText(this@MainActivity, "Datos enviados: $data", Toast.LENGTH_SHORT).show()
                        appendReceivedData(">> Enviado: $data\n")
                        etSendData.setText("")
                    } else {
                        Toast.makeText(this@MainActivity, "Error enviando datos", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "Error enviando datos: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun disconnectSerial() {
        isConnected = false

        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            // Ignorar errores al cerrar
        }

        usbDevice = null
        usbConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null

        showStatus("Desconectado", false)
    }

    private fun showStatus(message: String, connected: Boolean) {
        tvStatus.text = "Estado: $message"
        tvStatus.setBackgroundColor(
            if (connected) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
        )
        isConnected = connected
        updateButtonStates()
    }

    private fun updateButtonStates() {
        btnConnect.isEnabled = !isConnected
        btnDisconnect.isEnabled = isConnected
        btnSend.isEnabled = isConnected
        btnSend1.isEnabled = isConnected
        btnSend2.isEnabled = isConnected
        btnSend3.isEnabled = isConnected
        etSendData.isEnabled = isConnected
    }

    private fun appendReceivedData(data: String) {
        receivedDataBuilder.append(data)
        tvReceivedData.text = receivedDataBuilder.toString()

        // Mantener solo los últimos 2000 caracteres para evitar consumo excesivo de memoria
        if (receivedDataBuilder.length > 2000) {
            receivedDataBuilder.delete(0, receivedDataBuilder.length - 2000)
            tvReceivedData.text = receivedDataBuilder.toString()
        }

        // Auto-scroll al final
        val scrollView = tvReceivedData.parent as? android.widget.ScrollView
        scrollView?.post {
            scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        disconnectSerial()
        executor.shutdown()
    }
}