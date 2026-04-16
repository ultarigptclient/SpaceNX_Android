package net.spacenx.messenger.data.remote.api

import net.spacenx.messenger.data.remote.api.dto.MyPartRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SearchUserRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SubOrgRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncOrgRequestDTO
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST import retrofit2.http.Url

interface OrgApi {
    @POST
    suspend fun getMyPart(@Url url: String, @Body request: MyPartRequestDTO): Response<ResponseBody>

    @POST
    suspend fun getMyPartRequest(@Url url: String, @Body request: MyPartRequestDTO): Response<ResponseBody>

    @POST
    suspend fun getSubOrg(@Url url: String, @Body request: SubOrgRequestDTO): Response<ResponseBody>

    @POST
    suspend fun searchUser(@Url url: String, @Body request: SearchUserRequestDTO): Response<ResponseBody>

    @POST
    suspend fun syncOrg(@Url url: String, @Body request: SyncOrgRequestDTO): Response<ResponseBody>
}