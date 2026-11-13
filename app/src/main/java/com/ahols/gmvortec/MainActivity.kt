package com.ahols.gmvortec

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var outputText: TextView
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    private val commandReference = listOf(
        "ATZ — reset adapter", "ATE0 — echo off", "ATL0 — linefeeds off", "ATS0 — spaces off",
        "ATH1 — show headers", "ATSP0 — automatic protocol", "ATDP — describe protocol", "ATDPN — protocol number",
        "0100 — supported PID bitmap", "0101 — MIL/readiness", "03 — stored DTCs", "07 — pending DTCs",
        "0A — permanent DTCs", "04 — clear DTCs (clears readiness)", "0200 — freeze frame PID support",
        "010C — engine RPM", "0105 — coolant temperature", "010D — vehicle speed", "010F — intake air temperature",
        "010B — MAP", "0111 — throttle position", "0104 — calculated load", "0106 / 0107 — fuel trims",
        "010E — ignition timing", "0114–011B — oxygen sensor data (vehicle-dependent)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 7)
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,18) }
        val scroller = ScrollView(this)
        scroller.addView(root)
        setContentView(scroller)
        titleBar()
        home()
    }

    private fun titleBar() {
        root.addView(TextView(this).apply {
            text = "GM VORTEC DIAGNOSTICS\n1996–1999 GM • 5.7L Vortec / OBD-II"
            textSize = 21f; gravity = Gravity.CENTER; setPadding(4,8,4,12)
        })
        status = TextView(this).apply { text = "Bluetooth: not connected"; textSize=14f; setPadding(4,8,4,8) }
        root.addView(status)
        val nav = LinearLayout(this)
        listOf("Home","Connect","Dashboard","DTCs","Terminal","Commands","GM Enhanced").forEach { label ->
            nav.addView(Button(this).apply { text=label; textSize=10f; setOnClickListener { show(label) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        outputText = TextView(this).apply { textSize=13f; setPadding(4,12,4,12) }
        root.addView(outputText)
    }

    private fun show(page: String) {
        root.removeViews(4, root.childCount-4)
        when(page) {
            "Home" -> home()
            "Connect" -> connectPage()
            "Dashboard" -> dashboard()
            "DTCs" -> dtcs()
            "Terminal" -> terminal()
            "Commands" -> commands()
            "GM Enhanced" -> enhanced()
        }
    }
    private fun addText(s:String, size:Float=16f) { root.addView(TextView(this).apply { text=s; textSize=size; setPadding(6,8,6,8) }) }
    private fun addButton(s:String, action:()->Unit) { root.addView(Button(this).apply { text=s; setOnClickListener { action() } }) }
    private fun home() {
        addText("Purpose-built starter for your late-90s GMC. Connect a paired ELM327 Bluetooth Classic adapter, then use Dashboard, DTCs, Terminal and command reference.")
        addText("Important: GM enhanced misfire counts and cam retard/offset require PCM-specific data definitions. This build marks these as configuration-needed rather than inventing live values.")
        addButton("Connect Bluetooth adapter") { show("Connect") }
        addButton("Initialize ELM327") { send("ATZ\rATE0\rATL0\rATS0\rATSP0\r") }
        addButton("Check protocol") { send("ATDP\rATDPN\r") }
    }
    private fun connectPage() {
        addText("Bluetooth Classic SPP • pair adapter in Android Bluetooth settings first.")
        addButton("List paired devices") {
            try {
                val devices = adapter?.bondedDevices ?: emptySet()
                if(devices.isEmpty()) { addText("No paired devices found. Pair your ELM327 in Android Settings."); return@addButton }
                val names = devices.map { "${it.name ?: "Unknown"} — ${it.address}" }.toTypedArray()
                AlertDialogBuilder(this, names) { index -> connect(devices.elementAt(index)) }.show()
            } catch(e: SecurityException) { addText("Bluetooth permission required: ${e.message}") }
        }
        addButton("Disconnect") { try { socket?.close(); socket=null; status.text="Bluetooth: disconnected" } catch(_:Exception){} }
    }
    private fun connect(device: BluetoothDevice) {
        thread {
            try {
                val s=device.createRfcommSocketToServiceRecord(spp); s.connect()
                socket=s; input=s.inputStream; output=s.outputStream
                runOnUiThread { status.text="Connected: ${device.name ?: device.address}" }
                readLoop()
            } catch(e:Exception) { runOnUiThread { status.text="Connection failed: ${e.message}" } }
        }
    }
    private fun readLoop() {
        val buffer=ByteArray(1024)
        try { while(socket?.isConnected==true) {
            val n=input?.read(buffer) ?: -1
            if(n>0) { val text=String(buffer,0,n); runOnUiThread { outputText.append(text) } }
        }} catch(_:Exception) {}
    }
    private fun send(command:String) {
        thread { try {
            output?.write(command.toByteArray()); output?.flush()
            runOnUiThread { outputText.append("\n> "+command.trim()+"\n") }
        } catch(e:Exception) { runOnUiThread { outputText.append("\nSend error: ${e.message}\n") } } }
    }
    private fun dashboard() {
        addText("Live OBD-II dashboard (poll manually in this starter). Values depend on PCM support.")
        listOf("RPM (010C)","Coolant °C (0105)","Speed km/h (010D)","Throttle % (0111)","MAP kPa (010B)","Intake air °C (010F)","Ignition timing (010E)","Short/long fuel trims (0106/0107)").forEach { label ->
            addButton("Read $label") { val cmd=label.substringAfter("(").substringBefore(")"); send(cmd+"\r") }
        }
        addText("Graphing, polling scheduler and decoded gauge presentation are planned for next build iteration.")
    }
    private fun dtcs() {
        addText("Diagnostic trouble codes")
        listOf("Stored codes (03)","Pending codes (07)","Permanent codes (0A)","Read readiness (0101)","Freeze-frame RPM (020C)","Freeze-frame coolant (0205)").forEach { label ->
            addButton(label) { send(label.substringAfter("(").substringBefore(")")+"\r") }
        }
        addButton("Clear codes — caution") { send("04\r") }
        addText("Clearing DTCs can erase readiness status. Use only when intended.")
    }
    private fun terminal() {
        addText("Terminal sends raw ELM327 / OBD commands. Commands should end in carriage return.")
        val field=EditText(this).apply { hint="Example: 010C"; singleLine=true }
        root.addView(field)
        addButton("Send command") { send(field.text.toString().trim()+"\r") }
        addButton("ATZ reset") { send("ATZ\r") }
        addButton("Show protocol") { send("ATDP\rATDPN\r") }
    }
    private fun commands() { addText("ELM327 and OBD-II command reference"); commandReference.forEach { addText(it,14f) } }
    private fun enhanced() {
        addText("GM Enhanced / late-90s Vortec")
        addText("Cylinder misfire counts (1–8): NOT YET DECODED. Requires confirmed GM PCM enhanced-mode command and response mapping.")
        addText("Oxygen sensors: standard PID requests listed in command reference; actual sensor availability/configuration varies by calibration.")
        addText("Cam retard/offset: NOT YET DECODED. Distributor-equipped 5.7L Vortec typically reports cam retard through a GM-specific scan data item; generic OBD-II PID is not standardized.")
        addText("This screen intentionally does not show fabricated counts/angles.")
        addText("Next implementation needs: exact PCM calibration/engine variant and validated enhanced PID definitions from a compatible GM scan tool/service reference.")
    }
}

private class AlertDialogBuilder(val activity: Activity, val items: Array<String>, val selected:(Int)->Unit) {
    fun show() { android.app.AlertDialog.Builder(activity).setTitle("Choose paired ELM327").setItems(items) { _, which -> selected(which) }.show() }
}
