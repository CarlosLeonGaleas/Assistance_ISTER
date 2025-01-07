package com.example.assistanceister

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileWriter

class MainActivity : ComponentActivity() {
    private lateinit var apiService: ApiService

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
                    QRScannerScreen(
                        onScanClick = { startQRScanner() }
                    )
                }
            }
        }
    }

    @Composable
    fun QRScannerScreen(onScanClick: () -> Unit) {
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

            Spacer(modifier = Modifier.height(100.dp))

            // Título
            Text(
                text = "Registro de Asistencia del Personal ISTER",
                color = Color(0xFF27348B),
                fontSize = 50.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                lineHeight = 50.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(100.dp))

            // Botón de Escanear QR
            Button(
                onClick = onScanClick,
                modifier = Modifier
                    .width(300.dp)
                    .height(49.dp)
                    .background(color = Color(0xFF27348B)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF27348B)
                )
            ) {
                Text(
                    text = "Escanear QR de la Credencial",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val qrUrl = result.contents
                processQRUrl(qrUrl)
            } else {
                Toast.makeText(this, "Escaneo cancelado", Toast.LENGTH_SHORT).show()
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