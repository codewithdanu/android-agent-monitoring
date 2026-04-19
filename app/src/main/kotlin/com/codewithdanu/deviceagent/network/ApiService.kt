package com.codewithdanu.deviceagent.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("api/files/upload")
    suspend fun uploadFile(
        @Part("deviceId") deviceId: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>
}
