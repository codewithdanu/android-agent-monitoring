package com.codewithdanu.deviceagent.network

import android.content.Context
import com.codewithdanu.deviceagent.AgentConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    private var retrofit: Retrofit? = null

    fun getApiService(context: Context): ApiService {
        val baseUrl = AgentConfig.getNormalizedServerUrl(context).let {
            if (it.endsWith("/")) it else "$it/"
        }

        // Recreate Retrofit if null or if the URL has changed
        if (retrofit == null || retrofit!!.baseUrl().toString() != baseUrl) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }
}
