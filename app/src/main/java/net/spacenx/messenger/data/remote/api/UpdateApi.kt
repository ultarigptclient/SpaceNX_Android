package net.spacenx.messenger.data.remote.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface UpdateApi {
    @GET
    suspend fun checkUpdate(@Url url: String): Response<String>
}
