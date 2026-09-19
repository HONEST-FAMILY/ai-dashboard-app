package com.honestfamily.dashboard

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearSync {
    private const val PATH_TOKEN = "/hf/token"

    fun pushToken(context: Context, token: String?) {
        if (token.isNullOrBlank()) return
        try {
            val request = PutDataMapRequest.create(PATH_TOKEN).apply {
                dataMap.putString("token", token)
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request)
        } catch (e: Exception) {
        }
    }
}
