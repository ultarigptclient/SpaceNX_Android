package net.spacenx.messenger.service.push

fun interface OnGmsTokenListener {
    fun onGmsToken(token: String, isRefresh: Boolean)
}
