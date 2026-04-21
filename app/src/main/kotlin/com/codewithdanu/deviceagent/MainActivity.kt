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
import android.os.Environment
import android.provider.Settings
import android.net.Uri
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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.codewithdanu.deviceagent.utils.PermissionInstructions
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var etDeviceId: EditText
    private lateinit var etDeviceToken: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var tvStatus: TextView
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
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "Location permission is required for monitoring", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        prefs = AgentConfig.getPrefs(this)

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
            setPadding(0, 0, 0, 32)
        }

        tvStatus = TextView(this).apply {
            text = "Status: Initializing..."
            textSize = 11f
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF475569.toInt()) // Slate 600
            typeface = android.graphics.Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF1F5F9.toInt())
                cornerRadius = 16f
            }
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

                val normalizedServer = server.let { if (it.startsWith("http")) it else "http://$it" }.trim()

                prefs.edit()
                    .putString(AgentConfig.KEY_SERVER_URL, normalizedServer)
                    .putString(AgentConfig.KEY_DEVICE_ID, id)
                    .putString(AgentConfig.KEY_DEVICE_TOKEN, token)
                    .apply()

                etServerUrl.setText(normalizedServer)

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
            setPadding(48, 24, 48, 24)
            setOnClickListener { requestDeviceAdmin() }
        }

        val btnFileAccess = Button(this).apply {
            text = "Activate Full File Access"
            setBackgroundColor(0xFF6366F1.toInt()) // Indigo 500
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener { requestAllFilesAccess() }
            visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) View.VISIBLE else View.GONE
        }

        val btnXiaomi = Button(this).apply {
            text = "Fix Auto-Start for Xiaomi"
            setBackgroundColor(0xFFEA580C.toInt()) // Orange 600
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener { requestAutoStartPermission() }
            visibility = if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) View.VISIBLE else View.GONE
        }

        val btnBatteryOpt = Button(this).apply {
            text = "Disable Battery Optimization (Recommended)"
            setBackgroundColor(0xFF10B981.toInt()) // Emerald 500
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }

        val btnForceReconnect = Button(this).apply {
            text = "Force Re-connect Socket"
            styleAsSecondary()
            setOnClickListener {
                SocketManager.reconnect()
                Toast.makeText(this@MainActivity, "Reconnecting...", Toast.LENGTH_SHORT).show()
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "Activate Activity Monitor"
            setBackgroundColor(0xFF8B5CF6.toInt()) // Violet 500
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                PermissionInstructions.showAccessibilityTutorial(this@MainActivity) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val btnScreenCapture = Button(this).apply {
            text = "Enable Screen Capture Session"
            setBackgroundColor(0xFF06B6D4.toInt()) // Cyan 500
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                if (ScreenCaptureHelper.hasPermission()) {
                    Toast.makeText(this@MainActivity, "✨ Screen Capture is already active!", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionInstructions.showScreenCaptureInfo(this@MainActivity) {
                        ScreenCaptureHelper.requestPermission(this@MainActivity)
                    }
                }
            }
        }

        val btnRestricted = Button(this).apply {
            text = "Fix Restricted Settings"
            styleAsSecondary()
            setOnClickListener {
                PermissionInstructions.showRestrictedSettingsTutorial(this@MainActivity) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ─── SECTION: CONNECTION ───────────────────────────────────────────────
        val sectionConnection = createSection("Connection Setup", R.drawable.ic_save) {
            addView(label("Server URL"))
            addView(etServerUrl)
            addView(label("Device Identity"))
            addView(etDeviceId)
            addView(label("Security Token"))
            addView(etDeviceToken)
            addView(TextView(this@MainActivity).apply { height = 32 })
            addView(btnScan)
            addView(btnSave)
        }

        // ─── SECTION: OPTIMIZATION ─────────────────────────────────────────────
        val sectionOptimization = createSection("System Readiness", R.drawable.ic_devices) {
            addView(btnLockAdmin)
            addView(spacer(12))
            addView(btnBatteryOpt)
            addView(spacer(12))
            addView(btnXiaomi)
        }

        // ─── SECTION: FEATURES ─────────────────────────────────────────────────
        val sectionFeatures = createSection("Advanced Features", R.drawable.ic_camera) {
            addView(btnAccessibility)
            addView(spacer(12))
            addView(btnScreenCapture)
            addView(spacer(12))
            addView(btnFileAccess)
            addView(spacer(12))
            addView(btnRestricted)
        }

        // ─── SECTION: ACTIONS ──────────────────────────────────────────────────
        val sectionActions = createSection("Service Control", R.drawable.ic_play) {
            addView(btnStart)
            addView(btnStop)
            addView(spacer(24))
            addView(btnForceReconnect)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(tvStatus)
        layout.addView(spacer(48))

        layout.addView(sectionConnection)
        layout.addView(spacer(32))
        layout.addView(sectionOptimization)
        layout.addView(spacer(32))
        layout.addView(sectionFeatures)
        layout.addView(spacer(32))
        layout.addView(sectionActions)
        layout.addView(spacer(64))

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
        
        // Finalize setup
        initWorkManager()
        setupStatusListener()
        handleIntent(intent)
    }

    private fun setupStatusListener() {
        SocketManager.setStatusListener { status, detail ->
            runOnUiThread {
                val color = when (status) {
                    SocketManager.ConnectionStatus.CONNECTED -> 0xFF059669.toInt() // Emerald 600
                    SocketManager.ConnectionStatus.CONNECTING -> 0xFFD97706.toInt() // Amber 600
                    SocketManager.ConnectionStatus.ERROR -> 0xFFDC2626.toInt() // Rose 600
                    SocketManager.ConnectionStatus.DISCONNECTED -> 0xFF475569.toInt() // Slate 600
                }
                tvStatus.text = "SOCKET: $status${if (detail != null) " ($detail)" else ""}"
                tvStatus.setTextColor(color)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "deviceagent" && data.host == "setup") {
            val s = data.getQueryParameter("s") ?: ""
            val i = data.getQueryParameter("i") ?: ""
            val t = data.getQueryParameter("t") ?: ""

            if (s.isNotEmpty() && i.isNotEmpty()) {
                val normalizedServer = s.let { if (it.startsWith("http")) it else "http://$it" }.trim()
                Log.i("MainActivity", "Deep link setup detected: $normalizedServer")
                etServerUrl.setText(normalizedServer)
                etDeviceId.setText(i)
                etDeviceToken.setText(t)

                prefs.edit()
                    .putString(AgentConfig.KEY_SERVER_URL, normalizedServer)
                    .putString(AgentConfig.KEY_DEVICE_ID, i)
                    .putString(AgentConfig.KEY_DEVICE_TOKEN, t)
                    .apply()

                Toast.makeText(this, "Dashboard Sync Link Detected! Starting...", Toast.LENGTH_LONG).show()
                checkServicePermissionsAndStart()
            }
        }
    }

    private fun initWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<BootWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AgentWatchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun label(txt: String) = TextView(this).apply {
        text = txt
        textSize = 10f
        setPadding(4, 32, 0, 8)
        setTextColor(0xFF94A3B8.toInt()) // Slate 400
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun spacer(h: Int) = TextView(this).apply { height = h }

    private fun createSection(title: String, iconRes: Int, block: LinearLayout.() -> Unit): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 48f
                setStroke(2, 0xFFE2E8F0.toInt()) // Slate 200
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 32)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val icon = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(0xFF6366F1.toInt()) // Indigo 500
            layoutParams = LinearLayout.LayoutParams(48, 48)
        }

        val titleView = TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1E293B.toInt()) // Slate 800
            setPadding(24, 0, 0, 0)
            letterSpacing = 0.1f
        }

        header.addView(icon)
        header.addView(titleView)
        outer.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            block()
        }
        outer.addView(content)

        return outer
    }

    private fun Button.styleAsSecondary() {
        alpha = 0.6f
    }

    private fun checkServicePermissionsAndStart() {
        if (isServiceRunning(AgentService::class.java)) {
            Toast.makeText(this, "Monitoring is already active!", Toast.LENGTH_SHORT).show()
            return
        }

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

    private fun requestAutoStartPermission() {
        try {
            val intent = Intent()
            intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent()
                intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.Main")
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Please enable Auto-start for this app in Settings", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoringService() {
        val server = prefs.getString(AgentConfig.KEY_SERVER_URL, "") ?: ""
        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "") ?: ""
        val token = prefs.getString(AgentConfig.KEY_DEVICE_TOKEN, "") ?: ""

        if (server.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Please configure Server, Device ID, and Token first", Toast.LENGTH_LONG).show()
            return
        }

        startForegroundService(Intent(this, AgentService::class.java))
        Toast.makeText(this, "Monitoring active on $server", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
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
                                
                                if (s.isNotEmpty()) {
                                    prefs.edit()
                                        .putString(AgentConfig.KEY_SERVER_URL, s)
                                        .putString(AgentConfig.KEY_DEVICE_ID, i)
                                        .putString(AgentConfig.KEY_DEVICE_TOKEN, t)
                                        .apply()
                                    
                                    stopCamera()
                                    Toast.makeText(this, "Setup synced! Starting service...", Toast.LENGTH_LONG).show()
                                    checkServicePermissionsAndStart()
                                } else {
                                    stopCamera()
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Full File Access already granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
