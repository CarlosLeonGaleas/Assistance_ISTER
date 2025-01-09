package com.example.assistanceister

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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

                // Ajustar velocidad de lectura (1.0 es normal, 0.5 es más lento, 2.0 es más rápido)
                textToSpeech.setSpeechRate(1.3f)

                // Ajustar tono de voz (1.0 es normal, 0.5 es más grave, 2.0 es más agudo)
                textToSpeech.setPitch(0.8f)
            }else {
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
        val sourceFile = File(getExternalFilesDir(null), "qr_data.csv")
        if (sourceFile.exists()) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs() // Crea la carpeta Descargas si no existe
            }

            val targetFile = File(downloadsDir, sourceFile.name)
            try {
                sourceFile.copyTo(targetFile, overwrite = true)
                Toast.makeText(this, "Archivo guardado en Descargas", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al guardar archivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No hay archivo para descargar", Toast.LENGTH_SHORT).show()
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @Composable
    fun MainScreen(onScanningClick: () -> Unit,
                        onResetClick: () -> Unit,
                        onDownloadClick: () -> Unit) {
        var showDialog by remember { mutableStateOf(false) } // Controla si se muestra el diálogo
        var showManualForm by remember { mutableStateOf(false) }
        var cedula by remember { mutableStateOf("") }
        var nombreCompleto by remember { mutableStateOf("") }
        var correo by remember { mutableStateOf("") }
        var rol by remember { mutableStateOf("") }
        val roles = listOf("Público Externo", "Estudiante", "Docente", "Administrativo")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.White)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(25.dp))
            // Imagen (Logo del Departamento)
            Image(
                painter = painterResource(id = R.drawable.logo_departamento),
                contentDescription = "Logo del Departamento",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

            if (!showManualForm) {
                Spacer(modifier = Modifier.height(80.dp))

                // Título
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

                // Botón para activar el Modo de Registro Automático
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

                // Botón para activar el Modo Registro Manual
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

                    // Diálogo de confirmación
                    if (showDialog) {
                        AlertDialog(
                            onDismissRequest = { showDialog = false }, // Cierra el diálogo sin hacer nada
                            title = { Text("Confirmar acción") },
                            text = { Text("¿Estás seguro de que deseas resetear los datos? Esta acción no se puede deshacer.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDialog = false
                                        onResetClick() // Llama a la acción de reseteo
                                    }
                                ) {
                                    Text("Sí")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showDialog = false } // Solo cierra el diálogo
                                ) {
                                    Text("No")
                                }
                            }
                        )
                    }
                }

            }else {

                Spacer(modifier = Modifier.height(40.dp))
                // Título
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

                // Formulario de Registro Manual
                TextField(
                    value = cedula,
                    onValueChange = { cedula = it },
                    label = { Text("Cédula") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
                TextField(
                    value = nombreCompleto,
                    onValueChange = { nombreCompleto = it },
                    label = { Text("Nombre Completo") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
                TextField(
                    value = correo,
                    onValueChange = { correo = it },
                    label = { Text("Correo Electrónico") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
                DropdownRole(selectedRol = rol, onRolSelected = { rol = it }, roles = roles)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Button(onClick = {
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
                            // Limpiar campos
                            cedula = ""
                            nombreCompleto = ""
                            correo = ""
                            rol = ""
                        } else {
                            // Manejo de error
                            Toast.makeText(
                                this@MainActivity,
                                "Por favor, llene todos los campos",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Text("Guardar")
                    }
                    Button(onClick = {
                        showManualForm = false
                        // Limpiar campos
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

            // Línea divisora
            Divider(
                color = Color(0xFFFF8000),
                thickness = 3.dp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Pie de página
            Text(
                text = "Desarrollado por: Ing. Carlos León Galeas",
                color = Color(0xFF27348B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
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
                .fillMaxWidth()
                .wrapContentSize(Alignment.TopStart)
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (selectedRol.isNotEmpty()) selectedRol else "Seleccionar Rol"
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
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


    private fun setupRetrofit() {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://18.189.145.51/") // Cambia esto por la URL de tu backend
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun startQRScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Escanea un código QR")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.initiateScan()
    }

    private fun startContinuousScan() {
        isScanning = true
        lifecycleScope.launch {
            while (isScanning) {
                try {
                    startQRScanner()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }

                // Esperar 2 segundos antes de volver a escanear
                delay(2000)
            }
        }
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

                        // Modificado aquí para mostrar el mensaje correspondiente
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
            val file = File(getExternalFilesDir(null), "qr_data.csv")
            val fileExists = file.exists()

            FileWriter(file, true).use { writer ->
                if (!fileExists) {
                    writer.append("URL,Correo,FechaRegistro,Identificacion,Nombre,Rol\n")
                }
                writer.append("$url,$correo,$fechaRegistro,$identificacion,$nombre,$rol\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }
}