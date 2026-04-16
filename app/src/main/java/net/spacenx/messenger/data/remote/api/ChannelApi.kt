package net.spacenx.messenger.data.remote.api

import net.spacenx.messenger.data.remote.api.dto.MakeChannelRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SendChatRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncChannelRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncChatRequestDTO
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface ChannelApi {
    @POST
    suspend fun syncChannel(@Url url: String, @Body request: SyncChannelRequestDTO): Response<ResponseBody>

    @POST
    suspend fun syncChat(@Url url: String, @Body request: SyncChatRequestDTO): Response<ResponseBody>

    @POST
    suspend fun makeChannel(@Url url: String, @Body request: MakeChannelRequestDTO): Response<ResponseBody>

    @POST
    suspend fun sendChat(@Url url: String, @Body request: SendChatRequestDTO): Response<ResponseBody>
}