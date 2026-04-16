package net.spacenx.messenger.data.remote.api

import net.spacenx.messenger.data.remote.api.dto.RefreshTokenRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncConfigRequestDTO
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface AuthApi {
    @POST
    suspend fun login(@Url url: String, @Body request: Map<String, String>): Response<ResponseBody>

    @POST
    suspend fun logout(@Url url: String, @Body request: Map<String, String>): Response<ResponseBody>

    @POST
    suspend fun syncConfig(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body request: SyncConfigRequestDTO
    ): Response<ResponseBody>

    @POST
    suspend fun refreshToken(@Url url: String, @Body request: RefreshTokenRequestDTO): Response<ResponseBody>
}
