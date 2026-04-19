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
        if (retrofit == null) {
            val prefs = context.getSharedPreferences(AgentConfig.PREFS_NAME, Context.MODE_PRIVATE)
            var baseUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.SERVER_URL) ?: AgentConfig.SERVER_URL
            
            // Ensure trailing slash for Retrofit
            if (!baseUrl.endsWith("/")) baseUrl += "/"

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
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
