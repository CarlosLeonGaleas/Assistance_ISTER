package com.example.assistanceister
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/")
    suspend fun sendUrl(@Body urlData: UrlData): Response<WebData>
}

data class UrlData(val url: String)

// Cambia 'data' para reflejar el JSON completo que retorna tu backend
data class WebData(
    val correo: String?,
    val fecha_registro: String?,
    val identificacion: String?,
    val nombre: String?,
    val rol: String?
)