package com.codewithdanu.deviceagent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var etDeviceId: EditText
    private lateinit var etDeviceToken: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var cameraExecutor: ExecutorService
    
    // Scanner UI elements
    private lateinit var prefs: SharedPreferences
    private var scannerView: PreviewView? = null
    private var scannerContainer: FrameLayout? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required to scan QR", Toast.LENGTH_LONG).show()
        }
    }

    private val requestServicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineGranted || coarseGranted) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "Location permission is required for monitoring", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        prefs = getSharedPreferences(AgentConfig.PREFS_NAME, Context.MODE_PRIVATE)

        // Main Layout
        val root = FrameLayout(this)
        
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFFF8FAFC.toInt()) // Slate 50
        }

        val title = TextView(this).apply {
            text = "Agent Setup"
            textSize = 28f
            setTextColor(0xFF1E293B.toInt()) // Slate 800
            setPadding(0, 0, 0, 16)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_devices, 0, 0, 0)
            compoundDrawablePadding = 32
        }

        val subtitle = TextView(this).apply {
            text = "Configure your monitoring node"
            textSize = 14f
            setTextColor(0xFF64748B.toInt()) // Slate 500
            setPadding(0, 0, 0, 64)
        }

        etServerUrl = EditText(this).apply {
            hint = "Server URL (e.g. http://192.168.1.5:3000)"
            setText(prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.SERVER_URL))
        }

        etDeviceId = EditText(this).apply {
            hint = "Device ID"
            setText(prefs.getString(AgentConfig.KEY_DEVICE_ID, ""))
        }

        etDeviceToken = EditText(this).apply {
            hint = "Device Token"
            setText(prefs.getString(AgentConfig.KEY_DEVICE_TOKEN, ""))
        }

        val btnScan = Button(this).apply {
            text = "Scan Setup QR"
            setBackgroundColor(0xFF6366F1.toInt()) // Indigo 500
            setTextColor(0xFFFFFFFF.toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_qr_code_scanner, 0, 0, 0)
            compoundDrawablePadding = 24
            setPadding(48, 0, 48, 0)
            setOnClickListener { checkCameraPermission() }
        }

        val btnSave = Button(this).apply {
            text = "Save Configuration"
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_save, 0, 0, 0)
            compoundDrawablePadding = 24
            setPadding(48, 0, 48, 0)
            setOnClickListener {
                val server = etServerUrl.text.toString().trim()
                val id     = etDeviceId.text.toString().trim()
                val token  = etDeviceToken.text.toString().trim()

                if (server.isEmpty() || id.isEmpty() || token.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString(AgentConfig.KEY_SERVER_URL, server)
                    .putString(AgentConfig.KEY_DEVICE_ID, id)
                    .putString(AgentConfig.KEY_DEVICE_TOKEN, token)
                    .apply()

                Toast.makeText(this@MainActivity, "Configuration saved!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnStart = Button(this).apply {
            text = "Start Monitoring"
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
            compoundDrawablePadding = 24
            setPadding(48, 0, 48, 0)
            setOnClickListener {
                checkServicePermissionsAndStart()
            }
        }

        val btnStop = Button(this).apply {
            text = "Stop Monitoring"
            styleAsSecondary()
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
            compoundDrawablePadding = 24
            setPadding(48, 0, 48, 0)
            setOnClickListener {
                stopService(Intent(this@MainActivity, AgentService::class.java))
                Toast.makeText(this@MainActivity, "Agent offline", Toast.LENGTH_SHORT).show()
            }
        }

        val btnLockAdmin = Button(this).apply {
            text = "Activate Lock Screen Permission"
            setBackgroundColor(0xFFF59E0B.toInt()) // Amber 500
            setTextColor(0xFF000000.toInt())
            setPadding(48, 0, 48, 0)
            setOnClickListener { requestDeviceAdmin() }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(label("Server URL"))
        layout.addView(etServerUrl)
        layout.addView(label("Device Identity"))
        layout.addView(etDeviceId)
        layout.addView(label("Security Token"))
        layout.addView(etDeviceToken)
        layout.addView(TextView(this).apply { height = 48 }) // Spacer
        layout.addView(btnScan)
        layout.addView(btnSave)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(TextView(this).apply { height = 24 }) // Spacer
        layout.addView(btnLockAdmin)

        scroll.addView(layout)
        root.addView(scroll)

        // Scanner Container (hidden by default)
        scannerContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }
        scannerView = PreviewView(this)
        scannerContainer?.addView(scannerView)
        
        val btnCloseScanner = Button(this).apply {
            text = "Close Scanner"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 100)
            }
            setOnClickListener { stopCamera() }
        }
        scannerContainer?.addView(btnCloseScanner)
        root.addView(scannerContainer)

        setContentView(root)
    }

    private fun label(txt: String) = TextView(this).apply {
        text = txt
        textSize = 10f
        setPadding(4, 32, 0, 8)
        setTextColor(0xFF94A3B8.toInt()) // Slate 400
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun Button.styleAsSecondary() {
        alpha = 0.6f
    }

    private fun checkServicePermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startMonitoringService()
        } else {
            requestServicePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            Toast.makeText(this, "Device Admin already active! Lock screen works.", Toast.LENGTH_LONG).show()
        } else {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to allow the dashboard to remotely lock this device screen."
                )
            }
            startActivity(intent)
        }
    }

    private fun startMonitoringService() {
        val server = prefs.getString(AgentConfig.KEY_SERVER_URL, "") ?: ""
        if (server.isEmpty()) {
            Toast.makeText(this, "Please configure server URL first", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AgentService::class.java))
        Toast.makeText(this, "Monitoring active on $server", Toast.LENGTH_SHORT).show()
    }

    private fun checkCameraPermission() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        scannerContainer?.visibility = View.VISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(scannerView?.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        scannerContainer?.visibility = View.GONE
        ProcessCameraProvider.getInstance(this).get().unbindAll()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        try {
                            val json = JSONObject(rawValue)
                            runOnUiThread {
                                val s = json.optString("s")
                                val i = json.optString("i")
                                val t = json.optString("t")
                                
                                etServerUrl.setText(s)
                                etDeviceId.setText(i)
                                etDeviceToken.setText(t)
                                
                                // Auto-save to prefs
                                if (s.isNotEmpty()) {
                                    prefs.edit()
                                        .putString(AgentConfig.KEY_SERVER_URL, s)
                                        .putString(AgentConfig.KEY_DEVICE_ID, i)
                                        .putString(AgentConfig.KEY_DEVICE_TOKEN, t)
                                        .apply()
                                }
                                
                                stopCamera()
                                Toast.makeText(this, "Setup complete & synced!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            // Not our JSON
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
