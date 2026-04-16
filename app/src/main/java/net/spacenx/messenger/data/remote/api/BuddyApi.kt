package net.spacenx.messenger.data.remote.api

import net.spacenx.messenger.data.remote.api.dto.SyncBuddyRequestDTO
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface BuddyApi {
    @POST
    suspend fun syncBuddy(@Url url: String, @Body request: SyncBuddyRequestDTO): Response<ResponseBody>
}