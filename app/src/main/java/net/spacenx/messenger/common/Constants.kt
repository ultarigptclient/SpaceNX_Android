package net.spacenx.messenger.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

object Constants {

    const val TAG = "HybridWebMessenger"
    const val BUILDDATE = "ZD22"

    //@Deprecated 메시지 구분자 (소켓 통신용)
    //const val MESSAGE_DELIMITER = '\u000C' // form feed

    // 메시지 타입
    const val TYPE_TALK = "TALK"
    const val TYPE_MESSAGE = "MSG"
    const val TYPE_MCU = "MCU"
    const val TYPE_SYSTEM_NOTIFY = "NOTI"
    const val TYPE_CUSTOM = "CUSTOM"
    const val TYPE_MAIL = "MAIL"

    @JvmStatic
    var myId: String = ""

    fun getUseSecureCapture(): Boolean {
        return false;
    }

    fun getMyId(context: Context): String? {
        if (!myId.isEmpty()) {
            return myId
        } else {
            val pref: SharedPreferences = context.getSharedPreferences("LoginInfo", Context.MODE_PRIVATE)
            myId = pref.getString("MYID", "")!!

            return myId
        }
    }

    @JvmStatic
    fun getUUIDByMyId(context: Context): String? {
        val getKey: String = myId + "_" + "uuid"

        val sharedPref = context.getSharedPreferences("UUID", Context.MODE_PRIVATE)
        var uuid: String? = sharedPref.getString(getKey, "")!!.trim { it <= ' ' }

        if (uuid!!.isEmpty()) {
            uuid = UUID.randomUUID().toString()

            val editor = sharedPref.edit()
            editor.putString(getKey, uuid)
            editor.apply()
        }

        Log.d(TAG, "getUUIDByMyId key:$getKey, value:$uuid")
        return uuid
    }
}
