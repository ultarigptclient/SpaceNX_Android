package net.spacenx.messenger.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface StatusApi {
    @FormUrlEncoded
    @POST("api/status/presence")
    suspend fun setPresence(
        @Field("userId") userId: String,
        @Field("status") status: Int
    ): Response<ResponseBody>
}
