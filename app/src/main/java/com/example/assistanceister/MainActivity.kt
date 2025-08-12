package com.example.assistanceister

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var apiService: ApiService
    private var isScanning = false
    private lateinit var textToSpeech: TextToSpeech
    private var isProcessing = false // Variable para evitar procesamiento múltiple

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupRetrofit()
        checkPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onScanningClick = { startContinuousScan() },
                        onResetClick = { resetFile() },
                        onDownloadClick = { downloadFile() }
                    )
                }
            }
        }
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.getDefault()
                textToSpeech.setSpeechRate(1.3f)
                textToSpeech.setPitch(0.8f)
            } else {
                Log.e("TTS", "Inicialización fallida")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::textToSpeech.isInitialized) {
            textToSpeech.shutdown()
        }
    }

    private fun resetFile() {
        val file = File(getExternalFilesDir(null), "assistance_data.csv")
        if (file.exists()) {
            if (file.delete()) {
                Toast.makeText(this, "Archivo reseteado correctamente", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error al resetear el archivo", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No hay archivo para resetear", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile() {
        val sourceFile = File(getExternalFilesDir(null), "assistance_data.csv")
        if (!sourceFile.exists()) {
            Toast.makeText(this, "No hay archivo para descargar", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Para Android 10+ usar MediaStore para acceso a carpetas públicas
                saveFileUsingMediaStore(sourceFile)
            } else {
                // Para versiones anteriores, acceso directo a carpetas públicas
                saveFileToPublicDirectory(sourceFile)
            }
        } catch (e: Exception) {
            Log.e("Download", "Error al guardar archivo", e)
            Toast.makeText(this, "Error al guardar archivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveFileUsingMediaStore(sourceFile: File) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "assistance_data_${System.currentTimeMillis()}.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let { targetUri ->
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Toast.makeText(this, "Archivo guardado en Descargas", Toast.LENGTH_LONG).show()
        } ?: run {
            throw Exception("No se pudo crear el archivo en Descargas")
        }
    }

    private fun saveFileToPublicDirectory(sourceFile: File) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val targetFile = File(downloadsDir, "assistance_data_${System.currentTimeMillis()}.csv")
        sourceFile.copyTo(targetFile, overwrite = true)

        // Notificar al MediaScanner para que el archivo aparezca inmediatamente
        MediaScannerConnection.scanFile(
            this,
            arrayOf(targetFile.absolutePath),
            arrayOf("text/csv")
        ) { path, uri ->
            Log.d("MediaScanner", "Archivo escaneado: $path")
        }

        Toast.makeText(this, "Archivo guardado en Descargas: ${targetFile.name}", Toast.LENGTH_LONG).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Composable
    fun MainScreen(onScanningClick: () -> Unit,
                   onResetClick: () -> Unit,
                   onDownloadClick: () -> Unit) {
        var showDialog by remember { mutableStateOf(false) }
        var showManualForm by remember { mutableStateOf(false) }
        var cedula by remember { mutableStateOf("") }
        var nombreCompleto by remember { mutableStateOf("") }
        var correo by remember { mutableStateOf("") }
        var rol by remember { mutableStateOf("") }
        val roles = listOf("Público Externo", "Estudiante ISTER", "Docente ISTER", "Administrativo ISTER")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.White)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(25.dp))
            Image(
                painter = painterResource(id = R.drawable.logo_departamento),
                contentDescription = "Logo del Departamento",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

            if (!showManualForm) {
                Spacer(modifier = Modifier.height(80.dp))

                Text(
                    text = "Registro de Asistencia",
                    color = Color(0xFF27348B),
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    lineHeight = 60.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(80.dp))

                Button(
                    onClick = onScanningClick,
                    modifier = Modifier
                        .width(300.dp)
                        .height(39.dp)
                        .background(color = Color(0xFF27348B)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF27348B)
                    ),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Automático",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = 25.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { showManualForm = true },
                    modifier = Modifier
                        .width(300.dp)
                        .height(39.dp)
                        .background(color = Color(0xFF27348B)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF27348B)
                    ),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Manual",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = 25.sp)
                }

                Spacer(modifier = Modifier.height(80.dp))

                Row{
                    Button(
                        onClick = onDownloadClick,
                        modifier = Modifier
                            .width(143.dp)
                            .height(35.dp)
                            .background(color = Color(0xFF27348B)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF27348B)
                        )
                    ) {
                        Text(
                            text="Descargar Datos",
                            fontSize = 11.sp,
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Button(
                        onClick = { showDialog = true },
                        modifier = Modifier
                            .width(143.dp)
                            .height(35.dp)
                            .background(color = Color(0xFFFE8200)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFE8200)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text="Resetear Datos",
                            fontSize = 11.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (showDialog) {
                        AlertDialog(
                            onDismissRequest = { showDialog = false },
                            title = { Text("Confirmar acción") },
                            text = { Text("¿Estás seguro de que deseas resetear los datos? Esta acción no se puede deshacer.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDialog = false
                                        onResetClick()
                                    }
                                ) {
                                    Text("Sí")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showDialog = false }
                                ) {
                                    Text("No")
                                }
                            }
                        )
                    }
                }

            } else {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "Registre sus Datos",
                    color = Color(0xFF27348B),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    lineHeight = 60.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                TextField(
                    value = cedula,
                    onValueChange = { cedula = it },
                    label = { Text("Cédula", style = TextStyle(fontWeight = FontWeight.Bold)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor= Color(0x4027348B),
                        unfocusedContainerColor = Color(0x3327348B),
                        focusedLabelColor  = Color.Black,
                        unfocusedLabelColor  = Color.Black,
                        focusedIndicatorColor  = Color(0xFF27348B)
                    )
                )
                TextField(
                    value = nombreCompleto,
                    onValueChange = { nombreCompleto = it },
                    label = { Text("Nombre Completo", style = TextStyle(fontWeight = FontWeight.Bold)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor= Color(0x4027348B),
                        unfocusedContainerColor = Color(0x3327348B),
                        focusedLabelColor  = Color.Black,
                        unfocusedLabelColor  = Color.Black,
                        focusedIndicatorColor  = Color(0xFF27348B)
                    )
                )
                TextField(
                    value = correo,
                    onValueChange = { correo = it },
                    label = { Text("Correo Electrónico", style = TextStyle(fontWeight = FontWeight.Bold)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor= Color(0x4027348B),
                        unfocusedContainerColor = Color(0x3327348B),
                        focusedLabelColor  = Color.Black,
                        unfocusedLabelColor  = Color.Black,
                        focusedIndicatorColor  = Color(0xFF27348B)
                    )
                )
                DropdownRole(selectedRol = rol, onRolSelected = { rol = it }, roles = roles)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Button(
                        modifier = Modifier
                            .width(110.dp)
                            .height(35.dp)
                            .background(color = Color(0xFF27348B)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF27348B)
                        ),
                        onClick = {
                            if (cedula.isNotEmpty() && nombreCompleto.isNotEmpty() && correo.isNotEmpty() && rol.isNotEmpty()) {
                                val currentDateTime = LocalDateTime.now()
                                val formattedDateTime = currentDateTime.format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                )
                                saveToCSV(
                                    "MANUAL",
                                    correo,
                                    formattedDateTime,
                                    cedula,
                                    nombreCompleto,
                                    rol
                                )
                                showManualForm = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "El registro se GUARDÓ correctamente " + nombreCompleto,
                                    Toast.LENGTH_SHORT
                                ).show()
                                cedula = ""
                                nombreCompleto = ""
                                correo = ""
                                rol = ""
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Por favor, llene todos los campos",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) {
                        Text("Guardar")
                    }
                    Button(
                        modifier = Modifier
                            .width(110.dp)
                            .height(35.dp)
                            .background(color = Color(0xFF27348B)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF27348B)
                        ),
                        onClick = {
                            showManualForm = false
                            Toast.makeText(
                                this@MainActivity,
                                "El registro se CANCELÓ correctamente. ",
                                Toast.LENGTH_SHORT
                            ).show()
                            cedula = ""
                            nombreCompleto = ""
                            correo = ""
                            rol = ""
                        }) {
                        Text("Cancelar")
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            Divider(
                color = Color(0xFFFF8000),
                thickness = 3.dp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            CopyrightFooter()
        }
    }

    @Composable
    fun DropdownRole(
        selectedRol: String,
        onRolSelected: (String) -> Unit,
        roles: List<String>
    ) {
        var expanded by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth().padding(16.dp)
                .wrapContentSize(Alignment.TopStart)
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x3327348B),
                    contentColor = Color.Black
                ),
                border = BorderStroke(0.5.dp, Color(0xFF27348B)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (selectedRol.isNotEmpty()) selectedRol else "Seleccionar Rol",
                    fontWeight = if (selectedRol.isNotEmpty()) FontWeight.Normal else FontWeight.Bold,
                    textAlign = TextAlign.Left
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                roles.forEach { role ->
                    DropdownMenuItem(
                        text = { Text(text = role) },
                        onClick = {
                            onRolSelected(role)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun CopyrightFooter() {
        Text(
            text = "© 2025 Carlos León Galeas. Todos los derechos reservados.",
            color = Color(0xFF27348B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    private fun setupRetrofit() {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://3.148.205.45/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Permisos de almacenamiento según la versión de Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // Android 12 y anteriores
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun startQRScanner() {
        if (isProcessing) return // Evitar múltiples escaneos simultáneos

        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Escanea un código QR")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        // Configuraciones para mejorar rendimiento
        integrator.setTorchEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    private fun startContinuousScan() {
        isScanning = true
        startQRScanner() // Iniciar el primer escaneo inmediatamente
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val qrUrl = result.contents
                processQRUrl(qrUrl)
            } else {
                Toast.makeText(this, "Escaneo cancelado", Toast.LENGTH_SHORT).show()
                textToSpeech.speak("Escaneo cancelado", TextToSpeech.QUEUE_FLUSH, null, null)
                isScanning = false
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun processQRUrl(url: String) {
        if (isProcessing) return // Evitar procesamiento múltiple
        isProcessing = true

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.sendUrl(UrlData(url))
                }

                if (response.isSuccessful) {
                    val webData = response.body()
                    if (webData != null) {
                        saveToCSV(
                            url,
                            webData.correo ?: "null",
                            webData.fecha_registro ?: "null",
                            webData.identificacion ?: "null",
                            webData.nombre ?: "null",
                            webData.rol ?: "null"
                        )

                        val toastMessage = if (webData.nombre != null) {
                            "Gracias por Asistir ${webData.nombre}"
                        } else {
                            "QR de credencial inválida"
                        }

                        textToSpeech.speak(toastMessage, TextToSpeech.QUEUE_FLUSH, null, null)

                        Toast.makeText(
                            this@MainActivity,
                            toastMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        saveToCSV(url, "null", "null", "null", "null", "null")
                        Toast.makeText(
                            this@MainActivity,
                            "El cuerpo de la respuesta está vacío",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Error al procesar la URL: ${response.errorBody()?.string()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isProcessing = false
                // Continuar escaneando si está en modo continuo
                if (isScanning) {
                    // Pequeño delay antes del siguiente escaneo
                    delay(500)
                    startQRScanner()
                }
            }
        }
    }

    private fun saveToCSV(
        url: String,
        correo: String,
        fechaRegistro: String,
        identificacion: String,
        nombre: String,
        rol: String
    ) {
        try {
            val file = File(getExternalFilesDir(null), "assistance_data.csv")
            val fileExists = file.exists()

            FileWriter(file, true).use { writer ->
                if (!fileExists) {
                    writer.append("URL,Correo,FechaRegistro,Identificacion,Nombre,Rol\n")
                }
                writer.append("$url,$correo,$fechaRegistro,$identificacion,$nombre,$rol\n")
            }
        } catch (e: Exception) {
            Log.e("CSV", "Error guardando CSV", e)
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }
}