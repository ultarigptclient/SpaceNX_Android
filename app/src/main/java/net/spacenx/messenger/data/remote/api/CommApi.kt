package net.spacenx.messenger.data.remote.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Message / Noti REST API (generic JSON body)
 */
interface CommApi {
    @POST
    suspend fun post(@Url url: String, @Body body: RequestBody): Response<ResponseBody>
}
